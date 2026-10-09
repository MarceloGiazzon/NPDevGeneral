package com.npdev.dsl.v1.compiled;

import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * #34 (Pigmentampas friction log): a procedure step's {@code args} binds POSITIONALLY to the
 * capability method, so it must reach the runtime in the author's DECLARED key order. ModelCompiler
 * and the canonical-JSON writer both used to sort it alphabetically, which delivered
 * {@code {to, subject, body}} to mail.send as [body, subject, to] -- the body text became the To
 * address. Covers the whole chain a generated app's NPDevModelProvider reads at boot: parse ->
 * compile -> canonical JSON -> reader.
 */
class ProcedureArgsDeclaredOrderTest {

    private static final List<String> DECLARED = List.of("to", "subject", "body", "templateVars");

    private static final String MODEL = """
            {
              "namespace":"demo",
              "dslVersion":"1.0.0",
              "version":"v1",
              "concepts":[
                {"name":"Note","fields":[{"name":"id","type":"uuid","id":true}]}
              ],
              "capabilities":[
                {"name":"mail","type":"MailCapability","operations":["send"]}
              ],
              "procedures":[
                {
                  "name":"NotifySomeone",
                  "steps":[
                    {"name":"send-mail","type":"capabilityCall","capability":"mail","operation":"send",
                     "args":{"to":"$input.email","subject":"Hello","body":"Hi there","templateVars":"$input"},
                     "target":"delivery"}
                  ]
                }
              ]
            }
            """;

    @Test
    void capabilityCallArgsKeepDeclaredOrderThroughCompileAndCanonicalRoundTrip() throws Exception {
        Path model = Files.createTempFile("npdev-procedure-args-order-", ".json");
        Files.writeString(model, MODEL, StandardCharsets.UTF_8);
        CompiledModel compiled = new ModelCompiler().compile(new JsonModelParser().parse(model));

        assertEquals(DECLARED, argKeys(compiled), "compiled args must keep declared order");

        CompiledModel roundTripped = CompiledModelCanonicalJsonReader.fromJson(CompiledModelCanonicalJson.toJson(compiled));
        assertEquals(DECLARED, argKeys(roundTripped), "canonical JSON must keep declared args order");
    }

    private static List<String> argKeys(CompiledModel model) {
        CompiledProcedureStep step = model.getProcedures().get(0).steps().get(0);
        return List.copyOf(step.args().keySet());
    }
}
