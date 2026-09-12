# WaSparks API — quickstart

Send your first WhatsApp message in about five minutes: create a key, send a template, receive the
delivery webhook.

Base URL: `https://developer.wasparks.com`

---

## 1. Create an API key

In WaSparks, go to **Developer → API keys → Create key**. Give it a name, choose a mode, and copy the
key — it is shown once and stored only as a hash, so if you lose it you revoke it and make another.

Choose **Test** for your first key. A `wsk_test_` key behaves identically end to end but never reaches
WhatsApp: messages are recorded with a `DRYRUN-` wamid and the status webhooks are synthesised, so you
can build and test the whole integration without messaging a real person. Switch to a `wsk_live_` key
when you are ready.

Keep the key server-side. It authenticates as your whole business, and anyone holding it can send on
your behalf.

## 2. Check it works

```bash
curl https://developer.wasparks.com/v1/account \
  -H "X-API-Key: wsk_test_YOUR_KEY"
```

You get your numbers, your plan limits and today's usage:

```json
{
  "tenant": { "name": "Acme Ltd" },
  "tokenStatus": "OK",
  "phoneNumbers": [
    { "phoneNumberId": "1234567890", "display": "+91 98765 43210",
      "status": "ACTIVE", "tokenStatus": "OK",
      "qualityRating": "GREEN", "messagingTier": "TIER_1K" }
  ],
  "plan": { "code": "STARTER", "limits": { "requestsPerMinute": 300, "messagesPerDay": 5000 } },
  "usage": { "messagesToday": 0, "messagesRemainingToday": 5000 }
}
```

Note the `phoneNumberId` — you need it for every send. And watch `tokenStatus`: `EXPIRING` means a
number's access token runs out within seven days, after which sends start failing.

## 3. Send a template

Outside a 24-hour customer service window, WhatsApp only allows approved templates. Use one that is
already `APPROVED` (`GET /v1/templates?status=APPROVED` lists them):

```bash
curl -X POST https://developer.wasparks.com/meta/whatsapp/v20.0/1234567890/messages \
  -H "X-API-Key: wsk_test_YOUR_KEY" \
  -H "Idempotency-Key: order-4711" \
  -H "Content-Type: application/json" \
  -d '{
    "messaging_product": "whatsapp",
    "to": "+919876543210",
    "type": "template",
    "client_ref": "order-4711",
    "template": {
      "name": "order_update",
      "language": { "code": "en_US" },
      "components": [
        { "type": "body", "parameters": [
            { "type": "text", "text": "Sam" },
            { "type": "text", "text": "#A1234" } ] }
      ]
    }
  }'
```

```json
{
  "messaging_product": "whatsapp",
  "contacts": [ { "input": "+919876543210", "wa_id": "919876543210" } ],
  "messages": [ { "id": "msg_01J8ZK9W2Q7R3T5V7X9Y1B3D5F", "message_status": "accepted" } ]
}
```

**`202 Accepted` means queued, not delivered.** Keep `messages[0].id` — that is how you look the
message up and how you recognise it in webhooks.

Two extras Meta ignores and we honour: `client_ref` is your own reference, echoed in every webhook
about this message, so you never have to store our ids. `Idempotency-Key` makes a retry safe — send the
same key and body again and you get the original response back instead of a second message. Send it on
every send; network timeouts are the normal reason a message goes out twice.

## 4. Receive the outcome

### Webhooks (recommended)

```bash
curl -X POST https://developer.wasparks.com/v1/webhooks \
  -H "X-API-Key: wsk_test_YOUR_KEY" \
  -H "Content-Type: application/json" \
  -d '{ "url": "https://your-app.example.com/wasparks/hook",
        "events": ["message.*", "template.*"] }'
```

The response carries a `secret`, once. Store it now.

Each delivery is a POST with three headers:

```
X-WaSparks-Event: message.sent
X-WaSparks-Delivery: 0193f0a2-...
X-WaSparks-Signature: t=1757600000,v1=6f3c...9a
```

and a body like:

