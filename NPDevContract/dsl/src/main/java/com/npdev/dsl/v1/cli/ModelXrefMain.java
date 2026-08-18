package com.npdev.dsl.v1.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.resolution.ModelResolver;
import com.npdev.dsl.v1.xref.ReferenceIndex;
import com.npdev.dsl.v1.xref.ReferenceIndexJson;

import java.nio.file.Path;
import java.util.Locale;

/**
 * XREF-2's engine: build the model-wide reference index for one {@code model.json} and emit it as
 * {@code npdev-model-xref.v1} JSON.
 *
 * <p>Run by the {@code :NPDevContract:dsl:modelXref} Gradle task, which {@code npdev inspect usage}
 * shells out to -- the same shape {@code validate model} already uses for
 * {@link ModelValidatorMain}. The CLI is a reader of this document, NOT a second implementation:
 * pack/context composition and {@code qualifierId::Name} qualification exist only on this side, and
 * a Python re-walk of the same graph is REG-108's exact shape.
 *
 * <p>Resolution matters and is easy to get wrong: this runs {@link ModelResolver} first, exactly as
 * {@code SemanticValidator} does, so the index is built over the EFFECTIVE model (specializations
 * flattened, packs and contexts composed). Indexing the raw parse instead would report every
 * specialized concept's inherited references as orphans.
 *
 * <p>Exit codes: {@code 0} whenever the model could be read -- unresolved references are DATA in
 * the report, not a failure of this command. A caller that wants "fail on orphans" (that is
 * {@code npdev inspect usage --orphans}) reads the report and decides; making it a non-zero exit
 * here would break {@code inspect usage --of X}, which must still answer on a model that happens to
 * have an orphan somewhere else. {@code 2} is reserved for a model that cannot be parsed at all,
 * {@code 64} for a usage error.
 */
public final class ModelXrefMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ModelXrefMain() {
    }

    public static void main(String[] args) {
        Locale.setDefault(Locale.ENGLISH);

        String modelArg = null;
        String outArg = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--out".equals(arg) && i + 1 < args.length) {
                outArg = args[++i];
            } else if (arg.startsWith("--out=")) {
                outArg = arg.substring("--out=".length());
            } else if (!arg.startsWith("--")) {
                modelArg = arg;
            }
        }
        if (modelArg == null) {
            System.err.println("usage: ModelXrefMain <model.json> [--out model-xref.json]");
            System.exit(64);
            return;
        }

        Path modelPath = Path.of(modelArg);
        ModelAst effectiveModel;
        try {
            ModelAst parsed = new JsonModelParser().parse(modelPath);
            effectiveModel = new ModelResolver().resolve(parsed).modelAst();
        } catch (Exception parseError) {
            // Deliberately a plain, single-line message rather than a diagnostics array: a model
            // that will not parse is `validate model`'s business, and reproducing its typed report
            // here would be a second, drifting copy of the same diagnosis.
            System.err.println("npdev-model-xref: cannot read model " + modelPath + ": "
                    + safeMessage(parseError));
            System.exit(2);
            return;
        }

        ObjectNode report = ReferenceIndexJson.toJson(modelPath.toString(),
                ReferenceIndex.build(effectiveModel));
        String json = ReportIo.serialize(MAPPER, report);
        if (outArg != null) {
            ReportIo.write(outArg, json);
        }
        System.out.println(json);
        System.exit(0);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
