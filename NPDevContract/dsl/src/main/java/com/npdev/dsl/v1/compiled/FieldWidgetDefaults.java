package com.npdev.dsl.v1.compiled;

import java.util.Locale;
import java.util.Set;

/**
 * Single source of truth for field input-widget behavior, shared by the pre-compile validator
 * ({@code SemanticValidator}, which only has AST-level facts about a field) and the generator
 * ({@code BusinessUiEmitter}, which has the compiled model) so the two can never disagree about
 * what a widget defaults to or whether it's a legal declaration for a given field shape. Mirrors
 * the {@link GuidePageDefaults} precedent: a static-only registry in this package, over primitive
 * facts rather than {@code FieldAst}/{@code CompiledField} themselves.
 */
public final class FieldWidgetDefaults {

    public static final String TEXT = "text";
    public static final String TEXTAREA = "textarea";
    public static final String NUMBER = "number";
    public static final String EMAIL = "email";
    public static final String TEL = "tel";
    public static final String URL = "url";
    public static final String COLOR = "color";
    public static final String DATE = "date";
    public static final String DATETIME_LOCAL = "datetime-local";
    public static final String CHECKBOX = "checkbox";
    public static final String SELECT = "select";
    public static final String AUTOCOMPLETE = "autocomplete";
    public static final String LOOKUP = "lookup";
    /** Legacy alias for {@link #LOOKUP} kept for samples authored before the generator honored it. */
    public static final String SEARCH_DIALOG = "search-dialog";
    public static final String MULTISELECT = "multiselect";
    public static final String IMAGE_SELECT = "image-select";
    public static final String CUSTOM = "custom";
    /** Structural label for an {@code object} field's nested editor; the editor itself always wins. */
    public static final String GROUP = "group";
    /** Structural label for an {@code array} field's nested editor; the editor itself always wins. */
    public static final String LIST = "list";
    /** Numeric slider; compatible only when the field declares both {@code schema.min} and {@code schema.max}. */
    public static final String RANGE = "range";
    /** Boolean switch -- a purely visual variant of {@link #CHECKBOX}, same value contract. */
    public static final String TOGGLE = "toggle";
    /** Masked string input. */
    public static final String PASSWORD = "password";
    /** Radio-button group -- an alternative to {@link #SELECT} for enum/reference fields. */
    public static final String RADIO = "radio";
    /** Tag/chip-style variant of {@link #MULTISELECT}, same eligibility, different rendering. */
    public static final String CHIPS = "chips";
    /** Thumbnail preview for a {@code file}-typed field, in place of a bare download link. */
    public static final String IMAGE_PREVIEW = "image-preview";

    public static final Set<String> SUPPORTED_WIDGETS = Set.of(
            TEXT, TEXTAREA, NUMBER, EMAIL, TEL, URL, COLOR, DATE, DATETIME_LOCAL, CHECKBOX,
            SELECT, AUTOCOMPLETE, LOOKUP, SEARCH_DIALOG, MULTISELECT, IMAGE_SELECT, CUSTOM, GROUP, LIST,
            RANGE, TOGGLE, PASSWORD, RADIO, CHIPS, IMAGE_PREVIEW
    );

    private static final Set<String> NUMERIC_TYPES = Set.of("int", "integer", "long", "decimal");

    private FieldWidgetDefaults() {
    }

    public enum Compatibility {
        COMPATIBLE,
        DISCOURAGED,
        INCOMPATIBLE,
        UNKNOWN_WIDGET
    }

    /**
     * The primitive facts about a field needed to classify a declared widget or pick a default,
     * gathered identically whether the caller has an AST {@code FieldAst} (validator, pre-compile)
     * or a {@code CompiledField} (generator).
     */
    public record FieldShape(
            String dslType,
            boolean isReference,
            boolean isMultiReference,
            boolean hasEnumValues,
            boolean isClosedEnumArray,
            boolean hasAnyEnumOptionIcon,
            boolean hasImageFieldHint,
            boolean hasCustomWidgetRef,
            boolean hasRangeBounds,
            boolean isImageOnlyFile
    ) {
    }

    /** Normalizes a legacy alias (today just {@code search-dialog}) to its canonical widget name. */
    public static String normalize(String widget) {
        if (widget == null) {
            return null;
        }
        String trimmed = widget.trim();
        return SEARCH_DIALOG.equalsIgnoreCase(trimmed) ? LOOKUP : trimmed.toLowerCase(Locale.ROOT);
    }

    public static String defaultWidget(
            String dslType,
            boolean isReference,
            boolean isMultiReference,
            boolean hasEnumValues
    ) {
        if (isMultiReference) {
            return MULTISELECT;
        }
        if (isReference) {
            return LOOKUP;
        }
        String type = dslType == null ? "" : dslType.trim().toLowerCase(Locale.ROOT);
        if ("enum".equals(type) && hasEnumValues) {
            return SELECT;
        }
        return switch (type) {
            case "date" -> DATE;
            case "datetime" -> DATETIME_LOCAL;
            case "boolean" -> CHECKBOX;
            case "int", "integer", "long", "decimal" -> NUMBER;
            default -> TEXT;
        };
    }

