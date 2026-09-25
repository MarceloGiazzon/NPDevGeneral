package com.npdev.dsl.v1.compiled;

import java.util.List;
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
    public static final String SLIDER = "slider";
    public static final String TOGGLE = "toggle";
    public static final String RATING = "rating";
    public static final String CURRENCY = "currency";
    public static final String PASSWORD = "password";
    public static final String FILE = "file";
    public static final String UUID = "uuid";
    public static final String RICHTEXT = "richtext";

    public static final Set<String> SUPPORTED_WIDGETS = Set.of(
            TEXT, TEXTAREA, NUMBER, EMAIL, TEL, URL, COLOR, DATE, DATETIME_LOCAL, CHECKBOX,
            SELECT, AUTOCOMPLETE, LOOKUP, SEARCH_DIALOG, MULTISELECT, IMAGE_SELECT, CUSTOM, GROUP, LIST,
            SLIDER, TOGGLE, RATING, CURRENCY, PASSWORD, FILE, UUID, RICHTEXT
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
            boolean hasCustomWidgetRef
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
            case "uuid" -> UUID;
            case "file" -> FILE;
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
            return MULTISELECT.equals(normalized) ? Compatibility.COMPATIBLE : Compatibility.DISCOURAGED;
        }

        String type = shape.dslType() == null ? "" : shape.dslType().trim().toLowerCase(Locale.ROOT);

        if ("array".equals(type)) {
            if (MULTISELECT.equals(normalized)) {
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

        boolean isEnumWithValues = "enum".equals(type) && shape.hasEnumValues();

        if (TEXT.equals(normalized)) {
            return Compatibility.COMPATIBLE;
        }
        if (TEXTAREA.equals(normalized) || EMAIL.equals(normalized) || TEL.equals(normalized) || URL.equals(normalized)) {
            if ("string".equals(type)) {
                return Compatibility.COMPATIBLE;
            }
            if (NUMERIC_TYPES.contains(type) || "uuid".equals(type)) {
                return Compatibility.DISCOURAGED;
            }
            return Compatibility.INCOMPATIBLE;
        }
        if (COLOR.equals(normalized)) {
            return "string".equals(type) ? Compatibility.COMPATIBLE : Compatibility.INCOMPATIBLE;
        }
        if (NUMBER.equals(normalized)) {
            return NUMERIC_TYPES.contains(type) ? Compatibility.COMPATIBLE : Compatibility.INCOMPATIBLE;
        }
        if (SLIDER.equals(normalized)) {
            return NUMERIC_TYPES.contains(type) ? Compatibility.COMPATIBLE : Compatibility.INCOMPATIBLE;
        }
        if (RATING.equals(normalized)) {
            return ("int".equals(type) || "integer".equals(type)) ? Compatibility.COMPATIBLE : Compatibility.INCOMPATIBLE;
        }
        if (CURRENCY.equals(normalized)) {
            return "decimal".equals(type) ? Compatibility.COMPATIBLE : Compatibility.INCOMPATIBLE;
        }
        if (PASSWORD.equals(normalized) || RICHTEXT.equals(normalized)) {
            return "string".equals(type) ? Compatibility.COMPATIBLE : Compatibility.INCOMPATIBLE;
        }
        if (TOGGLE.equals(normalized)) {
            return "boolean".equals(type) ? Compatibility.COMPATIBLE : Compatibility.INCOMPATIBLE;
        }
        if (FILE.equals(normalized)) {
            return "file".equals(type) ? Compatibility.COMPATIBLE : Compatibility.INCOMPATIBLE;
        }
        if (UUID.equals(normalized)) {
            return "uuid".equals(type) ? Compatibility.COMPATIBLE : Compatibility.INCOMPATIBLE;
        }
        if (DATE.equals(normalized)) {
            return "date".equals(type) ? Compatibility.COMPATIBLE : Compatibility.INCOMPATIBLE;
        }
        if (DATETIME_LOCAL.equals(normalized)) {
            return "datetime".equals(type) ? Compatibility.COMPATIBLE : Compatibility.INCOMPATIBLE;
        }
        if (CHECKBOX.equals(normalized)) {
            return "boolean".equals(type) ? Compatibility.COMPATIBLE : Compatibility.INCOMPATIBLE;
        }
        if (SELECT.equals(normalized)) {
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
        if (MULTISELECT.equals(normalized)) {
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

    /**
     * One catalogue entry per {@link #SUPPORTED_WIDGETS} value: the field types it accepts (as
     * COMPATIBLE per {@link #classify}), the DSL types it is the default for (per
     * {@link #defaultWidget}), and a one-line human description. This is the single source both
     * the generator's {@code WidgetCatalogueEmitter} (a live JSON+HTML page per generated app) and
     * the {@code npdev widgets} CLI command read -- neither hand-maintains its own widget list.
     */
    public record WidgetCatalogueEntry(
            String name,
            List<String> compatibleTypes,
            List<String> isDefaultFor,
            String description
    ) {
    }

    /** @see WidgetCatalogueEntry */
    public static List<WidgetCatalogueEntry> catalogue() {
        return List.of(
                new WidgetCatalogueEntry(TEXT,
                        List.of("string", "int", "integer", "long", "decimal", "boolean", "date", "datetime", "uuid"),
                        List.of("string"),
                        "Plain single-line text input; compatible with virtually any scalar field."),
                new WidgetCatalogueEntry(TEXTAREA,
                        List.of("string"), List.of(),
                        "Multi-line plain text input for longer strings."),
                new WidgetCatalogueEntry(NUMBER,
                        List.of("int", "integer", "long", "decimal"), List.of("int", "integer", "long", "decimal"),
                        "Native number input."),
                new WidgetCatalogueEntry(EMAIL,
                        List.of("string"), List.of(),
                        "Native email input with browser-native validation."),
                new WidgetCatalogueEntry(TEL,
                        List.of("string"), List.of(),
                        "Native telephone-number input."),
                new WidgetCatalogueEntry(URL,
                        List.of("string"), List.of(),
                        "Native URL input with browser-native validation."),
                new WidgetCatalogueEntry(COLOR,
                        List.of("string"), List.of(),
                        "Native color picker; stores a hex string."),
                new WidgetCatalogueEntry(DATE,
                        List.of("date"), List.of("date"),
                        "Native date picker."),
                new WidgetCatalogueEntry(DATETIME_LOCAL,
                        List.of("datetime"), List.of("datetime"),
                        "Native date+time picker."),
                new WidgetCatalogueEntry(CHECKBOX,
                        List.of("boolean"), List.of("boolean"),
                        "Standard checkbox."),
                new WidgetCatalogueEntry(SELECT,
                        List.of("enum", "reference"), List.of("enum (with declared values)"),
                        "Native dropdown for an enum with declared values, or a single reference."),
                new WidgetCatalogueEntry(AUTOCOMPLETE,
                        List.of("enum", "reference"), List.of(),
                        "Type-ahead dropdown for an enum or a reference field."),
                new WidgetCatalogueEntry(LOOKUP,
                        List.of("reference"), List.of("reference"),
                        "Search-dialog picker for a single reference field."),
                new WidgetCatalogueEntry(SEARCH_DIALOG,
                        List.of("reference"), List.of(),
                        "Legacy alias for lookup, kept for samples authored before lookup existed."),
                new WidgetCatalogueEntry(MULTISELECT,
                        List.of("reference (multi)", "array (closed enum)"), List.of("reference (multi)"),
                        "Multi-select control for a many-to-many reference or a closed-enum array."),
                new WidgetCatalogueEntry(IMAGE_SELECT,
                        List.of("enum", "reference"), List.of(),
                        "Visual picker showing an image per option or row."),
                new WidgetCatalogueEntry(CUSTOM,
                        List.of("any (requires ui.customWidgetRef)"), List.of(),
                        "Delegates rendering to an author-registered custom widget."),
                new WidgetCatalogueEntry(GROUP,
                        List.of("object"), List.of(),
                        "Structural label for a nested object field's own editor."),
                new WidgetCatalogueEntry(LIST,
                        List.of("array"), List.of(),
                        "Structural label for a nested array field's own editor."),
                new WidgetCatalogueEntry(SLIDER,
                        List.of("int", "integer", "long", "decimal"), List.of(),
                        "Range slider; uses the field's declared min/max, else defaults to 0-100."),
                new WidgetCatalogueEntry(TOGGLE,
                        List.of("boolean"), List.of(),
                        "Styled on/off switch; same value path as a checkbox."),
                new WidgetCatalogueEntry(RATING,
                        List.of("int", "integer"), List.of(),
                        "1-5 star rating (star count from the field's max if declared and <= 10)."),
                new WidgetCatalogueEntry(CURRENCY,
                        List.of("decimal"), List.of(),
                        "Number input formatted to 2 decimals with a currency symbol from ui.currency."),
                new WidgetCatalogueEntry(PASSWORD,
                        List.of("string"), List.of(),
                        "Masked text input with a show/hide toggle."),
                new WidgetCatalogueEntry(FILE,
                        List.of("file"), List.of("file"),
                        "Upload control backed by the file-store adapter."),
                new WidgetCatalogueEntry(UUID,
                        List.of("uuid"), List.of("uuid"),
                        "Read-only monospace display with a copy-to-clipboard button."),
                new WidgetCatalogueEntry(RICHTEXT,
                        List.of("string"), List.of(),
                        "Minimal contenteditable editor (bold/italic/list); stores sanitized HTML.")
        );
    }
}
