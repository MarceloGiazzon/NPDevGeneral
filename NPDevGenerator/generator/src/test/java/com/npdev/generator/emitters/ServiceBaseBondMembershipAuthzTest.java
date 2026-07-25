package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingStore;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-16-resid Round 3, finding R3-F2 (HIGH) — many-to-many bond endpoints had NO authorization.
 *
 * <p>Every generated app emits four HTTP endpoints per many-to-many bond:</p>
 * <pre>
 *   GET    /{id}/{bond}                 list members
 *   POST   /{id}/{bond}/{targetAnchor}  add a member
 *   DELETE /{id}/{bond}/{targetAnchor}  remove a member
 *   PUT    /{id}/{bond}                 replace all members
 * </pre>
 *
 * <p>All four went straight to {@code runtimeSupport.*BondMember*}, whose SQL keys on the source id
 * alone. So there was no coarse {@code checkCrudPermission}, no row-level {@code access.write} gate,
 * no tenant predicate and no audit — on a WRITE surface, in an app whose create/update/delete paths
 * had all four. {@code enforceBondTargetTenant} does not help either: it explicitly skips
 * many-to-many fields, because those live in a junction table rather than on the payload.</p>
 *
 * <p>This is LNCH13-F1's class of bug on a surface LNCH13-F1 never covered, and it is strictly worse
 * in one respect: LNCH13-F1 bypassed only the row-level gate, while this bypassed the coarse
 * permission check as well.</p>
 *
 * <p>This test asserts the SHAPE of the fix against real generator output.
 * {@code RowLevelAuthorizationAttackTest#userBCannotAuthorizeAWriteAgainstUserARow} carries the
 * behavioural half — that the gate it now calls actually denies.</p>
 */
class ServiceBaseBondMembershipAuthzTest {

    private static final String MODEL_JSON = """
            {
              "namespace": "reg16resid.bonds",
              "dslVersion": "1.0.0",
              "version": "v1",
              "concepts": [
                {
                  "name": "Tag",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "label", "type": "string", "required": true }
                  ]
                },
                {
                  "name": "Article",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "ownerId", "type": "string", "required": true },
                    { "name": "title", "type": "string", "required": true },
                    { "name": "tags", "type": "reference", "reference": { "target": "Tag", "multiple": true } }
                  ],
                  "access": {
                    "write": "ownerId == $user.id"
                  }
                }
              ]
            }
            """;

    private static String generateArticleService() throws Exception {
        Path modelPath = Files.createTempFile("npdev-bond-authz-", ".json");
        Files.writeString(modelPath, MODEL_JSON);
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(modelPath));

        Path out = Files.createTempDirectory("npdev-bond-authz-out-");
        new ServiceEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, true, new SettingResolver(SettingStore.empty()));

        try (var files = Files.walk(out)) {
            Path service = files
                    .filter(path -> path.getFileName().toString().equals("ArticleServiceBase.java"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("ArticleServiceBase.java was not emitted under " + out));
            return Files.readString(service);
        }
    }

    @Test
    void everyBondMembershipMutationIsAuthorizedBeforeItTouchesTheJunctionTable() throws Exception {
        String source = generateArticleService();

        for (String mutator : new String[]{"addTagsMember", "removeTagsMember", "replaceTagsMembers"}) {
            int method = source.indexOf("public void " + mutator + "(");
            assertTrue(method > 0, mutator + " must be emitted; generated source:\n" + source);

            int gate = source.indexOf("enforceBondMembershipWrite(id);", method);
            int mutation = source.indexOf("runtimeSupport.", method);
            assertTrue(gate > 0 && gate < mutation,
                    mutator + " must call enforceBondMembershipWrite BEFORE mutating the junction table"
                            + " -- a gate that runs after the write is not a gate");
        }
    }

    @Test
    void theBondWriteGateAppliesBothTheCoarsePermissionAndTheRowLevelScope() throws Exception {
        String source = generateArticleService();
        int gate = source.indexOf("private void enforceBondMembershipWrite(");
        assertTrue(gate > 0, "the shared bond write gate must be emitted; generated source:\n" + source);
        String body = source.substring(gate, source.indexOf("\n    }", gate));

        assertTrue(body.contains("checkCrudPermission(\"Article\", \"UPDATE\""),
                "the coarse permission check was missing entirely before this fix, not merely mis-ordered");
        assertTrue(body.contains("conceptGateway.authorizeWrite("),
                "row-level access.write must be enforced via the gateway's check-only entry point");
    }

    @Test
    void listingMembersRequiresTheSourceRecordToBeReadable() throws Exception {
        String source = generateArticleService();
        int method = source.indexOf("public List<Object> listTagsMembers(");
        assertTrue(method > 0, "listTagsMembers must be emitted");
        String body = source.substring(method, source.indexOf("\n    }", method));

        // A record's relationships disclose information about it, so listing them needs the same
        // scope reading it needs -- otherwise access.read is enforced on the row but not on its edges.
        assertTrue(body.contains("checkCrudPermission(\"Article\", \"READ\""), body);
        assertTrue(body.contains("requireReadableBondSource(id);"), body);
        assertTrue(body.indexOf("requireReadableBondSource(id);") < body.indexOf("runtimeSupport.listBondMembers"),
                "the read gate must precede the junction-table read");
    }

    @Test
    void bondMutationsAreAudited() throws Exception {
        String source = generateArticleService();
        // Create/update/delete all record an audit row; relationship edits were invisible to the
        // audit trail entirely, so a tampering event left no evidence anywhere.
        int method = source.indexOf("public void addTagsMember(");
        String body = source.substring(method, source.indexOf("\n    }", method));
        assertTrue(body.contains("auditCrudMutation(\"Article\", \"UPDATE\""), body);
    }
}
