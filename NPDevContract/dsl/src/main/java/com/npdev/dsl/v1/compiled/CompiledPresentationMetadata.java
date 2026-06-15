package com.npdev.dsl.v1.compiled;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CompiledPresentationMetadata {
    private final String label;
    private final String shortLabel;
    private final String description;
    private final String helpText;
    private final String placeholder;
    private final String group;
    private final String section;
    private final Integer order;
    private final Boolean advanced;
    private final Boolean deprecated;
    private final List<String> examples;
    private final String widget;
    private final String visibleWhen;
    private final String enabledWhen;
    private final String readonlyWhen;
    private final String requiredWhen;
    private final String pickerType;
    private final Boolean allowInlineCreate;
    private final List<String> searchFields;
    private final String filterPreset;
    private final String tab;
    private final Integer column;
    private final Integer columnSpan;
    private final String width;
    private final Boolean summaryCard;
    private final Boolean listColumn;
    private final Boolean showInDefaultWebUi;
    private final Integer listColumnOrder;
    private final Integer formColumns;
    private final String displayMode;
    private final String defaultSort;
    private final String defaultGroup;

    public CompiledPresentationMetadata(
            String label,
            String shortLabel,
            String description,
            String helpText,
            String placeholder,
            String group,
            String section,
            Integer order,
            Boolean advanced,
            Boolean deprecated,
            List<String> examples,
            String widget,
            String visibleWhen,
            String enabledWhen,
            String readonlyWhen,
            String requiredWhen,
            String pickerType,
            Boolean allowInlineCreate,
            List<String> searchFields,
            String filterPreset,
            String tab,
            Integer column,
            Integer columnSpan,
            String width,
            Boolean summaryCard,
            Boolean listColumn,
            Boolean showInDefaultWebUi,
            Integer listColumnOrder,
            Integer formColumns,
            String displayMode,
            String defaultSort,
            String defaultGroup
    ) {
        this.label = label;
        this.shortLabel = shortLabel;
        this.description = description;
        this.helpText = helpText;
        this.placeholder = placeholder;
        this.group = group;
        this.section = section;
        this.order = order;
        this.advanced = advanced;
        this.deprecated = deprecated;
        this.examples = examples == null ? List.of() : new ArrayList<>(examples);
        this.widget = widget;
        this.visibleWhen = visibleWhen;
        this.enabledWhen = enabledWhen;
        this.readonlyWhen = readonlyWhen;
        this.requiredWhen = requiredWhen;
        this.pickerType = pickerType;
        this.allowInlineCreate = allowInlineCreate;
        this.searchFields = searchFields == null ? List.of() : new ArrayList<>(searchFields);
        this.filterPreset = filterPreset;
        this.tab = tab;
        this.column = column;
        this.columnSpan = columnSpan;
        this.width = width;
        this.summaryCard = summaryCard;
        this.listColumn = listColumn;
        this.showInDefaultWebUi = showInDefaultWebUi;
        this.listColumnOrder = listColumnOrder;
        this.formColumns = formColumns;
        this.displayMode = displayMode;
        this.defaultSort = defaultSort;
        this.defaultGroup = defaultGroup;
    }

    public String getLabel() {
        return label;
    }

    public String getShortLabel() {
        return shortLabel;
    }

    public String getDescription() {
        return description;
    }

    public String getHelpText() {
        return helpText;
    }

    public String getPlaceholder() {
        return placeholder;
    }

    public String getGroup() {
        return group;
    }

    public String getSection() {
        return section;
    }

    public Integer getOrder() {
        return order;
    }

    public Boolean getAdvanced() {
        return advanced;
    }

    public Boolean getDeprecated() {
        return deprecated;
    }

    public List<String> getExamples() {
        return Collections.unmodifiableList(examples);
    }

    public String getWidget() {
        return widget;
    }

    public String getVisibleWhen() {
        return visibleWhen;
    }

    public String getEnabledWhen() {
        return enabledWhen;
    }

    public String getReadonlyWhen() {
        return readonlyWhen;
    }

    public String getRequiredWhen() {
        return requiredWhen;
    }

    public String getPickerType() {
        return pickerType;
    }

    public Boolean getAllowInlineCreate() {
        return allowInlineCreate;
    }

    public List<String> getSearchFields() {
        return Collections.unmodifiableList(searchFields);
    }

    public String getFilterPreset() {
        return filterPreset;
    }

    public String getTab() {
        return tab;
    }

    public Integer getColumn() {
        return column;
    }

    public Integer getColumnSpan() {
        return columnSpan;
    }

    public String getWidth() {
        return width;
    }

    public Boolean getSummaryCard() {
        return summaryCard;
    }

    public Boolean getListColumn() {
        return listColumn;
    }

    public Boolean getShowInDefaultWebUi() {
        return showInDefaultWebUi;
    }

    public Integer getListColumnOrder() {
        return listColumnOrder;
    }

    public Integer getFormColumns() {
        return formColumns;
    }

    public String getDisplayMode() {
        return displayMode;
    }

    public String getDefaultSort() {
        return defaultSort;
    }

    public String getDefaultGroup() {
        return defaultGroup;
    }
}
