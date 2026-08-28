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
 *       {@code Ljava/io/File;} are caught even without a direct reference), with the exact-class
 *       exemptions in {@link #FORBIDDEN_OWNER_PREFIX_EXEMPTIONS} applied ({@code java/io/PrintStream}
 *       -- {@code System.out}/{@code System.err} console output, not filesystem IO).</li>
 *   <li>Owners without a trailing slash (e.g. {@code java/lang/Runtime}) are EXACT class matches,
 *       judged on MEMBER-reference owners only (what the plugin actually invokes or reads -- a
 *       class-ref-only appearance is compiler data an author never wrote, e.g. the
 *       {@code MethodHandles$Lookup} ref javac emits for any string concatenation). A plugin merely
 *       catching {@code RuntimeException} is not refused; {@code Runtime.getRuntime()} is.</li>
 *   <li>{@code java/util/concurrent/} is NOT banned wholesale: benign primitives like
 *       {@code AtomicLong} are ordinary thread-safety helpers, not escape vectors (the shipped
 *       auditLog capability uses one). Only the concurrent classes that can detach or schedule
 *       work that outlives the caller are banned.</li>
 *   <li>{@code System.*} methods are matched on the reconstructed reference
 *       ({@code java/lang/System.exit}), so a plugin merely DEFINING a method named {@code exit}
 *       is not refused -- only real references to the {@code System} member are.</li>
 *   <li>{@code java/lang/invoke/} is NOT a banned prefix (ordinary string concatenation lowers to
 *       invokedynamic through {@code java/lang/invoke/StringConcatFactory}); only the
 *       author-controllable method-handle classes and {@code LambdaMetafactory} are banned exact
 *       owners. The consequence that matters: ANY lambda (which javac lowers through
 *       {@code java/lang/invoke/LambdaMetafactory}) is refused -- the denylist's intended
 *       conservatism (method handles are a standard static-analysis escape; SEC-3 analysis) --
 *       applied consistently in the source gate too.</li>
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
            // NOTE: java/lang/invoke/ is NOT banned wholesale. The compiler lowers ordinary
            // string concatenation ('+') to invokedynamic through java/lang/invoke/StringConcatFactory
            // -- a boot gate that refused that would refuse almost every real plugin. Only the
            // AUTHOR-CONTROLLABLE method-handle classes and LambdaMetafactory are banned (the
            // latter keeps the documented 'no lambdas' conservatism of SEC-3: any lambda lowers
            // through it).
            "javax/script/",
            "sun/",
            "jdk/"
    );

    /**
     * Exact classes exempt from an otherwise-banned prefix. {@code java/io/PrintStream} (reached
     * only through {@code System.out}/{@code System.err} references) is console output, not
     * filesystem IO -- the shipped lib-probe capability logs a diagnostic line to the app log this
     * way. It stays safe because a PrintStream instance can only come from {@code System.out/err}
     * or by wrapping a banned stream class (whose own references ARE caught).
     */
    public static final Map<String, Set<String>> FORBIDDEN_OWNER_PREFIX_EXEMPTIONS = Map.of(
            "java/io/", Set.of("java/io/PrintStream")
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
            // Author-controllable method-handle machinery (java/lang/invoke/StringConcatFactory,
            // the compiler's string-concatenation implementation, is deliberately NOT banned, and
            // java/lang/invoke/ is not a prefix rule). LambdaMetafactory being banned is the
            // documented 'no lambdas' conservatism of SEC-3.
            "java/lang/invoke/MethodHandle",
            "java/lang/invoke/MethodHandles",
            "java/lang/invoke/MethodHandles$Lookup",
            "java/lang/invoke/MethodType",
            "java/lang/invoke/VarHandle",
            "java/lang/invoke/CallSite",
            "java/lang/invoke/MethodHandleInfo",
            "java/lang/invoke/MutableCallSite",
            "java/lang/invoke/VolatileCallSite",
            "java/lang/invoke/LambdaMetafactory",
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
                if (violatesForbiddenPrefix(constant, prefix)) {
                    violations.add("forbidden owner " + prefix);
                }
            }
            for (String method : FORBIDDEN_SYSTEM_METHODS) {
                if (constant.contains(method)) {
                    violations.add("forbidden method " + method);
                }
            }
        }

        // Class-reference owners (type positions, compiler-generated CONSTANT_Class data): PREFIX
        // rules only. The exact-owner list is deliberately NOT applied here -- javac emits
        // java/lang/invoke/MethodHandles$Lookup / MethodType / CallSite / StringConcatFactory as
        // PLAIN CLASS REFS for any string concatenation or lambda indy site, so an exact-owner
        // match would refuse ordinary source the author never wrote. What a plugin INVOKES is what
        // the exact rules judge (member owners, below).
        for (String owner : pool.classOwners()) {
            for (String prefix : FORBIDDEN_OWNER_PREFIXES) {
                if (violatesForbiddenPrefix(owner, prefix)) {
                    violations.add("forbidden owner " + prefix);
                }
            }
        }

        // Member-reference owners (method/field/interface-method targets -- what the plugin
        // actually invokes or reads): PREFIX rules and the exact-class list both apply.
        for (String owner : pool.memberOwners()) {
            for (String prefix : FORBIDDEN_OWNER_PREFIXES) {
                if (violatesForbiddenPrefix(owner, prefix)) {
                    violations.add("forbidden owner " + prefix);
                }
            }
            for (String banned : FORBIDDEN_OWNERS) {
                if (owner.equals(banned)) {
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

    private static boolean violatesForbiddenPrefix(String value, String prefix) {
        if (!value.contains(prefix)) {
            return false;
        }
        Set<String> exemptions = FORBIDDEN_OWNER_PREFIX_EXEMPTIONS.get(prefix);
        if (exemptions == null) {
            return true;
        }
        for (String exempt : exemptions) {
            if (value.contains(exempt)) {
                return false;
            }
        }
        return true;
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

            Set<String> classOwners = new LinkedHashSet<>();
            for (int nameIndex : classNameIndexByClassRefIndex.values()) {
                String owner = constantUtf8(utf8, nameIndex);
                if (owner != null) {
                    classOwners.add(owner);
                }
            }
            Set<String> memberOwners = new LinkedHashSet<>();
            Set<String> referencedMembers = new LinkedHashSet<>();
            for (int[] memberRef : memberRefs) {
                Integer classEntryIndex = memberRef[0];
                Integer classNameIndex = classNameIndexByClassRefIndex.get(classEntryIndex);
                String owner = classNameIndex == null ? null : constantUtf8(utf8, classNameIndex);
                Integer nameIndex = nameAndTypeNameIndex.get(memberRef[1]);
                String memberName = nameIndex == null ? null : constantUtf8(utf8, nameIndex);
                if (owner != null) {
                    memberOwners.add(owner);
                }
                if (owner != null && memberName != null) {
                    referencedMembers.add(owner + "." + memberName);
                }
            }
            return new ConstantPool(new ArrayList<>(utf8), List.copyOf(classOwners), List.copyOf(memberOwners), List.copyOf(referencedMembers));
        }
    }

    private static String constantUtf8(List<String> utf8, int index) {
        if (index < 0 || index >= utf8.size()) {
            return null;
        }
        return utf8.get(index);
    }

    private record ConstantPool(List<String> utf8, List<String> classOwners, List<String> memberOwners, List<String> referencedMembers) {
    }

    public record BytecodeInspectionResult(String classFile, boolean passed, List<String> violations) {
    }
}