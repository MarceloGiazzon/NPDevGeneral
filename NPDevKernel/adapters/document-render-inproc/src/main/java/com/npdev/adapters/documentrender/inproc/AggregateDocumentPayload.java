package com.npdev.adapters.documentrender.inproc;

import com.npdev.kernel.ports.DocumentRenderContract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R5.7 (Roadmap Wave 1 2026-08-19): parses the untyped payload the {@code documentRender}
 * capability's new {@code renderAggregate} operation accepts -- an aggregate's already-loaded data
 * tree (the exact {@code Map<String,Object>} shape {@code AggregateRuntime.load()} produces: root
 * fields at the top level, a named {@code List<Map<String,Object>>} per declared collection) plus the
 * document's compiled band definitions. A band binds against that tree directly; this adapter never
 * re-queries anything itself.
 *
 * <p>Kept entirely inside this adapter's own package rather than added to {@code
 * com.npdev.kernel.ports.DocumentRenderPayload} (which parses the pre-existing flat {@code render}
 * operation's payload) -- this module does not own {@code NPDevKernel/kernel/**}, and {@link
 * DocumentRenderContract}'s {@code render(html, options)} signature is unchanged; {@code
 * renderAggregate} is an additional {@code CapabilityAdapter} operation this class alone implements,
 * the same way the existing {@code render} operation already is one capability operation among
 * others an adapter can add without touching the kernel port.
 */
final class AggregateDocumentPayload {

    private AggregateDocumentPayload() {
    }

    record BandField(String field, String label) {
    }

    record Band(String name, String kind, String collection, String label, List<BandField> fields) {
    }

    record Request(
            String title,
            DocumentRenderContract.PageSize pageSize,
            Double marginMm,
            String filename,
            String logoDataUri,
            Map<String, Object> tree,
            List<Band> bands
    ) {
        DocumentRenderContract.RenderOptions toRenderOptions() {
            DocumentRenderContract.RenderOptions defaults = DocumentRenderContract.RenderOptions.defaults();
            return new DocumentRenderContract.RenderOptions(
                    pageSize == null ? defaults.pageSize() : pageSize,
                    marginMm == null ? defaults.marginMm() : marginMm
            );
        }

        String filenameOrDefault() {
            return (filename == null || filename.isBlank()) ? "document.pdf" : filename;
        }
    }

    @SuppressWarnings("unchecked")
    static Request parse(List<Object> args) {
        if (args == null || args.isEmpty() || !(args.get(0) instanceof Map<?, ?> map)) {
            throw new DocumentRenderContract.DocumentRenderException(
                    "renderAggregate requires a single object payload with 'tree' and 'bands'", null);
        }
        String title = stringOf(map.get("title"));
        DocumentRenderContract.PageSize pageSize = toPageSize(map.get("pageSize"));
        Double marginMm = toDouble(map.get("marginMm"));
        String filename = stringOf(map.get("filename"));
        String logoDataUri = stringOf(map.get("logoDataUri"));
        Object treeObj = map.get("tree");
        Map<String, Object> tree = (treeObj instanceof Map<?, ?> treeMap)
                ? (Map<String, Object>) treeMap : new LinkedHashMap<>();
        List<Band> bands = parseBands(map.get("bands"));
        return new Request(title, pageSize, marginMm, filename, logoDataUri, tree, bands);
    }

    private static List<Band> parseBands(Object value) {
        List<Band> out = new ArrayList<>();
        if (!(value instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> bandMap)) {
                continue;
            }
            out.add(new Band(
                    stringOf(bandMap.get("name")),
                    stringOf(bandMap.get("kind")),
                    stringOf(bandMap.get("collection")),
                    stringOf(bandMap.get("label")),
                    parseFields(bandMap.get("fields"))
            ));
        }
        return out;
    }

    private static List<BandField> parseFields(Object value) {
        List<BandField> out = new ArrayList<>();
        if (!(value instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> fieldMap) {
                String field = stringOf(fieldMap.get("field"));
                String label = stringOf(fieldMap.get("label"));
                out.add(new BandField(field, label == null ? field : label));
            } else if (item != null) {
                String field = String.valueOf(item);
                out.add(new BandField(field, field));
            }
        }
        return out;
    }

    private static String stringOf(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static DocumentRenderContract.PageSize toPageSize(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        return "LETTER".equalsIgnoreCase(text)
                ? DocumentRenderContract.PageSize.LETTER : DocumentRenderContract.PageSize.A4;
    }

    private static Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : Double.valueOf(text);
    }
}
