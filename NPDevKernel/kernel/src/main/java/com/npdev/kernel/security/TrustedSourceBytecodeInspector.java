package com.npdev.kernel.security;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Constant-pool scanner that refuses Java class files whose bytecode references capability
 * escapes: filesystem/network IO, process/system control, reflection, dynamic loading, timers,
 * threads, scripting, detached/async execution, and JVM internals. SEC-3: this is the shared gate
 * both the generator's plugin-source admission and the generated app's boot-time plugin admission
 * enforce (B30).
 *
 * <p>It is a static-analysis barrier against DIRECT references, not a sandbox: anything on the app
 * classpath that proxies a forbidden capability on a plugin's behalf is outside its view.
 *
 * <p>Matching is deliberate:
 * <ul>
 *   <li>The constant pool is RESOLVED (Class/Methodref/Fieldref/InterfaceMethodref entries are
 *       spliced back into {@code owner} / {@code owner.name} strings), because javac stores
 *       {@code java.lang.System} and {@code exit} as SEPARATE pool constants -- a raw-Utf8
 *       substring scan can never see {@code System.exit}.</li>
 *   <li>Owners ending in {@code /} (e.g. {@code java/io/}) are PREFIX matches against every
 *       referenced class name AND every raw Utf8 constant (so type descriptors like
 *       {@code Ljava/io/File;} are caught even without a direct reference).</li>
 *   <li>Owners without a trailing slash (e.g. {@code java/lang/Runtime}) are EXACT class matches
 *       with a boundary check, so {@code Runtime.getRuntime()} is refused but a class merely
 *       catching {@code RuntimeException} is not ({@code java/lang/Class} vs
 *       {@code java/lang/ClassLoader} likewise).</li>
 *   <li>{@code java/util/concurrent/} is NOT banned wholesale: benign primitives like
 *       {@code AtomicLong} are ordinary thread-safety helpers, not escape vectors (the shipped
 *       auditLog capability uses one). Only the concurrent classes that can detach or schedule
 *       work that outlives the caller are banned.</li>
 *   <li>{@code System.*} methods are matched on the reconstructed reference
 *       ({@code java/lang/System.exit}), so a plugin merely DEFINING a method named {@code exit}
 *       is not refused -- only real references to the {@code System} member are.</li>
 *   <li>{@code java/lang/invoke/} is a banned prefix, which has a visible consequence: ANY
 *       lambda (which javac lowers to an invokedynamic binding through
 *       {@code java/lang/invoke/LambdaMetafactory}) is refused. That is the denylist's intended
 *       conservatism -- method handles are a standard static-analysis escape (SEC-3 analysis) --
 *       and applies equally to the source gate.</li>
 * </ul>
 */
public final class TrustedSourceBytecodeInspector {

    private static final int JAVA_CLASS_MAGIC = 0xCAFEBABE;

    /** Owners refused by PREFIX match (trailing {@code /}). */
    public static final Set<String> FORBIDDEN_OWNER_PREFIXES = Set.of(
            "java/io/",
            "java/nio/file/",
            "java/net/",
            "java/lang/reflect/",
            "java/lang/invoke/",
            "javax/script/",
            "sun/",
            "jdk/"
    );

    /** Owners refused by exact-class match (boundary-checked). */
    public static final Set<String> FORBIDDEN_OWNERS = Set.of(
            "java/lang/Runtime",
            "java/lang/Process",
            "java/lang/ProcessBuilder",
            "java/lang/Class",
            "java/lang/ClassLoader",
            "java/util/ServiceLoader",
            "java/lang/Thread",
            "java/lang/ThreadLocal",
            "java/util/Timer",
            // java/util/concurrent/ is not banned wholesale (AtomicLong etc. are benign) -- only
            // the executor/scheduler/async machinery that lets a plugin detach work that outlives
            // the wall-clock timeout, or coordinate its own threads. TimeUnit is included because
            // TimeUnit.sleep() delegates to Thread.sleep.
            "java/util/concurrent/Executor",
            "java/util/concurrent/ExecutorService",
            "java/util/concurrent/Executors",
            "java/util/concurrent/ThreadPoolExecutor",
            "java/util/concurrent/ScheduledThreadPoolExecutor",
            "java/util/concurrent/ScheduledExecutorService",
            "java/util/concurrent/ForkJoinPool",
            "java/util/concurrent/ForkJoinTask",
            "java/util/concurrent/CompletableFuture",
            "java/util/concurrent/CompletionService",
            "java/util/concurrent/CompletionStage",
            "java/util/concurrent/FutureTask",
            "java/util/concurrent/Delayed",
            "java/util/concurrent/ScheduledFuture",
            "java/util/concurrent/TimeUnit"
    );

    /** java.lang.System methods refused by reconstructed member reference. */
    public static final Set<String> FORBIDDEN_SYSTEM_METHODS = Set.of(
            "java/lang/System.getenv",
            "java/lang/System.getProperty",
            "java/lang/System.getProperties",
            "java/lang/System.setProperty",
            "java/lang/System.setProperties",
            "java/lang/System.exit"
    );

    public BytecodeInspectionResult inspect(Path classFile) throws IOException {
        try (InputStream input = Files.newInputStream(classFile)) {
            return inspect(input, classFile.toString());
        }
    }

