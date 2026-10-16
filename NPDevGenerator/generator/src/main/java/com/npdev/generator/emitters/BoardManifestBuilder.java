package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledLifecycle;
import com.npdev.dsl.v1.compiled.CompiledPresentationMetadata;
import com.npdev.dsl.v1.compiled.CompiledStateMachineState;
import com.npdev.dsl.v1.compiled.CompiledStateTransition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * UI-3: the manifest's per-concept {@code board} block -- the data views/board.js needs to render a
 * kanban grouped by one enum/lifecycle field. ZERO new DSL: the group field is the concept's existing
 * {@code ui.defaultGroup} (already parsed, compiled, round-tripped and validated to name a field of the
 * same concept by UxMetadataValidation, but read by nothing until now) or, when that is unset, the
 * concept's {@code lifecycle.statusField}. Returns null when the concept does not qualify, in which
 * case BusinessUiEmitter emits neither {@code board} nor the "board" entry in {@code views}.
 *
 * <p>Transitions are emitted only when the group field IS the lifecycle status field; the client
 * uses them to grey out illegal drop targets, but the server stays the authority
 * (GeneratedCrudRuntimeSupport.validateLifecycleTransitions runs on every update, including the
 * batch setField path board.js uses).
 */
final class BoardManifestBuilder {

    private BoardManifestBuilder() {
    }

    static Map<String, Object> build(CompiledConcept concept) {
        if (concept == null) {
            return null;
        }
        CompiledLifecycle lifecycle = concept.getLifecycle();
        String statusField = lifecycle == null || isBlank(lifecycle.getStatusField())
                ? null : lifecycle.getStatusField().trim();
        CompiledPresentationMetadata ui = concept.getUi();
        String declaredGroup = ui == null || isBlank(ui.getDefaultGroup()) ? null : ui.getDefaultGroup().trim();
        String groupBy = declaredGroup != null ? declaredGroup : statusField;
        if (groupBy == null) {
            return null;
        }
        CompiledField field = findField(concept, groupBy);
        if (field == null || !"enum".equals(field.getDslType())) {
            // A string/number defaultGroup is legal metadata but has no closed set of columns.
            return null;
        }
        boolean isLifecycleField = groupBy.equals(statusField);

        List<Map<String, Object>> columns = new ArrayList<>();
        if (isLifecycleField && !lifecycle.getStates().isEmpty()) {
            for (CompiledStateMachineState state : lifecycle.getStates()) {
                Map<String, Object> column = new LinkedHashMap<>();
                column.put("value", state.getValue());
                column.put("label", isBlank(state.getLabel()) ? state.getValue() : state.getLabel());
                column.put("initial", state.isInitial());
                column.put("terminal", state.isTerminal());
                columns.add(column);
            }
        } else {
            for (String value : field.getEnumValues()) {
                Map<String, Object> column = new LinkedHashMap<>();
                column.put("value", value);
                column.put("label", value); // board.js prefers the field's enumOptions label when present
                column.put("initial", false);
                column.put("terminal", false);
                columns.add(column);
            }
        }

        List<Map<String, Object>> transitions = new ArrayList<>();
        if (isLifecycleField) {
            for (CompiledStateTransition transition : lifecycle.getTransitions()) {
                if (transition == null) {
                    continue;
                }
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("from", transition.getFrom());
                node.put("to", transition.getTo());
                node.put("label", isBlank(transition.getActionLabel()) ? transition.getTo() : transition.getActionLabel());
                node.put("requires", transition.getRequiredPayload() == null ? List.of() : transition.getRequiredPayload());
                node.put("guarded", !isBlank(transition.getGuard()));
                transitions.add(node);
            }
        }

        Map<String, Object> board = new LinkedHashMap<>();
        board.put("groupBy", groupBy);
        board.put("lifecycle", isLifecycleField);
        // true = only declared transitions are legal moves; false = any column is a legal target.
        board.put("restricted", isLifecycleField && !transitions.isEmpty());
        board.put("nullable", !field.isRequired());
        board.put("columns", columns);
        board.put("transitions", transitions);
        return board;
    }

    private static CompiledField findField(CompiledConcept concept, String name) {
        for (CompiledField candidate : concept.getFields()) {
            if (candidate != null && name.equals(candidate.getName())) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

// --- BusinessUiEmitter.manifestJson concept loop (after UI-0's `views` list is created) ---------
//        Map<String, Object> board = BoardManifestBuilder.build(concept);
//        if (board != null) {
//            node.put("board", board);
//            views.add("board");
//        }
