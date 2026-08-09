"""Feature detectors that read the UI surface: panels, autoPanels, workbench regions and actions.

Split from `detectors_model` because the two never call each other -- verified, not assumed, by
scanning each body for the other's names before the split was written. Bodies moved byte-for-byte.
"""
from __future__ import annotations



def _has_panel_action_concept_query(model: dict) -> bool:
    for panel in (model.get("panels", None) or []):
        if not isinstance(panel, dict):
            continue
        for action in (panel.get("actions", None) or []):
            if isinstance(action, dict) and str(action.get("binding", "")).lower() == "conceptquery":
                return True
    return False


def _has_field_picker_filter(model: dict) -> bool:
    """B16/B19 (Move 9 A3): a field's picker.filter -- the reference field's own single-clause
    predicate constraining its auto-picker's candidate rows."""
    for concept in (model.get("concepts", None) or []):
        if not isinstance(concept, dict):
            continue
        for field in (concept.get("fields", None) or []):
            picker = field.get("picker") if isinstance(field, dict) else None
            if isinstance(picker, dict) and picker.get("filter"):
                return True
    return False


def _has_band_picker_filter(model: dict) -> bool:
    """B16/B19 (Move 9 A3): a bandPickers entry's filter/multiSelect -- the SAME two properties a
    plain FK field's picker declares, reused on a band collection's own picker."""
    for panel in (model.get("autoPanels", None) or []):
        if not isinstance(panel, dict):
            continue
        transaction = panel.get("transaction")
        if not isinstance(transaction, dict):
            continue
        band_pickers = transaction.get("bandPickers")
        if not isinstance(band_pickers, dict):
            continue
        for picker in band_pickers.values():
            if isinstance(picker, dict) and (picker.get("filter") or picker.get("multiSelect")):
                return True
    return False


def _has_panel_action_download(model: dict) -> bool:
    for panel in (model.get("panels", None) or []):
        if not isinstance(panel, dict):
            continue
        for action in (panel.get("actions", None) or []):
            if isinstance(action, dict) and action.get("resultAs") == "download":
                return True
    return False


def _workbench_transactions(model: dict):
    for auto_panel in (model.get("autoPanels", None) or []):
        if not isinstance(auto_panel, dict):
            continue
        transaction = auto_panel.get("transaction")
        if isinstance(transaction, dict):
            yield transaction


def _workbench_transaction_metadatas(model: dict):
    for transaction in _workbench_transactions(model):
        metadata = transaction.get("metadata")
        if isinstance(metadata, dict):
            yield metadata


def _workbench_actions(model: dict):
    """Yields every action dict from BOTH the untyped transaction.metadata.actions[] list and its
    Move 7 W1 (docs/MOVE7_IMPLEMENTATION_SPEC.md) typed replacement, transaction.actions[] -- same
    underlying capability, re-spelled; a model migrated to the typed form must not silently drop
    coverage of any sub-feature (applyTo/afterAction/visibleWhen) detected off of it."""
    for metadata in _workbench_transaction_metadatas(model):
        for action in (metadata.get("actions", None) or []):
            if isinstance(action, dict):
                yield action
    for transaction in _workbench_transactions(model):
        for action in (transaction.get("actions", None) or []):
            if isinstance(action, dict):
                yield action


def _has_workbench_apply_to(model: dict) -> bool:
    for action in _workbench_actions(model):
        if isinstance(action.get("applyTo"), dict):
            return True
    return False


def _has_workbench_after_action(model: dict) -> bool:
    # Move 6 Move B (docs/MOVE6_TYPED_SURFACE_PLAN.md §B.4): per-action afterAction, declared
    # alongside (not inside) applyTo -- recognizes both the untyped transaction.metadata.actions[]
    # bag and its Move 7 W1 typed replacement, transaction.actions[].
    for action in _workbench_actions(model):
        if action.get("afterAction"):
            return True
    return False


def _has_transaction_hook(model: dict, position: str) -> bool:
    # Move 6 Move B (docs/MOVE6_TYPED_SURFACE_PLAN.md §B.2): the typed, closed-enum
    # transaction.hooks block -- onLoad/onFieldChange/beforeAction/onValidate/onCommit.
    for transaction in _workbench_transactions(model):
        hooks = transaction.get("hooks")
        if isinstance(hooks, dict) and hooks.get(position):
            return True
    return False


def _has_panel_data_source_on_row_load(model: dict) -> bool:
    # Move 6 Move C (docs/MOVE6_TYPED_SURFACE_PLAN.md §4): a panel dataSource's onRowLoad --
    # enriches rows the gateway produced, distinct from `procedure` (which replaces the row
    # source entirely).
    for panel in (model.get("panels", None) or []):
        if not isinstance(panel, dict):
            continue
        for data_source in (panel.get("dataSources", None) or []):
            if isinstance(data_source, dict) and data_source.get("onRowLoad"):
                return True
    return False


