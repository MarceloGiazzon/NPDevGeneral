[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$SourcePath,
    [Parameter(Mandatory = $true)]
    [string]$ClassName,
    [string]$MethodName = "execute",
    [string]$OutputRoot = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

$workspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
$workspaceRoot = Normalize-NPDevPath $workspaceRoot
$SourcePath = Normalize-NPDevPath $SourcePath
Ensure-NPDevFile $SourcePath "Trusted Java procedure source"

if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $hash = (Get-FileHash -LiteralPath $SourcePath -Algorithm SHA256).Hash.ToLowerInvariant()
    $OutputRoot = Resolve-NPDevWorkspacePath $workspaceRoot ("scripts\reports\out\ai-beta\trusted-java\" + $hash)
}
else {
    $OutputRoot = Normalize-NPDevPath $OutputRoot
}
New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null

$contextPath = Join-Path $OutputRoot "NPDevProcedureContext.java"
$runnerPath = Join-Path $OutputRoot "TrustedProcedureRunner.java"

function Write-Utf8NoBomFile {
    param(
        [string]$Path,
        [string]$Value
    )

    $encoding = New-Object System.Text.UTF8Encoding -ArgumentList $false
    [System.IO.File]::WriteAllText($Path, $Value, $encoding)
}

Write-Utf8NoBomFile -Path $contextPath -Value @'
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class NPDevProcedureContext {
    private final Map<String, List<Map<String, Object>>> recordsByConcept = new LinkedHashMap<>();

    public Map<String, Object> saveConcept(String conceptName, Map<String, Object> record) {
        if (conceptName == null || conceptName.isBlank()) {
            throw new IllegalArgumentException("conceptName must be non-blank");
        }
        if (record == null) {
            throw new IllegalArgumentException("record must be non-null");
        }
        Map<String, Object> copy = new LinkedHashMap<>(record);
        recordsByConcept.computeIfAbsent(conceptName, ignored -> new ArrayList<>()).add(copy);
        return copy;
    }

    public List<Map<String, Object>> saveMany(String conceptName, List<Map<String, Object>> records) {
        if (records == null) {
            throw new IllegalArgumentException("records must be non-null");
        }
        List<Map<String, Object>> saved = new ArrayList<>();
        for (Map<String, Object> record : records) {
            saved.add(saveConcept(conceptName, record));
        }
        return saved;
    }

    public Map<String, List<Map<String, Object>>> recordsByConcept() {
        return recordsByConcept;
    }
}
'@

Write-Utf8NoBomFile -Path $runnerPath -Value @'
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TrustedProcedureRunner {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: TrustedProcedureRunner <className> <methodName>");
            System.exit(2);
        }

        String className = args[0];
        String methodName = args[1];
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdoutBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBuffer = new ByteArrayOutputStream();
        NPDevProcedureContext context = new NPDevProcedureContext();
        Map<String, Object> response = new LinkedHashMap<>();
        int exitCode = 0;

        try (
            PrintStream capturedOut = new PrintStream(stdoutBuffer, true, StandardCharsets.UTF_8);
            PrintStream capturedErr = new PrintStream(stderrBuffer, true, StandardCharsets.UTF_8)
        ) {
            System.setOut(capturedOut);
            System.setErr(capturedErr);
            Class<?> sourceClass = Class.forName(className);
            Method method = sourceClass.getMethod(methodName, NPDevProcedureContext.class);
            Object target = Modifier.isStatic(method.getModifiers()) ? null : sourceClass.getDeclaredConstructor().newInstance();
            Object result = method.invoke(target, context);
            response.put("ok", true);
            response.put("result", result);
            response.put("recordsByConcept", context.recordsByConcept());
            response.put("createdRecordCount", createdRecordCount(context.recordsByConcept()));
        } catch (Throwable error) {
            Throwable effective = error.getCause() == null ? error : error.getCause();
            response.put("ok", false);
            response.put("error", effective.getMessage() == null ? effective.getClass().getName() : effective.getMessage());
            response.put("recordsByConcept", context.recordsByConcept());
            response.put("createdRecordCount", createdRecordCount(context.recordsByConcept()));
            exitCode = 1;
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        response.put("diagnostics", diagnostics(stdoutBuffer.toString(StandardCharsets.UTF_8), stderrBuffer.toString(StandardCharsets.UTF_8)));
        originalOut.print(toJson(response));
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static int createdRecordCount(Map<String, List<Map<String, Object>>> recordsByConcept) {
        int count = 0;
        for (List<Map<String, Object>> records : recordsByConcept.values()) {
            count += records.size();
        }
        return count;
    }

    private static List<Map<String, Object>> diagnostics(String stdout, String stderr) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (stdout != null && !stdout.isBlank()) {
            items.add(Map.of("level", "info", "message", stdout.trim()));
        }
        if (stderr != null && !stderr.isBlank()) {
            items.add(Map.of("level", "error", "message", stderr.trim()));
        }
        return items;
    }

    private static String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String stringValue) {
            return quote(stringValue);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> mapValue) {
            StringBuilder builder = new StringBuilder();
            builder.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                builder.append(quote(String.valueOf(entry.getKey())));
                builder.append(':');
                builder.append(toJson(entry.getValue()));
            }
            builder.append('}');
            return builder.toString();
        }
        if (value instanceof Collection<?> collectionValue) {
            StringBuilder builder = new StringBuilder();
            builder.append('[');
            boolean first = true;
            for (Object item : collectionValue) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                builder.append(toJson(item));
            }
            builder.append(']');
            return builder.toString();
        }
        if (value.getClass().isArray()) {
            StringBuilder builder = new StringBuilder();
            builder.append('[');
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                if (index > 0) {
                    builder.append(',');
                }
                builder.append(toJson(Array.get(value, index)));
            }
            builder.append(']');
            return builder.toString();
        }
        return quote(String.valueOf(value));
    }

    private static String quote(String value) {
        StringBuilder builder = new StringBuilder();
        builder.append('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (current < 0x20) {
                        builder.append(String.format("\\u%04x", (int) current));
                    } else {
                        builder.append(current);
                    }
                }
            }
        }
        builder.append('"');
        return builder.toString();
    }
}
'@

$javac = Get-Command javac -ErrorAction Stop
$java = Get-Command java -ErrorAction Stop

$compileOutput = & $javac.Source -encoding UTF-8 -d $OutputRoot $contextPath $runnerPath $SourcePath 2>&1
if ($LASTEXITCODE -ne 0) {
    throw ("Trusted Java procedure compilation failed: " + (($compileOutput | Out-String).Trim()))
}

$runOutput = & $java.Source -cp $OutputRoot TrustedProcedureRunner $ClassName $MethodName 2>&1
if ($LASTEXITCODE -ne 0) {
    throw ("Trusted Java procedure execution failed: " + (($runOutput | Out-String).Trim()))
}

($runOutput | Out-String).Trim()
