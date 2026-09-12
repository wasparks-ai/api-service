package com.wasparks.api.meta;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * Meta's Cloud API message object, as a <b>strict</b> DTO (epic §B5).
 *
 * <p>Strict is the whole design, and it is why this service runs Jackson with
 * {@code fail-on-unknown-properties: true} where the other services do not. A client that sends
 * {@code "tempate"} for {@code "template"}, or an {@code interactive} block we do not support, must be
 * told so at once — as {@code 400} with Meta code {@code 100}. Silently dropping the field would produce
 * a 202 for a message that can never be what the caller meant, and they would find out from their
 * customer rather than from us.
 *
 * <p>Validation of the type-specific parts is not done with bean-validation annotations but in
 * {@link MetaMessageValidator}, because the rules are conditional on {@code type} and a
 * {@code @NotNull} that only applies sometimes is a lie in the generated documentation.
 */
@Data
public class MetaMessageRequest {

    /** Meta requires this and only ever accepts {@code whatsapp}. */
    @JsonProperty("messaging_product")
    private String messagingProduct;

    /** Meta's recipient routing hint. Accepted and ignored — we always address an individual. */
    @JsonProperty("recipient_type")
    private String recipientType;

    @NotBlank
    private String to;

    /** {@code text}, {@code template}, {@code image}, {@code document}, {@code video}. */
    private String type;

    private Text text;
    private Template template;
    private Media image;
    private Media document;
    private Media video;

    /**
     * Meta message types v1 does not support (epic §B5).
     *
     * <p>They are declared, and deliberately untyped, so that the strict deserializer accepts the field
     * and {@link MetaMessageValidator} can answer {@code unsupported_type} — naming the actual problem.
     * Leaving them off the DTO would make {@code "type":"location"} fail as an unknown property, which
     * is the same HTTP status and Meta code but tells the caller their JSON is malformed rather than
     * that we do not do locations yet. A genuine typo is still rejected, because only these known names
     * are accepted.
     */
    private Object interactive;
    private Object location;
    private Object reaction;
    private Object contacts;
    private Object sticker;
    private Object audio;

    /**
     * Meta ignores unknown top-level fields on its own API; we use one. {@code client_ref} is the
     * caller's own reference and is echoed in every webhook about the message, which is what lets an
     * integration match a delivery report to its order without storing our ids (epic §B5).
     */
    @JsonProperty("client_ref")
    private String clientRef;

    @Data
    public static class Text {
        private String body;

        /** Meta's link-preview toggle. Accepted for compatibility; tenants-service does not use it. */
        @JsonProperty("preview_url")
        private Boolean previewUrl;
    }

    @Data
    public static class Template {
        private String name;
        private Language language;
        private List<Component> components;
    }

    @Data
    public static class Language {
        private String code;
        /** Meta's fallback policy. Accepted and ignored — we resolve the exact language. */
        private String policy;
    }

    /** A Meta template component: {@code header}, {@code body} or {@code button}. */
    @Data
    public static class Component {
        private String type;

        /** Button components only: {@code quick_reply} or {@code url}. */
        @JsonProperty("sub_type")
        private String subType;

        /** Button components only — Meta sends it as a string. */
        private String index;

        private List<Parameter> parameters;
    }

    /** One template parameter. Exactly one of the value fields is populated, per {@code type}. */
    @Data
    public static class Parameter {
        private String type;
        private String text;
        private Media image;
        private Media document;
        private Media video;

        /** {@code payload} carries the value for a quick-reply button. */
        private String payload;
    }

    /** Media by {@code link} only; {@code id} uploads are P3 (epic §B5). */
    @Data
    public static class Media {
        private String id;
        private String link;
        private String caption;
        private String filename;
    }
}
