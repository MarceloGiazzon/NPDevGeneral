package com.npdev.dsl.v1.validation;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.xref.ReferenceEdge;
import com.npdev.dsl.v1.xref.ReferenceIndex;
import com.npdev.dsl.v1.xref.Resolution;

import java.util.List;
import java.util.Set;

/**
 * REG-185: a reference to something that does not exist is an ERROR, not silence.
 *
 * <h2>The measurement this closes</h2>
 * Ghost names were injected into {@code NPDevSamples/dsl-conformance-max} at six reference sites on
 * 2026-08-17. {@code npdev validate model} reported {@code status: passed, errors: 0, warnings: 0}
 * for every one of them -- including a {@code query.orderBy} entry, which reaches generated SQL and
 * is therefore a guaranteed runtime failure the validator called clean.
 *
 * <h2>Why this can be a hard ERROR on its first release</h2>
 * Because the corpus was measured before the rule was written, not after. Running
 * {@link ReferenceIndex} over all 48 corpus models produced exactly ONE unresolved edge --
 * {@code superuser-admin-console}'s {@code ProjectsOverviewPanel.layout.fields = ["projects"]},
 * where {@code projects} is the DATA SOURCE name and not a field of {@code Project}. That sample
 * renders a table column bound to nothing, and has since it shipped. One corpus fix, landed in the
 * same commit, is the entire migration cost -- so this needs no warning phase and no ratchet, both
 * of which are just deferrals nobody closes.
 *
 * <p>Getting to that number took five rounds. The first sweep reported 173 unresolved edges and 172
 * were wrong, each one a real NPDev rule the index did not yet know (a dotted
 * {@code fieldBindings[].source}, a data source taking its concept from its query, a panel
 * {@code visibility} that is a role expression, {@code generated.action.X}, {@code groupBy} join
 * paths). They are pinned as regression tests in {@code ReferenceIndexTest}, because the failure
 * mode of a noisy checker is not "some noise" -- it is being switched off, after which the real
 * orphan it was built for is invisible again.
 */
final class ReferenceIntegrityValidation {

    private ReferenceIntegrityValidation() {
    }

