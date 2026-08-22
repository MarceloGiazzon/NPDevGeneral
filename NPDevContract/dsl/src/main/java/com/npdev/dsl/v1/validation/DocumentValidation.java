package com.npdev.dsl.v1.validation;

import com.npdev.dsl.v1.ast.AggregateAst;
import com.npdev.dsl.v1.ast.AggregateCollectionAst;
import com.npdev.dsl.v1.ast.ConceptAst;
import com.npdev.dsl.v1.ast.DocumentAst;
import com.npdev.dsl.v1.ast.DocumentBandAst;
import com.npdev.dsl.v1.ast.DocumentLogoAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.ast.PanelFieldBindingAst;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.npdev.dsl.v1.validation.SemanticValidator.hasText;
import static com.npdev.dsl.v1.validation.SemanticValidator.normalize;

/**
 * R5.7 (Roadmap Wave 1 2026-08-19): semantic validation for {@code documents[]} -- concept/aggregate
 * resolution, and the band tree (header/lineItems bands bound to an aggregate's root/collections).
 * Split out to mirror {@link AggregateValidation}'s shape ({@code SemanticValidator} is an
 * orchestrator only, see its javadoc). Before this class, {@code documents[]} had exactly one
 * check anywhere -- {@code ModelCompiler}'s hard "concept not found" throw -- and nothing validated
 * a document's (new) {@code aggregate}/{@code bands}/{@code logo} at all.
 *
 * <p>The load-bearing check is {@link #validateBand}'s empty-fields rejection: a band with zero
 * field bindings would render either an empty header block or a line-item table with no columns,
 * which is visually indistinguishable from "this record legitimately has no line items" -- exactly
 * the "blank page instead of an authoring error" failure mode R5.7 exists to prevent. Caught here,
 * at validate time, rather than discovered on a printed invoice.
 */
final class DocumentValidation {

    private DocumentValidation() {
    }

    static void validateDocuments(ModelAst modelAst, Map<String, ConceptAst> entitiesByLower, List<String> errors) {
        Map<String, AggregateAst> aggregatesByLower = new HashMap<>();
        for (AggregateAst aggregate : modelAst.getAggregates()) {
            if (hasText(aggregate.name())) {
                aggregatesByLower.put(normalize(aggregate.name()), aggregate);
            }
        }

        Set<String> documentNames = new HashSet<>();
        for (DocumentAst document : modelAst.getDocuments()) {
            validateDocument(document, entitiesByLower, aggregatesByLower, documentNames, errors);
        }
    }

    private static void validateDocument(
            DocumentAst document,
            Map<String, ConceptAst> entitiesByLower,
            Map<String, AggregateAst> aggregatesByLower,
            Set<String> documentNames,
            List<String> errors) {
        String here = "Document " + document.name();
        if (!hasText(document.name())) {
            errors.add("Document: name is required -- suggestedFix: Give this document a 'name'.");
        } else if (!documentNames.add(normalize(document.name()))) {
            errors.add(here + ": duplicate document name -- suggestedFix: Rename this document so its "
                    + "'name' is unique among documents[].");
        }

        // document.concept existence is already a hard ModelCompiler.compileModel failure; this
        // lookup stays defensive (may be null for an unresolved/invalid concept) rather than
        // assuming that check already ran against this exact ModelAst.
        ConceptAst rootConcept = hasText(document.concept())
                ? entitiesByLower.get(normalize(document.concept())) : null;

        AggregateAst aggregate = null;
        if (hasText(document.aggregate())) {
            aggregate = aggregatesByLower.get(normalize(document.aggregate()));
            if (aggregate == null) {
                errors.add(here + ": aggregate not found: " + document.aggregate()
                        + " -- suggestedFix: Declare an aggregate with this name in aggregates[], or "
                        + "point 'aggregate' at one that already exists.");
            } else if (hasText(document.concept()) && hasText(aggregate.root())
                    && !normalize(document.concept()).equals(normalize(aggregate.root()))) {
                errors.add(here + ": concept '" + document.concept() + "' does not match aggregate '"
                        + document.aggregate() + "' root concept '" + aggregate.root() + "'"
                        + " -- suggestedFix: Change 'concept' to match the aggregate's root concept, "
                        + "or point 'aggregate' at an aggregate whose root is this concept.");
            }
        }

        if (!document.bands().isEmpty() && aggregate == null) {
            errors.add(here + ": bands declared but no aggregate is bound -- bands read the header "
                    + "and collections of a declared aggregate, so 'aggregate' must be set too"
                    + " -- suggestedFix: Declare 'aggregate' on this document, naming the aggregate "
                    + "whose header/collections the bands bind.");
        }

        Map<String, AggregateCollectionAst> collectionsByLower =
                aggregate == null ? Map.of() : indexCollections(aggregate.collections());

        Set<String> bandNames = new HashSet<>();
        boolean sawHeaderBand = false;
        for (DocumentBandAst band : document.bands()) {
            sawHeaderBand = validateBand(
                    document, band, here, rootConcept, aggregate, collectionsByLower,
                    entitiesByLower, bandNames, sawHeaderBand, errors);
        }

        validateLogo(document, here, rootConcept, errors);
    }

