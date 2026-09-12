package com.wasparks.api.meta;

import com.wasparks.api.error.ApiErrorCode;
import com.wasparks.api.error.ApiException;
import com.wasparks.api.internal.InternalDtos;
import com.wasparks.api.util.PhoneNumbers;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Validates a Meta Cloud API message body and translates it into the internal send request
 * (epic §B5, internal.md).
 *
 * <p>This is the <b>local half of the preflight</b>: everything that can be decided without asking
 * tenants-service anything — the recipient's shape, whether we support the type, whether the body
 * actually carries what that type needs. Doing it here means an obviously malformed request costs one
 * CPU microsecond instead of a network round trip, and the error the client sees is the same
 * Meta-shaped one either way.
 *
 * <h2>The translation</h2>
 * Meta expresses template variables as a list of {@code components}, each with positional
 * {@code parameters}; tenants-service expresses them as {@code bodyParams}, a {@code headerParam} and
 * {@code buttonParams} (the shape its UI composer already uses). Mapping between them is this class's
 * other job, and it is the single place the two vocabularies meet — a client writes Meta's dialect and
 * never learns ours.
 */
@Component
public class MetaMessageValidator {

    /**
     * v1 supports these five. {@code interactive}, {@code location}, {@code reaction} and
     * {@code contacts} are rejected as unsupported (epic §B5); media uploads by id are P3.
     */
    private static final Set<String> SUPPORTED_TYPES =
            Set.of("text", "template", "image", "document", "video");

    /** {@code messages.client_ref} is VARCHAR(64). */
    private static final int CLIENT_REF_MAX = 64;

    /**
     * Validate and translate.
     *
     * @param messageId the UUIDv7 minted for this send — it becomes {@code messages.id} upstream
     * @param mode      LIVE or TEST, already resolved from the key and the plan
     */
    public InternalDtos.SendRequest toInternalRequest(MetaMessageRequest request, String phoneNumberId,
                                                      UUID messageId, String mode) {
        requireMessagingProduct(request);

        String to = request.getTo();
        if (to == null || to.isBlank()) {
            throw ApiException.of(ApiErrorCode.INVALID_REQUEST, "`to` is required.");
        }
        if (!PhoneNumbers.isValid(to)) {
            // Meta code 100 — the recipient is not a valid phone number (epic §B5).
            throw ApiException.of(ApiErrorCode.INVALID_REQUEST,
                    "`to` must be a valid E.164 phone number, for example +919876543210.");
        }

        String type = request.getType() == null ? "text" : request.getType().toLowerCase();
        if (!SUPPORTED_TYPES.contains(type)) {
            throw ApiException.of(ApiErrorCode.UNSUPPORTED_TYPE,
                            "Message type `" + type + "` is not supported in v1.")
                    .withDetail("supported", SUPPORTED_TYPES);
        }

        String clientRef = request.getClientRef();
        if (clientRef != null && clientRef.length() > CLIENT_REF_MAX) {
            throw ApiException.of(ApiErrorCode.INVALID_REQUEST,
                    "`client_ref` must be " + CLIENT_REF_MAX + " characters or fewer.");
        }

        String text = null;
        InternalDtos.Template template = null;
        InternalDtos.Media media = null;

        switch (type) {
            case "text" -> {
                if (request.getText() == null || request.getText().getBody() == null
                        || request.getText().getBody().isBlank()) {
                    throw ApiException.of(ApiErrorCode.INVALID_REQUEST,
                            "`text.body` is required when type is `text`.");
                }
                text = request.getText().getBody();
            }
            case "template" -> template = toTemplate(request.getTemplate());
            default -> media = toMedia(type, request);
        }

        return new InternalDtos.SendRequest(
                messageId.toString(),
                phoneNumberId,
                PhoneNumbers.normalize(to),
                type,
                text,
                template,
                media,
                mode,
                clientRef);
    }

    /**
     * Meta requires {@code messaging_product: "whatsapp"} on every send and errors without it. Enforcing
     * it means a client that omits it fails here exactly as it would against Meta, rather than
     * succeeding with us and failing when they switch back.
     */
    private void requireMessagingProduct(MetaMessageRequest request) {
        String product = request.getMessagingProduct();
        if (product == null || product.isBlank()) {
            throw ApiException.of(ApiErrorCode.INVALID_REQUEST,
                    "`messaging_product` is required and must be `whatsapp`.");
        }
        if (!"whatsapp".equalsIgnoreCase(product)) {
            throw ApiException.of(ApiErrorCode.INVALID_REQUEST,
                    "`messaging_product` must be `whatsapp`.");
        }
    }

    private InternalDtos.Media toMedia(String type, MetaMessageRequest request) {
        MetaMessageRequest.Media media = switch (type) {
            case "image" -> request.getImage();
            case "document" -> request.getDocument();
            case "video" -> request.getVideo();
            default -> null;
        };
        if (media == null) {
            throw ApiException.of(ApiErrorCode.INVALID_REQUEST,
                    "`" + type + "` is required when type is `" + type + "`.");
        }
        if (media.getLink() == null || media.getLink().isBlank()) {
            // Media by id is a P3 feature; say so explicitly rather than reporting a missing field,
            // because a client migrating from Meta will have working `id` code and needs to know why.
            String reason = media.getId() != null
                    ? "Media by `id` is not supported in v1 — upload the file to a public URL and send "
                      + "`link` instead."
                    : "`" + type + ".link` is required.";
            throw ApiException.of(ApiErrorCode.INVALID_REQUEST, reason);
        }
        if (!isHttpUrl(media.getLink())) {
            throw ApiException.of(ApiErrorCode.INVALID_REQUEST,
                    "`" + type + ".link` must be an http(s) URL WhatsApp can fetch.");
        }
        return new InternalDtos.Media(media.getLink(), media.getCaption(), media.getFilename());
    }