    public static Compatibility classify(FieldShape shape, String widget) {
        String normalized = normalize(widget);
        if (normalized == null || normalized.isBlank() || !SUPPORTED_WIDGETS.contains(normalized)) {
            return Compatibility.UNKNOWN_WIDGET;
        }

        if (CUSTOM.equals(normalized)) {
            return shape.hasCustomWidgetRef() ? Compatibility.COMPATIBLE : Compatibility.INCOMPATIBLE;
        }

        if (shape.isMultiReference()) {
            return (MULTISELECT.equals(normalized) || CHIPS.equals(normalized))
                    ? Compatibility.COMPATIBLE : Compatibility.DISCOURAGED;
        }

        String type = shape.dslType() == null ? "" : shape.dslType().trim().toLowerCase(Locale.ROOT);

        if ("array".equals(type)) {
            if (MULTISELECT.equals(normalized) || CHIPS.equals(normalized)) {
                return shape.isClosedEnumArray() ? Compatibility.COMPATIBLE : Compatibility.INCOMPATIBLE;
            }
            if (LIST.equals(normalized)) {
                return Compatibility.COMPATIBLE;
            }
            return Compatibility.DISCOURAGED;
        }

        if ("object".equals(type)) {
            return GROUP.equals(normalized) ? Compatibility.COMPATIBLE : Compatibility.DISCOURAGED;
        }

        if (IMAGE_PREVIEW.equals(normalized)) {
            if (!"file".equals(type)) {
                return Compatibility.INCOMPATIBLE;
            }
            return shape.isImageOnlyFile() ? Compatibility.COMPATIBLE : Compatibility.DISCOURAGED;
        }

        boolean isEnumWithValues = "enum".equals(type) && shape.hasEnumValues();

        if (TEXT.equals(normalized)) {
            return Compatibility.COMPATIBLE;
        }
        if (TEXTAREA.equals(normalized) || TEL.equals(normalized)) {
            // Neither carries a native HTML format constraint, so on a numeric/uuid field they
            // still render and accept the value -- mismatched, but not data-entry-breaking.
            if ("string".equals(type)) {
                return Compatibility.COMPATIBLE;
            }
            if (NUMERIC_TYPES.contains(type) || "uuid".equals(type)) {
                return Compatibility.DISCOURAGED;
            }
            return Compatibility.INCOMPATIBLE;
        }
        if (EMAIL.equals(normalized) || URL.equals(normalized)) {
            // Both impose a native browser format constraint (an "@" / URL-shaped value) inside a
            // real, non-novalidate <form> -- on a numeric/uuid field this silently blocks submit
            // for any value that isn't email/URL-shaped, which is worse than merely discouraged.
            return "string".equals(type) ? Compatibility.COMPATIBLE : Compatibility.INCOMPATIBLE;
        }
        if (PASSWORD.equals(normalized)) {
            return "string".equals(type) ? Compatibility.COMPATIBLE : Compatibility.INCOMPATIBLE;
        }
        if (COLOR.equals(normalized)) {
            return "string".equals(type) ? Compatibility.COMPATIBLE : Compatibility.INCOMPATIBLE;
        }
        if (NUMBER.equals(normalized)) {
            return NUMERIC_TYPES.contains(type) ? Compatibility.COMPATIBLE : Compatibility.INCOMPATIBLE;
        }
        if (RANGE.equals(normalized)) {
            if (!NUMERIC_TYPES.contains(type)) {
                return Compatibility.INCOMPATIBLE;
            }
            // Still a valid number input without declared bounds, just not a meaningful slider.
            return shape.hasRangeBounds() ? Compatibility.COMPATIBLE : Compatibility.DISCOURAGED;
        }
        if (DATE.equals(normalized)) {
            return "date".equals(type) ? Compatibility.COMPATIBLE : Compatibility.INCOMPATIBLE;
        }
        if (DATETIME_LOCAL.equals(normalized)) {
            return "datetime".equals(type) ? Compatibility.COMPATIBLE : Compatibility.INCOMPATIBLE;
        }
        if (CHECKBOX.equals(normalized) || TOGGLE.equals(normalized)) {
            return "boolean".equals(type) ? Compatibility.COMPATIBLE : Compatibility.INCOMPATIBLE;
        }
        if (SELECT.equals(normalized) || RADIO.equals(normalized)) {
            return (shape.isReference() || isEnumWithValues) ? Compatibility.COMPATIBLE : Compatibility.INCOMPATIBLE;
        }
        if (AUTOCOMPLETE.equals(normalized)) {
            return (shape.isReference() || isEnumWithValues) ? Compatibility.COMPATIBLE : Compatibility.INCOMPATIBLE;
        }
        if (LOOKUP.equals(normalized)) {
            return shape.isReference() ? Compatibility.COMPATIBLE : Compatibility.INCOMPATIBLE;
        }
        if (IMAGE_SELECT.equals(normalized)) {
            if (!shape.isReference() && !isEnumWithValues) {
                return Compatibility.INCOMPATIBLE;
            }
            boolean hasImageSource = shape.isReference() ? shape.hasImageFieldHint() : shape.hasAnyEnumOptionIcon();
            return hasImageSource ? Compatibility.COMPATIBLE : Compatibility.DISCOURAGED;
        }
        if (MULTISELECT.equals(normalized) || CHIPS.equals(normalized)) {
            // Reached only for a scalar/enum/single-reference field -- neither eligible collection
            // shape (many-to-many bond, closed-enum array) applies here.
            return Compatibility.INCOMPATIBLE;
        }
        if (GROUP.equals(normalized) || LIST.equals(normalized)) {
            // group/list only mean something on the object/array field they label structurally.
            return Compatibility.INCOMPATIBLE;
        }

        return Compatibility.UNKNOWN_WIDGET;
    }
}
