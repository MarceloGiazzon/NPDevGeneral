package com.npdev.dsl.v1.compiled;

import org.junit.jupiter.api.Test;

import static com.npdev.dsl.v1.compiled.FieldWidgetDefaults.Compatibility.COMPATIBLE;
import static com.npdev.dsl.v1.compiled.FieldWidgetDefaults.Compatibility.DISCOURAGED;
import static com.npdev.dsl.v1.compiled.FieldWidgetDefaults.Compatibility.INCOMPATIBLE;
import static com.npdev.dsl.v1.compiled.FieldWidgetDefaults.Compatibility.UNKNOWN_WIDGET;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FieldWidgetDefaultsTest {

    private static FieldWidgetDefaults.FieldShape scalar(String dslType) {
        return new FieldWidgetDefaults.FieldShape(dslType, false, false, false, false, false, false, false);
    }

    private static FieldWidgetDefaults.FieldShape enumField(boolean hasValues, boolean hasIcon) {
        return new FieldWidgetDefaults.FieldShape("enum", false, false, hasValues, false, hasIcon, false, false);
    }

    private static FieldWidgetDefaults.FieldShape singleReference(boolean hasImageField) {
        return new FieldWidgetDefaults.FieldShape("reference", true, false, false, false, false, hasImageField, false);
    }

    private static FieldWidgetDefaults.FieldShape multiReference() {
        return new FieldWidgetDefaults.FieldShape("reference", true, true, false, false, false, false, false);
    }

    private static FieldWidgetDefaults.FieldShape array(boolean closedEnum) {
        return new FieldWidgetDefaults.FieldShape("array", false, false, false, closedEnum, false, false, false);
    }

    // -- defaultWidget() --

    @Test
    void defaultsMatchPerDataType() {
        assertEquals("text", FieldWidgetDefaults.defaultWidget("string", false, false, false));
        assertEquals("text", FieldWidgetDefaults.defaultWidget("uuid", false, false, false));
        assertEquals("number", FieldWidgetDefaults.defaultWidget("int", false, false, false));
        assertEquals("number", FieldWidgetDefaults.defaultWidget("integer", false, false, false));
        assertEquals("number", FieldWidgetDefaults.defaultWidget("long", false, false, false));
        assertEquals("date", FieldWidgetDefaults.defaultWidget("date", false, false, false));
        assertEquals("datetime-local", FieldWidgetDefaults.defaultWidget("datetime", false, false, false));
        assertEquals("checkbox", FieldWidgetDefaults.defaultWidget("boolean", false, false, false));
        assertEquals("select", FieldWidgetDefaults.defaultWidget("enum", false, false, true));
        assertEquals("lookup", FieldWidgetDefaults.defaultWidget("reference", true, false, false));
        assertEquals("multiselect", FieldWidgetDefaults.defaultWidget("reference", true, true, false));
    }

    // -- classify(): compatible cases --

    @Test
    void textIsCompatibleWithAnyScalarType() {
        assertEquals(COMPATIBLE, FieldWidgetDefaults.classify(scalar("string"), "text"));
        assertEquals(COMPATIBLE, FieldWidgetDefaults.classify(scalar("int"), "text"));
        assertEquals(COMPATIBLE, FieldWidgetDefaults.classify(scalar("boolean"), "text"));
        assertEquals(COMPATIBLE, FieldWidgetDefaults.classify(scalar("date"), "text"));
    }

    @Test
    void colorIsCompatibleOnlyWithString() {
        assertEquals(COMPATIBLE, FieldWidgetDefaults.classify(scalar("string"), "color"));
        assertEquals(INCOMPATIBLE, FieldWidgetDefaults.classify(scalar("int"), "color"));
        assertEquals(INCOMPATIBLE, FieldWidgetDefaults.classify(scalar("boolean"), "color"));
    }

    @Test
    void numberIsCompatibleOnlyWithIntegerFamily() {
        assertEquals(COMPATIBLE, FieldWidgetDefaults.classify(scalar("int"), "number"));
        assertEquals(COMPATIBLE, FieldWidgetDefaults.classify(scalar("integer"), "number"));
        assertEquals(COMPATIBLE, FieldWidgetDefaults.classify(scalar("long"), "number"));
        assertEquals(INCOMPATIBLE, FieldWidgetDefaults.classify(scalar("string"), "number"));
        assertEquals(INCOMPATIBLE, FieldWidgetDefaults.classify(scalar("uuid"), "number"));
    }

    @Test
    void checkboxIsCompatibleOnlyWithBoolean() {
        assertEquals(COMPATIBLE, FieldWidgetDefaults.classify(scalar("boolean"), "checkbox"));
        assertEquals(INCOMPATIBLE, FieldWidgetDefaults.classify(scalar("string"), "checkbox"));
        assertEquals(INCOMPATIBLE, FieldWidgetDefaults.classify(enumField(true, false), "checkbox"));
    }

    @Test
    void selectAndAutocompleteAcceptEnumWithValuesOrSingleReference() {
        assertEquals(COMPATIBLE, FieldWidgetDefaults.classify(enumField(true, false), "select"));
        assertEquals(COMPATIBLE, FieldWidgetDefaults.classify(singleReference(false), "select"));
        assertEquals(COMPATIBLE, FieldWidgetDefaults.classify(enumField(true, false), "autocomplete"));
        assertEquals(COMPATIBLE, FieldWidgetDefaults.classify(singleReference(false), "autocomplete"));
        assertEquals(INCOMPATIBLE, FieldWidgetDefaults.classify(scalar("string"), "select"));
        // A many-to-many field is force-overridden to multiselect regardless of what's declared --
        // covered separately (and precisely) by anyWidgetOtherThanMultiselectOnManyToManyIsDiscouraged.
    }

    @Test
    void lookupAcceptsOnlySingleReference() {
        assertEquals(COMPATIBLE, FieldWidgetDefaults.classify(singleReference(false), "lookup"));
        assertEquals(COMPATIBLE, FieldWidgetDefaults.classify(singleReference(false), "search-dialog"));
        assertEquals(INCOMPATIBLE, FieldWidgetDefaults.classify(enumField(true, false), "lookup"));
    }

    @Test
    void multiselectAcceptsManyToManyReferenceOrClosedEnumArray() {
        assertEquals(COMPATIBLE, FieldWidgetDefaults.classify(multiReference(), "multiselect"));
        assertEquals(COMPATIBLE, FieldWidgetDefaults.classify(array(true), "multiselect"));
        assertEquals(INCOMPATIBLE, FieldWidgetDefaults.classify(array(false), "multiselect"));
        assertEquals(INCOMPATIBLE, FieldWidgetDefaults.classify(scalar("string"), "multiselect"));
    }

    @Test
    void imageSelectDegradesToDiscouragedWithoutAnImageSource() {
        assertEquals(COMPATIBLE, FieldWidgetDefaults.classify(enumField(true, true), "image-select"));
        assertEquals(DISCOURAGED, FieldWidgetDefaults.classify(enumField(true, false), "image-select"));
        assertEquals(COMPATIBLE, FieldWidgetDefaults.classify(singleReference(true), "image-select"));
        assertEquals(DISCOURAGED, FieldWidgetDefaults.classify(singleReference(false), "image-select"));
        assertEquals(INCOMPATIBLE, FieldWidgetDefaults.classify(scalar("string"), "image-select"));
    }

    @Test
    void customRequiresACustomWidgetRef() {
        FieldWidgetDefaults.FieldShape withRef = new FieldWidgetDefaults.FieldShape("string", false, false, false, false, false, false, true);
        FieldWidgetDefaults.FieldShape withoutRef = scalar("string");
        assertEquals(COMPATIBLE, FieldWidgetDefaults.classify(withRef, "custom"));
        assertEquals(INCOMPATIBLE, FieldWidgetDefaults.classify(withoutRef, "custom"));
    }

    @Test
    void groupAndListAreCompatibleOnlyWithTheirOwnStructuralType() {
        FieldWidgetDefaults.FieldShape object = new FieldWidgetDefaults.FieldShape("object", false, false, false, false, false, false, false);
        assertEquals(COMPATIBLE, FieldWidgetDefaults.classify(object, "group"));
        assertEquals(COMPATIBLE, FieldWidgetDefaults.classify(array(false), "list"));
        assertEquals(INCOMPATIBLE, FieldWidgetDefaults.classify(scalar("string"), "group"));
        assertEquals(INCOMPATIBLE, FieldWidgetDefaults.classify(scalar("string"), "list"));
    }

    // -- classify(): discouraged (renders, but mismatched or silently ignored) --

    @Test
    void textLikeWidgetsAreDiscouragedOnNumericOrUuidFields() {
        assertEquals(DISCOURAGED, FieldWidgetDefaults.classify(scalar("int"), "email"));
        assertEquals(DISCOURAGED, FieldWidgetDefaults.classify(scalar("uuid"), "textarea"));
        assertEquals(INCOMPATIBLE, FieldWidgetDefaults.classify(scalar("boolean"), "email"));
    }

    @Test
    void anyWidgetOnObjectOrPlainArrayIsDiscouragedAsDeadDeclaration() {
        FieldWidgetDefaults.FieldShape object = new FieldWidgetDefaults.FieldShape("object", false, false, false, false, false, false, false);
        assertEquals(DISCOURAGED, FieldWidgetDefaults.classify(object, "text"));
        assertEquals(DISCOURAGED, FieldWidgetDefaults.classify(array(false), "text"));
    }

    @Test
    void anyWidgetOtherThanMultiselectOnManyToManyIsDiscouraged() {
        assertEquals(DISCOURAGED, FieldWidgetDefaults.classify(multiReference(), "lookup"));
        assertEquals(DISCOURAGED, FieldWidgetDefaults.classify(multiReference(), "select"));
    }

    // -- classify(): unknown widget name --

    @Test
    void unrecognizedWidgetNameIsAlwaysUnknown() {
        assertEquals(UNKNOWN_WIDGET, FieldWidgetDefaults.classify(scalar("string"), "not-a-real-widget"));
    }
}