def _has_autopanel_selection_data_source_procedure(model: dict) -> bool:
    # Move 8 D3 (item G6, docs/MOVE8_CLOSE_TABLE_SPEC.md / Move 6 §B.7): an AutoPanel's Selection
    # surface declaring dataSource.procedure -- REPLACES the generated row source with a
    # procedure's output instead of the bound concept's table, distinct from
    # panelDataSource.onRowLoad above (which enriches rows a hand-authored panel's gateway already
    # produced, on a Panel, not an AutoPanel).
    for auto_panel in (model.get("autoPanels", None) or []):
        if not isinstance(auto_panel, dict):
            continue
        selection = auto_panel.get("selection")
        if not isinstance(selection, dict):
            continue
        data_source = selection.get("dataSource")
        if isinstance(data_source, dict) and data_source.get("procedure"):
            return True
    return False


def _has_region_component_mount(model: dict) -> bool:
    # Move 6 Move D (docs/MOVE6_TYPED_SURFACE_PLAN.md §5): a transaction.regions entry declaring
    # render:"component" -- an addressable region mounting an app-owned JS component.
    for transaction in _workbench_transactions(model):
        regions = transaction.get("regions")
        if not isinstance(regions, dict):
            continue
        for region in regions.values():
            if isinstance(region, dict) and region.get("render") == "component":
                return True
    return False


def _has_workbench_derived(model: dict) -> bool:
    # Recognizes BOTH the retired transaction.metadata.derived list and its Move 6 Move B typed
    # replacement, transaction.derivedFields (docs/MOVE6_TYPED_SURFACE_PLAN.md §B.4) -- the same
    # underlying capability, re-spelled; a model migrated to the new spelling must not silently
    # drop this feature's only corpus witness.
    for metadata in _workbench_transaction_metadatas(model):
        if metadata.get("derived", None):
            return True
    for transaction in _workbench_transactions(model):
        if transaction.get("derivedFields", None):
            return True
    return False


def _has_workbench_visible_when(model: dict) -> bool:
    # Recognizes the untyped transaction.metadata.visibleWhen map AND its Move 7 W1 typed
    # replacement, transaction.visibleWhen (same shape, now schema-validated) -- plus either
    # form's per-action visibleWhen key.
    for metadata in _workbench_transaction_metadatas(model):
        if isinstance(metadata.get("visibleWhen"), dict) and metadata["visibleWhen"]:
            return True
    for transaction in _workbench_transactions(model):
        if isinstance(transaction.get("visibleWhen"), dict) and transaction["visibleWhen"]:
            return True
    for action in _workbench_actions(model):
        if action.get("visibleWhen"):
            return True
    return False


def _has_workbench_ui_state(model: dict) -> bool:
    # Move 11 W6 (C1, docs/MOVE3_G2_CHECKLISTS.md): transaction.uiState declares transient screen
    # state -- a record-type toggle -- that a `$ui.<name>` visibleWhen predicate resolves. Requires
    # BOTH halves to count as covered: a declared control nothing references proves the schema
    # accepts it and nothing more, and a `$ui.` predicate over an undeclared control does not
    # validate at all. Only the pair exercises the feature.
    declared = False
    referenced = False
    for transaction in _workbench_transactions(model):
        if isinstance(transaction.get("uiState"), dict) and transaction["uiState"]:
            declared = True
        for expression in (transaction.get("visibleWhen") or {}).values():
            if isinstance(expression, str) and expression.strip().lstrip("$").startswith("ui."):
                referenced = True
    for action in _workbench_actions(model):
        expression = action.get("visibleWhen")
        if isinstance(expression, str) and expression.strip().lstrip("$").startswith("ui."):
            referenced = True
    return declared and referenced


def _has_workbench_band_pickers(model: dict) -> bool:
    # Move 7 W1 (docs/MOVE7_IMPLEMENTATION_SPEC.md): recognizes the untyped
    # transaction.metadata.bandPickers map AND its typed replacement, transaction.bandPickers.
    for metadata in _workbench_transaction_metadatas(model):
        if isinstance(metadata.get("bandPickers"), dict) and metadata["bandPickers"]:
            return True
    for transaction in _workbench_transactions(model):
        if isinstance(transaction.get("bandPickers"), dict) and transaction["bandPickers"]:
            return True
    return False


def _has_typed_workbench_actions(model: dict) -> bool:
    # Move 7 W1: specifically the TYPED transaction.actions[] slot (not its untyped predecessor) --
    # tracked separately so a regression to JUST the typed spelling still fails the build, same
    # discipline autoPanel.hooks.* already applies per-position.
    for transaction in _workbench_transactions(model):
        if transaction.get("actions", None):
            return True
    return False