    private static boolean validateBand(
            DocumentAst document,
            DocumentBandAst band,
            String documentHere,
            ConceptAst rootConcept,
            AggregateAst aggregate,
            Map<String, AggregateCollectionAst> collectionsByLower,
            Map<String, ConceptAst> entitiesByLower,
            Set<String> bandNames,
            boolean sawHeaderBand,
            List<String> errors) {
        String bandHere = documentHere + " band";
        if (!hasText(band.name())) {
            errors.add(bandHere + ": name is required -- suggestedFix: Give this band a 'name'.");
        } else {
            bandHere = documentHere + " band '" + band.name() + "'";
            if (!bandNames.add(normalize(band.name()))) {
                errors.add(bandHere + ": duplicate band name -- suggestedFix: Rename this band so its "
                        + "'name' is unique within the document's bands.");
            }
        }

        String kind = normalize(band.kind());
        ConceptAst targetConcept = null;
        if ("header".equals(kind)) {
            if (sawHeaderBand) {
                errors.add(bandHere + ": a document may declare at most one header band"
                        + " -- suggestedFix: Remove or change the kind of the extra 'header' band -- "
                        + "a document may declare only one.");
            }
            sawHeaderBand = true;
            if (hasText(band.collection())) {
                errors.add(bandHere + ": a header band must not declare 'collection' "
                        + "(it binds the aggregate root, not a collection)"
                        + " -- suggestedFix: remove 'collection' from this band, or change its kind "
                        + "to 'lineItems' if you meant to bind one of the aggregate's collections");
            }
            targetConcept = rootConcept;
        } else if ("lineitems".equals(kind)) {
            if (!hasText(band.collection())) {
                errors.add(bandHere + ": kind 'lineItems' requires 'collection'"
                        + " -- suggestedFix: Add 'collection', naming one of the aggregate's declared "
                        + "top-level collections.");
            } else if (aggregate != null) {
                AggregateCollectionAst collection = collectionsByLower.get(normalize(band.collection()));
                if (collection == null) {
                    errors.add(bandHere + ": collection not found on aggregate '" + document.aggregate()
                            + "': " + band.collection() + " (a band binds one of the aggregate's TOP-LEVEL "
                            + "collections -- nested/dotted collection paths are not supported)"
                            + " -- suggestedFix: Point 'collection' at one of the aggregate's declared "
                            + "top-level collection names.");
                } else if (hasText(collection.concept())) {
                    targetConcept = entitiesByLower.get(normalize(collection.concept()));
                }
            }
        } else if (hasText(band.kind())) {
            errors.add(bandHere + ": kind must be 'header' or 'lineItems', found: " + band.kind()
                    + " -- suggestedFix: Change 'kind' to 'header' or 'lineItems'.");
        } else {
            errors.add(bandHere + ": kind is required (must be 'header' or 'lineItems')"
                    + " -- suggestedFix: Add 'kind', set to 'header' or 'lineItems'.");
        }

        // The load-bearing check: an empty band silently renders a blank header/table with no
        // visible error unless rejected right here (R5.7's "watch for").
        if (band.fields().isEmpty()) {
            errors.add(bandHere + ": must declare at least one field -- an empty band would render "
                    + "a blank header or a line-item table with no columns, indistinguishable from "
                    + "an authoring mistake"
                    + " -- suggestedFix: Add at least one entry to 'fields' naming a property on the "
                    + "band's bound concept.");
        }

        if (targetConcept != null) {
            Set<String> conceptFieldNames = targetConcept.getFields().stream()
                    .map(field -> normalize(field.getName()))
                    .collect(Collectors.toSet());
            for (PanelFieldBindingAst fieldBinding : band.fields()) {
                if (!hasText(fieldBinding.field())) {
                    errors.add(bandHere + ": a field binding requires 'field'"
                            + " -- suggestedFix: Add 'field', naming a property on the band's bound "
                            + "concept.");
                } else if (!conceptFieldNames.contains(normalize(fieldBinding.field()))) {
                    errors.add(bandHere + ": field '" + fieldBinding.field() + "' not found on concept '"
                            + targetConcept.getName() + "'"
                            + " -- suggestedFix: Point 'field' at a property declared on the target "
                            + "concept, or add that property to the concept.");
                }
            }
        }
        return sawHeaderBand;
    }

    private static void validateLogo(DocumentAst document, String here, ConceptAst rootConcept, List<String> errors) {
        DocumentLogoAst logo = document.logo();
        if (logo == null) {
            return;
        }
        if (!hasText(logo.field())) {
            errors.add(here + ": logo.field is required -- suggestedFix: Add 'field' under 'logo', "
                    + "naming a property on the document's root concept.");
            return;
        }
        if (rootConcept == null) {
            return;
        }
        boolean found = rootConcept.getFields().stream()
                .anyMatch(field -> normalize(field.getName()).equals(normalize(logo.field())));
        if (!found) {
            errors.add(here + ": logo field '" + logo.field() + "' not found on concept '"
                    + rootConcept.getName() + "'"
                    + " -- suggestedFix: Point 'logo.field' at a property declared on the document's "
                    + "root concept.");
        }
    }

    private static Map<String, AggregateCollectionAst> indexCollections(List<AggregateCollectionAst> collections) {
        Map<String, AggregateCollectionAst> out = new HashMap<>();
        if (collections == null) {
            return out;
        }
        for (AggregateCollectionAst collection : collections) {
            if (hasText(collection.name())) {
                out.put(normalize(collection.name()), collection);
            }
        }
        return out;
    }
}