    /**
     * Inspects a class file supplied as a stream (e.g. a classpath resource resolved at boot),
     * naming it with {@code displayName} in the result.
     */
    public BytecodeInspectionResult inspect(InputStream input, String displayName) throws IOException {
        ConstantPool pool = readConstantPool(input, displayName);
        List<String> violations = new ArrayList<>();

        // Raw-Utf8 sweep: catches forbidden types appearing only in method/field descriptors
        // (e.g. Ljava/io/File;) even when no resolved reference exists.
        for (String constant : pool.utf8()) {
            if (constant == null) {
                continue; // non-Utf8 pool slots are tracked as null placeholders
            }
            for (String prefix : FORBIDDEN_OWNER_PREFIXES) {
                if (constant.contains(prefix)) {
                    violations.add("forbidden owner " + prefix);
                }
            }
            for (String method : FORBIDDEN_SYSTEM_METHODS) {
                if (constant.contains(method)) {
                    violations.add("forbidden method " + method);
                }
            }
        }

        // Resolved class owners (class refs + method/field/interface-method refs).
        for (String owner : pool.referencedOwners()) {
            for (String prefix : FORBIDDEN_OWNER_PREFIXES) {
                if (owner.startsWith(prefix)) {
                    violations.add("forbidden owner " + prefix);
                }
            }
            for (String banned : FORBIDDEN_OWNERS) {
                if (referencesExactOwner(owner, banned)) {
                    violations.add("forbidden owner " + banned);
                }
            }
        }

        // Reconstructed member references (owner.name).
        for (String member : pool.referencedMembers()) {
            if (FORBIDDEN_SYSTEM_METHODS.contains(member)) {
                violations.add("forbidden method " + member);
            }
        }

        return new BytecodeInspectionResult(displayName, violations.isEmpty(), List.copyOf(violations));
    }

    private static boolean referencesExactOwner(String constant, String owner) {
        if (!constant.startsWith(owner)) {
            return false;
        }
        return constant.length() == owner.length()
                || !Character.isLetterOrDigit(constant.charAt(owner.length()));
    }

    private static ConstantPool readConstantPool(InputStream source, String displayName) throws IOException {
        try (DataInputStream data = new DataInputStream(source)) {
            int magic = data.readInt();
            if (magic != JAVA_CLASS_MAGIC) {
                throw new IOException("Not a Java class file: " + displayName);
            }
            data.readUnsignedShort();
            data.readUnsignedShort();
            int constantPoolCount = data.readUnsignedShort();

            List<String> utf8 = new ArrayList<>();
            utf8.add(null); // pool indices are 1-based; slot 0 unused
            Map<Integer, Integer> classNameIndexByClassRefIndex = new LinkedHashMap<>();
            List<int[]> memberRefs = new ArrayList<>();  // {classRefIndex, nameAndTypeIndex}
            Map<Integer, Integer> nameAndTypeNameIndex = new LinkedHashMap<>();

            int index = 1;
            while (index < constantPoolCount) {
                int tag = data.readUnsignedByte();
                switch (tag) {
                    case 1 -> {
                        utf8.add(data.readUTF());
                        index++;
                    }
                    case 3, 4 -> {
                        data.readInt();
                        utf8.add(null);
                        index++;
                    }
                    case 5, 6 -> {
                        data.readLong();
                        utf8.add(null);
                        utf8.add(null);
                        index += 2;
                    }
                    case 7 -> {
                        // CONSTANT_Class: the payload is the name_index of the class's Utf8 name;
                        // the ENTRY itself occupies pool slot `index`, which member refs point at.
                        classNameIndexByClassRefIndex.put(index, data.readUnsignedShort());
                        utf8.add(null);
                        index++;
                    }
                    case 8, 16, 19, 20 -> {
                        data.readUnsignedShort();
                        utf8.add(null);
                        index++;
                    }
                    case 9, 10, 11 -> {
                        int classIndex = data.readUnsignedShort();
                        int nameAndTypeIndex = data.readUnsignedShort();
                        memberRefs.add(new int[]{classIndex, nameAndTypeIndex});
                        utf8.add(null);
                        index++;
                    }
                    case 12 -> {
                        int nameIndex = data.readUnsignedShort();
                        data.readUnsignedShort();
                        nameAndTypeNameIndex.put(index, nameIndex);
                        utf8.add(null);
                        index++;
                    }
                    case 15 -> {
                        data.readUnsignedByte();
                        data.readUnsignedShort();
                        utf8.add(null);
                        index++;
                    }
                    case 17, 18 -> {
                        data.readUnsignedShort();
                        data.readUnsignedShort();
                        utf8.add(null);
                        index++;
                    }
                    default -> throw new IOException("Unsupported constant pool tag " + tag + " in " + displayName);
                }
            }

            Set<String> referencedOwners = new LinkedHashSet<>();
            for (int nameIndex : classNameIndexByClassRefIndex.values()) {
                String owner = constantUtf8(utf8, nameIndex);
                if (owner != null) {
                    referencedOwners.add(owner);
                }
            }
            Set<String> referencedMembers = new LinkedHashSet<>();
            for (int[] memberRef : memberRefs) {
                Integer classEntryIndex = memberRef[0];
                Integer classNameIndex = classNameIndexByClassRefIndex.get(classEntryIndex);
                String owner = classNameIndex == null ? null : constantUtf8(utf8, classNameIndex);
                Integer nameIndex = nameAndTypeNameIndex.get(memberRef[1]);
                String memberName = nameIndex == null ? null : constantUtf8(utf8, nameIndex);
                if (owner != null) {
                    referencedOwners.add(owner);
                }
                if (owner != null && memberName != null) {
                    referencedMembers.add(owner + "." + memberName);
                }
            }
            return new ConstantPool(new ArrayList<>(utf8), List.copyOf(referencedOwners), List.copyOf(referencedMembers));
        }
    }

    private static String constantUtf8(List<String> utf8, int index) {
        if (index < 0 || index >= utf8.size()) {
            return null;
        }
        return utf8.get(index);
    }

    private record ConstantPool(List<String> utf8, List<String> referencedOwners, List<String> referencedMembers) {
    }

    public record BytecodeInspectionResult(String classFile, boolean passed, List<String> violations) {
    }
}