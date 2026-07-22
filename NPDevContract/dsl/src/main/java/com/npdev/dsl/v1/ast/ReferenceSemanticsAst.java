package com.npdev.dsl.v1.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ReferenceSemanticsAst {
    private final String target;
    private final boolean multiple;
    private final String displayField;
    private final List<String> searchFields;
    private final List<String> previewFields;
    private final String inlineCreatePolicy;
    private final String displayTemplate;
    private final List<String> pickerColumns;
    private final String previewCardTemplate;
    private final String defaultFilter;
    private final String via;
    private final String onDelete;

    public ReferenceSemanticsAst(
            String target,
            boolean multiple,
            String displayField,
            List<String> searchFields,
            List<String> previewFields,
            String inlineCreatePolicy,
            String displayTemplate,
            List<String> pickerColumns,
            String previewCardTemplate,
            String defaultFilter
    ) {
        this(target, multiple, displayField, searchFields, previewFields, inlineCreatePolicy,
                displayTemplate, pickerColumns, previewCardTemplate, defaultFilter, null, null);
    }

    public ReferenceSemanticsAst(
            String target,
            boolean multiple,
            String displayField,
            List<String> searchFields,
            List<String> previewFields,
            String inlineCreatePolicy,
            String displayTemplate,
            List<String> pickerColumns,
            String previewCardTemplate,
            String defaultFilter,
            String via,
            String onDelete
    ) {
        this.target = target;
        this.multiple = multiple;
        this.displayField = displayField;
        this.searchFields = searchFields == null ? List.of() : new ArrayList<>(searchFields);
        this.previewFields = previewFields == null ? List.of() : new ArrayList<>(previewFields);
        this.inlineCreatePolicy = inlineCreatePolicy;
        this.displayTemplate = displayTemplate;
        this.pickerColumns = pickerColumns == null ? List.of() : new ArrayList<>(pickerColumns);
        this.previewCardTemplate = previewCardTemplate;
        this.defaultFilter = defaultFilter;
        this.via = via;
        this.onDelete = onDelete;
    }

    public String getTarget() {
        return target;
    }

    public boolean isMultiple() {
        return multiple;
    }

    public String getDisplayField() {
        return displayField;
    }

    public List<String> getSearchFields() {
        return Collections.unmodifiableList(searchFields);
    }

    public List<String> getPreviewFields() {
        return Collections.unmodifiableList(previewFields);
    }

    public String getInlineCreatePolicy() {
        return inlineCreatePolicy;
    }

    public String getDisplayTemplate() {
        return displayTemplate;
    }

    public List<String> getPickerColumns() {
        return Collections.unmodifiableList(pickerColumns);
    }

    public String getPreviewCardTemplate() {
        return previewCardTemplate;
    }

    public String getDefaultFilter() {
        return defaultFilter;
    }

    /** Anchor field on the target this port binds to ({@code via}); null means the target's id. */
    public String getVia() {
        return via;
    }

    /** Referential integrity behaviour when the target is deleted: restrict|cascade|nullify; null means restrict. */
    public String getOnDelete() {
        return onDelete;
    }
}