    /**
     * Sites whose orphans are ALREADY reported by another validator in this package. Reporting them
     * here too would give one mistake two error messages under two different wordings, which reads
     * to an author as two mistakes.
     *
     * <p>Each entry is a claim that can be checked by deleting it and watching the duplicate appear;
     * none of them is "we are not sure about this one". Anything genuinely uncertain is
     * {@link Resolution#UNDECIDABLE} at the index level and never reaches this class.
     */
    private static final Set<String> REPORTED_ELSEWHERE = Set.of(
            // ConceptValidation.validateConceptsAndFields -> reference target/displayField checks.
            ReferenceIndex.SITE_CONCEPT_REFERENCE_TARGET,
            ReferenceIndex.SITE_CONCEPT_REFERENCE_DISPLAY_FIELD,
            ReferenceIndex.SITE_CONCEPT_REFERENCE_SEARCH_FIELDS,
            ReferenceIndex.SITE_CONCEPT_REFERENCE_PREVIEW_FIELDS,
            ReferenceIndex.SITE_CONCEPT_REFERENCE_PICKER_COLUMNS,
            ReferenceIndex.SITE_CONCEPT_DOMAIN_TYPE,
            ReferenceIndex.SITE_CONCEPT_INDEX_FIELDS,
            ReferenceIndex.SITE_CONCEPT_LIFECYCLE_STATUS_FIELD,
            // PanelValidation.validatePanels -> addFormFields (PanelValidation.java:684) and the
            // named-object existence checks for a panel's concept/query/procedure.
            ReferenceIndex.SITE_PANEL_DATASOURCE_ADD_FORM_FIELDS,
            ReferenceIndex.SITE_PANEL_DATASOURCE_CONCEPT,
            ReferenceIndex.SITE_PANEL_DATASOURCE_QUERY,
            ReferenceIndex.SITE_PANEL_DATASOURCE_PROCEDURE,
            // PackValidation.validateQueries -> concept, groupBy (including the join-path walk),
            // aggregates[].field and the where-predicate grammar.
            ReferenceIndex.SITE_QUERY_CONCEPT,
            ReferenceIndex.SITE_QUERY_GROUP_BY,
            ReferenceIndex.SITE_QUERY_GROUP_BY_JOIN,
            ReferenceIndex.SITE_QUERY_AGGREGATE_FIELD,
            // FlowValidation.validateFlows -> step capability/event/invariant existence.
            ReferenceIndex.SITE_FLOW_CONCEPT,
            ReferenceIndex.SITE_FLOW_STEP_CAPABILITY,
            ReferenceIndex.SITE_FLOW_STEP_EVENT,
            ReferenceIndex.SITE_FLOW_STEP_INVARIANT,
            // AggregateValidation.validateAggregates -> root/collection concept and childField.
            ReferenceIndex.SITE_AGGREGATE_ROOT,
            ReferenceIndex.SITE_AGGREGATE_COLLECTION_CONCEPT,
            ReferenceIndex.SITE_AGGREGATE_COLLECTION_CHILD_FIELD,
            // PanelValidation.validateAutoPanels / validateSelectors.
            ReferenceIndex.SITE_AUTOPANEL_CONCEPT,
            ReferenceIndex.SITE_AUTOPANEL_AGGREGATE,
            ReferenceIndex.SITE_SELECTOR_CONCEPT,
            // PanelValidation.validateGuidePages -> gadget query + axis (PanelValidation.java:692).
            ReferenceIndex.SITE_GUIDE_PAGE_GADGET_QUERY,
            ReferenceIndex.SITE_GUIDE_PAGE_GADGET_AXIS,
            // PackValidation.validateProcedures -> patchConcept.set keys and step targets.
            ReferenceIndex.SITE_PROCEDURE_STEP_SET_FIELD,
            ReferenceIndex.SITE_PROCEDURE_STEP_CONCEPT,
            ReferenceIndex.SITE_PROCEDURE_STEP_QUERY,
            ReferenceIndex.SITE_PROCEDURE_STEP_PROCEDURE
    );

    /**
     * Appends one error per UNRESOLVED edge at a site not already covered elsewhere.
     *
     * <p>{@link Resolution#UNDECIDABLE} never produces an error here, by design. A checker that
     * fails on what it cannot understand teaches authors to work around it, and the whole reason
     * this class exists is that "could not check" and "checked, fine" used to be indistinguishable.
     * {@code npdev inspect usage --orphans} is where UNDECIDABLE edges surface, visibly and without
     * blocking anything.
     */
    static void validate(ModelAst effectiveModel, List<String> errors) {
        ReferenceIndex index = ReferenceIndex.build(effectiveModel);
        for (ReferenceEdge edge : index.edges()) {
            if (edge.resolution() != Resolution.UNRESOLVED || REPORTED_ELSEWHERE.contains(edge.site())) {
                continue;
            }
            errors.add(describe(edge));
        }
    }

    /**
     * Message shape follows the convention the surrounding validators already use -- "&lt;Kind&gt;
     * &lt;name&gt; &lt;site&gt;: references unknown &lt;what&gt;" -- so a reader cannot tell which
     * validator produced which line, which is the point.
     */
    private static String describe(ReferenceEdge edge) {
        String owner = capitalize(edge.fromKind()) + " " + edge.fromName();
        String where = shortSite(edge.site());
        if (edge.targetsField()) {
            String field = edge.toName().startsWith(edge.ownerConcept() + ".")
                    ? edge.toName().substring(edge.ownerConcept().length() + 1)
                    : edge.toName();
            return owner + " " + where + ": references unknown field " + field
                    + " on concept " + edge.ownerConcept();
        }
        return owner + " " + where + ": references unknown " + edge.toKind() + " " + edge.toName();
    }

    /** {@code panel.layout.fields} -> {@code layout.fields}: the owner kind is already in the
     *  message's first half, and repeating it reads like a different object. */
    private static String shortSite(String site) {
        int dot = site.indexOf('.');
        return dot < 0 ? site : site.substring(dot + 1);
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
