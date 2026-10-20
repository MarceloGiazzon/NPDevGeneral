package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingStore;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** INT-9: the zero-dependency .xlsx helper is emitted, loaded before app.js, and wired into the grid. */
public class BusinessUiEmitterXlsxTest {

    private static Path emit() throws Exception {
        Path modelPath = Files.createTempFile("npdev-xlsx-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "xlsx.demo", "dslVersion": "1.0.0", "version": "1.0",
                  "concepts": [
                    { "name": "Customer", "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "displayName", "type": "string", "required": true } ] }
                  ]
                }
                """);
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(modelPath));
        Path out = Files.createTempDirectory("npdev-xlsx-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(SettingStore.empty()));
        return out;
    }

    private static boolean nodeOnPath() {
        try {
            Process process = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static String run(Path workDir, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(workDir.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "node timed out");
        assertEquals(0, process.exitValue(), output);
        return output;
    }

    @Test
    void helperIsEmittedLoadedBeforeAppAndWiredIntoTheGrid() throws Exception {
        Path out = emit();
        Path helper = out.resolve("src/main/resources/static/npdev-xlsx.js");
        assertTrue(Files.exists(helper), "static/npdev-xlsx.js must be emitted");
        assertTrue(Files.readString(helper).contains("NpdevXlsx"));

        String index = Files.readString(out.resolve("src/main/resources/static/npdev-business-ui/index.html"));
        assertTrue(index.contains("/npdev-xlsx.js"));
        assertTrue(index.indexOf("/npdev-xlsx.js") < index.indexOf("./app.js"), "xlsx helper must load before app.js");

        String app = CarvedUiModules.appJsWithModules(out);
        assertTrue(app.contains("function exportPanelXlsx"));
        assertTrue(app.contains("NpdevXlsx.readFirstSheet"));
        assertTrue(app.contains("Export Excel"));
        assertTrue(app.contains("async function collectExportRows"));
    }

    @Test
    void emittedScriptsParseAndTheWriterProducesAReadableZip() throws Exception {
        Assumptions.assumeTrue(nodeOnPath(), "node not on PATH");
        Path out = emit();
        Path helper = out.resolve("src/main/resources/static/npdev-xlsx.js");
        Path app = out.resolve("src/main/resources/static/npdev-business-ui/app.js");
        run(out, "node", "--check", helper.toString());
        run(out, "node", "--check", app.toString());

        Path script = out.resolve("xlsx-check.js");
        Files.writeString(script, """
                const fs = require('fs');
                const zlib = require('zlib');
                globalThis.window = globalThis;
                eval(fs.readFileSync(process.argv[2], 'utf8'));
                (async () => {
                  const blob = NpdevXlsx.writeXlsx(
                    [{ name: 'a', label: 'Name' }, { name: 'n', label: 'Qty' }],
                    [{ a: 'A\\u00e7\\u00e3o =1+1', n: 3 }, { a: '=1+1', n: 4 }, { a: '-42', n: 5 }], 'T/est');
                  const buf = Buffer.from(await blob.arrayBuffer());
                  const eocd = buf.length - 22;
                  if (buf.readUInt32LE(eocd) !== 0x06054b50) throw new Error('no EOCD');
                  const count = buf.readUInt16LE(eocd + 10);
                  let ptr = buf.readUInt32LE(eocd + 16);
                  const parts = {};
                  for (let i = 0; i < count; i++) {
                    const size = buf.readUInt32LE(ptr + 20), nameLen = buf.readUInt16LE(ptr + 28);
                    const crc = buf.readUInt32LE(ptr + 16), off = buf.readUInt32LE(ptr + 42);
                    const name = buf.toString('utf8', ptr + 46, ptr + 46 + nameLen);
                    const start = off + 30 + buf.readUInt16LE(off + 26) + buf.readUInt16LE(off + 28);
                    const data = buf.subarray(start, start + size);
                    if (zlib.crc32 && zlib.crc32(data) !== crc) throw new Error('bad crc for ' + name);
                    parts[name] = data.toString('utf8');
                    ptr += 46 + nameLen;
                  }
                  for (const n of ['[Content_Types].xml', '_rels/.rels', 'xl/workbook.xml', 'xl/_rels/workbook.xml.rels', 'xl/worksheets/sheet1.xml'])
                    if (!parts[n]) throw new Error('missing part ' + n);
                  const sheet = parts['xl/worksheets/sheet1.xml'];
                  if (!sheet.includes('A\\u00e7\\u00e3o')) throw new Error('accents lost');
                  if (!sheet.includes("'=1+1")) throw new Error('formula not neutralised');
                  if (!sheet.includes('<v>3</v>')) throw new Error('number not numeric');
                  if (sheet.includes("'-42")) throw new Error('plain negative number must stay a number');
                  if (!parts['xl/workbook.xml'].includes('name="T est"')) throw new Error('sheet name not sanitised');
                  console.log('ok');
                })().catch(e => { console.error(e); process.exit(1); });
                """);
        String output = run(out, "node", script.toString(), helper.toString());
        assertFalse(output.isBlank());
        assertTrue(output.contains("ok"), output);
    }
}