```json
{ "id": "evt_0193f0a2...", "type": "message.sent",
  "created_at": "2026-09-11T10:00:02Z",
  "data": { "messageId": "01J8ZK9W...", "wamid": "wamid.HBgL...", "to": "+919876543210",
            "status": "SENT", "clientRef": "order-4711" } }
```

**Verify the signature before trusting anything.** Compute
`HMAC-SHA256(secret, t + "." + <raw request body>)`, hex-encode it, and compare with `v1`. Use the raw
bytes you received — re-serialising the JSON changes the digest. Reject anything whose `t` is more than
five minutes old; that check is what stops someone replaying a delivery they captured.

```js
const crypto = require("crypto");
function verify(rawBody, header, secret) {
  const parts = Object.fromEntries(header.split(",").map(p => p.split("=")));
  if (Math.abs(Date.now() / 1000 - Number(parts.t)) > 300) return false;
  const expected = crypto.createHmac("sha256", secret)
                         .update(parts.t + "." + rawBody).digest("hex");
  return crypto.timingSafeEqual(Buffer.from(expected), Buffer.from(parts.v1));
}
```

Answer `2xx` quickly and do your work afterwards. We wait 10 seconds, then retry at 10s, 1m, 5m, 30m
and 2h before giving up. A hundred failures in a row pauses the endpoint.

`POST /v1/webhooks/{id}/test` sends a `ping` through the real pipeline, and
`GET /v1/webhooks/{id}/deliveries` shows the last hundred attempts with the status we got back.

With a test key you will see a synthetic `message.sent` about two seconds after the send and a
`message.delivered` two seconds after that, with a `DRYRUN-` wamid — enough to build the whole
integration before you send anything real.

### Polling

```bash
curl https://developer.wasparks.com/v1/messages/msg_01J8ZK9W2Q7R3T5V7X9Y1B3D5F \
  -H "X-API-Key: wsk_test_YOUR_KEY"
```

`status` is `QUEUED`, then `SENT`, `DELIVERED`, `READ` or `FAILED`.

---

## Migrating from the Meta Cloud API

If you already call the Cloud API, change **two things**: point the base URL at
`https://developer.wasparks.com` and send your WaSparks key instead of your Meta token. Both
`X-API-Key: wsk_live_…` and `Authorization: Bearer wsk_live_…` work, so most clients need only a
configuration change. The path, the request body and the error shape are Meta's.

**One response field differs, and it matters.** Meta returns a `wamid` because Meta has already accepted
the message. We return `202` with `messages[0].id` set to *our* id (`msg_` + UUIDv7) and
`message_status: "accepted"`, because at that moment the message is queued and no `wamid` exists yet.
The real `wamid` arrives in the `message.sent` webhook and from `GET /v1/messages/{id}`.

So: if your code stores `messages[0].id` and later matches it against a status webhook, it keeps
working — just match on our id, or on your own `client_ref`. If your code assumes that field is a
`wamid` it can hand back to Meta, that is the one place you need to change.

Everything we can decide up front is still a synchronous error, never a `202` — a malformed recipient
(100), an unapproved template (132001), a text outside the 24-hour window (131047), an opted-out
recipient (131050), a disconnected number (409), no quota left (429). The error body is Meta's shape,
with our own code additionally in the `X-WaSparks-Error` header.

Not supported in v1: `interactive`, `location`, `reaction` and `contacts` messages, and media by `id`
(upload to a public URL and send `link`). These return `400` and name the reason.

## Rate limits

Every response carries `X-RateLimit-Limit`, `X-RateLimit-Remaining` and `X-RateLimit-Reset` — including
error responses. The window is a fixed minute per key, so `X-RateLimit-Reset` is the exact second your
allowance returns; sleep until then rather than backing off blindly. Over the limit is `429` with
`Retry-After`.

Message quotas are per day and per month, per business rather than per key. Over quota is `429` with
`X-Quota-Scope: day|month`, unless your plan allows overage — then the send is accepted and flagged
with `X-Quota-Warning: exceeded`.