    /**
     * Translate Meta's {@code components} into the internal template shape.
     *
     * <p>Meta's parameters are positional within each component, which is why body parameters come out
     * as a plain ordered list: {@code bodyParams[0]} is {@code {{1}}}. Header and button parameters are
     * single-valued upstream, so only the first of each component is taken — a second one would be a
     * client error Meta itself would reject, and quietly using the last would be worse than ignoring it.
     */
    private InternalDtos.Template toTemplate(MetaMessageRequest.Template template) {
        if (template == null) {
            throw ApiException.of(ApiErrorCode.INVALID_REQUEST,
                    "`template` is required when type is `template`.");
        }
        if (template.getName() == null || template.getName().isBlank()) {
            throw ApiException.of(ApiErrorCode.INVALID_REQUEST, "`template.name` is required.");
        }
        if (template.getLanguage() == null || template.getLanguage().getCode() == null
                || template.getLanguage().getCode().isBlank()) {
            throw ApiException.of(ApiErrorCode.INVALID_REQUEST,
                    "`template.language.code` is required, for example `en_US`.");
        }

        List<String> bodyParams = new ArrayList<>();
        InternalDtos.HeaderParam headerParam = null;
        List<InternalDtos.ButtonParam> buttonParams = new ArrayList<>();

        if (template.getComponents() != null) {
            for (MetaMessageRequest.Component component : template.getComponents()) {
                String componentType = component.getType() == null
                        ? "" : component.getType().toLowerCase();
                switch (componentType) {
                    case "body" -> bodyParams.addAll(textValues(component));
                    case "header" -> headerParam = toHeaderParam(component);
                    case "button" -> buttonParams.add(toButtonParam(component));
                    default -> throw ApiException.of(ApiErrorCode.INVALID_REQUEST,
                            "Unknown template component type `" + component.getType() + "`.");
                }
            }
        }

        return new InternalDtos.Template(
                template.getName(),
                template.getLanguage().getCode(),
                bodyParams.isEmpty() ? null : bodyParams,
                headerParam,
                buttonParams.isEmpty() ? null : buttonParams);
    }

    private List<String> textValues(MetaMessageRequest.Component component) {
        List<String> values = new ArrayList<>();
        if (component.getParameters() == null) {
            return values;
        }
        for (MetaMessageRequest.Parameter parameter : component.getParameters()) {
            if (parameter.getText() == null) {
                throw ApiException.of(ApiErrorCode.INVALID_REQUEST,
                        "Body parameters must each carry `text`.");
            }
            values.add(parameter.getText());
        }
        return values;
    }

    private InternalDtos.HeaderParam toHeaderParam(MetaMessageRequest.Component component) {
        if (component.getParameters() == null || component.getParameters().isEmpty()) {
            throw ApiException.of(ApiErrorCode.INVALID_REQUEST,
                    "A `header` component needs one parameter.");
        }
        MetaMessageRequest.Parameter parameter = component.getParameters().get(0);
        String parameterType = parameter.getType() == null ? "text" : parameter.getType().toLowerCase();

        String value = switch (parameterType) {
            case "text" -> parameter.getText();
            case "image" -> linkOf(parameter.getImage());
            case "document" -> linkOf(parameter.getDocument());
            case "video" -> linkOf(parameter.getVideo());
            default -> throw ApiException.of(ApiErrorCode.INVALID_REQUEST,
                    "Unsupported header parameter type `" + parameterType + "`.");
        };
        if (value == null || value.isBlank()) {
            throw ApiException.of(ApiErrorCode.INVALID_REQUEST,
                    "The `header` parameter has no value.");
        }
        return new InternalDtos.HeaderParam(parameterType, value);
    }

    private InternalDtos.ButtonParam toButtonParam(MetaMessageRequest.Component component) {
        int index;
        try {
            index = component.getIndex() == null ? 0 : Integer.parseInt(component.getIndex().trim());
        } catch (NumberFormatException e) {
            throw ApiException.of(ApiErrorCode.INVALID_REQUEST,
                    "`components[].index` must be a number for a button component.");
        }
        if (component.getParameters() == null || component.getParameters().isEmpty()) {
            throw ApiException.of(ApiErrorCode.INVALID_REQUEST,
                    "A `button` component needs one parameter.");
        }

        MetaMessageRequest.Parameter parameter = component.getParameters().get(0);
        String subType = component.getSubType() == null ? null : component.getSubType().toLowerCase();
        // Meta carries a URL button's value in `text` and a quick reply's in `payload`.
        String value = parameter.getText() != null ? parameter.getText() : parameter.getPayload();
        if (value == null || value.isBlank()) {
            throw ApiException.of(ApiErrorCode.INVALID_REQUEST,
                    "The `button` parameter at index " + index + " has no value.");
        }
        return new InternalDtos.ButtonParam(index, subType, value);
    }

    private String linkOf(MetaMessageRequest.Media media) {
        if (media == null) {
            return null;
        }
        if (media.getLink() == null && media.getId() != null) {
            throw ApiException.of(ApiErrorCode.INVALID_REQUEST,
                    "Media by `id` is not supported in v1 — send `link` instead.");
        }
        return media.getLink();
    }

    private boolean isHttpUrl(String value) {
        String lower = value.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }
}
