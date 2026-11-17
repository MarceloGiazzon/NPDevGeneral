package com.npdev.dsl.v1;

import com.npdev.dsl.v1.compiled.CompiledCapability;
import com.npdev.dsl.v1.compiled.CompiledCapabilityBinding;
import com.npdev.dsl.v1.compiled.CompiledFlowStep;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FORM P3-T8: a {@code notify} step is a {map, notification.send} pair with the notification capability and its
 *  default adapter implied -- no capability or binding declaration. */
class NotifyStepTest {

    private static String model(String notifyStep, String extra) {
        return """
                {"namespace": "demo.notify", "dslVersion": "3.0.0", "version": "1.0",
                 "concepts": [{"name": "Room", "fields": [{"name": "id", "type": "uuid", "id": true},
                                                          {"name": "name", "type": "string", "required": true}]}],
                """ + extra + """
                 "flows": [{"name": "Welcome", "input": {"concept": "Room", "mode": "create"},
                   "steps": [""" + notifyStep + """
                             ,{"name": "done", "type": "return", "value": "$input"}]}]}
                """;
    }

    private static final String NOTIFY = "{\"name\": \"tell\", \"type\": \"notify\", \"to\": \"$input.name\", \"message\": \"Welcome\"}";

    @Test
    void notifyLowersToAMapAndANotificationSendCall() throws Exception {
        CompiledModel compiled = compile(model(NOTIFY, ""));
        List<CompiledFlowStep> steps = compiled.getFlows().get(0).getSteps();
        assertEquals("map", steps.get(0).getType());
        assertEquals("tell-payload", steps.get(0).getName());
        assertEquals("capability", steps.get(1).getType());
        assertEquals("tell", steps.get(1).getName());
        assertEquals("notification", steps.get(1).getCapabilityCall().getCapabilityName());
        assertEquals("send", steps.get(1).getCapabilityCall().getOperation());
    }

    @Test
    void noCapabilityOrBindingDeclarationIsNeeded() throws Exception {
        CompiledModel compiled = compile(model(NOTIFY, ""));
        CompiledCapability notification = compiled.getCapabilities().stream()
                .filter(c -> "notification".equals(c.getName())).findFirst().orElseThrow();
        assertEquals("NotificationCapability", notification.getType());
        assertTrue(compiled.getBindings().stream().map((CompiledCapabilityBinding b) -> b.getCapability() + ":" + b.getAdapter())
                .anyMatch("notification:notification-inproc"::equals));
    }

    @Test
    void theModelValidatesWithoutDeclaringTheCapability() throws Exception {
        var ast = new JsonModelParser().parse(write(model(NOTIFY, "")));
        var result = new SemanticValidator().validate(ast);
        assertTrue(result.isEmpty(), result.toString());
    }

    @Test
    void aDeclaredNotificationCapabilityWins() throws Exception {
        CompiledModel compiled = compile(model(NOTIFY,
                "\"capabilities\": [{\"name\": \"notification\", \"type\": \"NotificationCapability\", \"adapter\": \"mail-smtp\", \"operations\": [\"send\"]}],\n"));
        assertEquals(1, compiled.getCapabilities().stream().filter(c -> "notification".equals(c.getName())).count());
        assertTrue(compiled.getBindings().stream().anyMatch(b -> "notification".equals(b.getCapability()) && "mail-smtp".equals(b.getAdapter())));
    }

    @Test
    void errorTargetAndOutputCarryOverToTheSendStep() throws Exception {
        CompiledModel compiled = compile(model(
                "{\"name\": \"tell\", \"type\": \"notify\", \"to\": \"$input.name\", \"message\": \"Hi\", \"output\": \"$sent\", \"errorTarget\": \"$sendError\"}", ""));
        CompiledFlowStep send = compiled.getFlows().get(0).getSteps().get(1);
        assertEquals("$sent", send.getCapabilityCall().getOutputRef());
        assertEquals("$sendError", send.getErrorTarget());
    }

    @Test
    void aNotifyStepNeedsToAndMessage() throws Exception {
        Path file = write(model("{\"name\": \"tell\", \"type\": \"notify\", \"to\": \"$input.name\"}", ""));
        IOException ex = assertThrows(IOException.class, () -> new JsonModelParser().parse(file));
        assertTrue(ex.getMessage().contains("notify"), ex.getMessage());
    }

    private static Path write(String json) throws Exception {
        Path file = Files.createTempFile("npdev-notify-", ".json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    private static CompiledModel compile(String json) throws Exception {
        return new ModelCompiler().compile(new JsonModelParser().parse(write(json)));
    }
}
