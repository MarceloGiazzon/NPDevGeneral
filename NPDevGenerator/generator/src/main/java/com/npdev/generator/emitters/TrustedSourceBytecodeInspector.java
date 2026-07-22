package com.npdev.generator.emitters;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class TrustedSourceBytecodeInspector {
    private static final int JAVA_CLASS_MAGIC = 0xCAFEBABE;
    private static final Set<String> FORBIDDEN_OWNERS = Set.of(
            "java/io/",
            "java/nio/file/",
            "java/net/",
            "java/lang/Runtime",
            "java/lang/Process",
            "java/lang/ProcessBuilder",
            "java/lang/reflect/",
            "java/lang/invoke/",
            "java/lang/Class",
            "java/lang/ClassLoader",
            "java/util/ServiceLoader",
            "java/lang/Thread",
            "java/lang/ThreadLocal",
            "java/util/Timer",
            "java/util/concurrent/",
            "javax/script/",
            "sun/",
            "jdk/"
    );
    private static final Set<String> FORBIDDEN_SYSTEM_METHODS = Set.of(
            "java/lang/System.getenv",
            "java/lang/System.getProperty",
            "java/lang/System.getProperties",
            "java/lang/System.setProperty",
            "java/lang/System.setProperties",
            "java/lang/System.exit"
    );

    public BytecodeInspectionResult inspect(Path classFile) throws IOException {
        List<String> constants = readUtf8Constants(classFile);
        List<String> violations = new ArrayList<>();
        for (String constant : constants) {
            for (String owner : FORBIDDEN_OWNERS) {
                if (constant.contains(owner)) {
                    violations.add("forbidden owner " + owner);
                }
            }
            for (String method : FORBIDDEN_SYSTEM_METHODS) {
                if (constant.contains(method) || constant.endsWith(method.substring(method.lastIndexOf('.') + 1))) {
                    violations.add("forbidden method " + method);
                }
            }
        }
        return new BytecodeInspectionResult(classFile.toString(), violations.isEmpty(), List.copyOf(violations));
    }

    private static List<String> readUtf8Constants(Path classFile) throws IOException {
        try (InputStream input = Files.newInputStream(classFile);
             DataInputStream data = new DataInputStream(input)) {
            int magic = data.readInt();
            if (magic != JAVA_CLASS_MAGIC) {
                throw new IOException("Not a Java class file: " + classFile);
            }
            data.readUnsignedShort();
            data.readUnsignedShort();
            int constantPoolCount = data.readUnsignedShort();
            List<String> constants = new ArrayList<>();
            for (int index = 1; index < constantPoolCount; index++) {
                int tag = data.readUnsignedByte();
                switch (tag) {
                    case 1 -> constants.add(data.readUTF());
                    case 3, 4 -> data.readInt();
                    case 5, 6 -> {
                        data.readLong();
                        index++;
                    }
                    case 7, 8, 16, 19, 20 -> data.readUnsignedShort();
                    case 9, 10, 11, 12, 17, 18 -> {
                        data.readUnsignedShort();
                        data.readUnsignedShort();
                    }
                    case 15 -> {
                        data.readUnsignedByte();
                        data.readUnsignedShort();
                    }
                    default -> throw new IOException("Unsupported constant pool tag " + tag + " in " + classFile);
                }
            }
            return constants;
        }
    }

    public record BytecodeInspectionResult(String classFile, boolean passed, List<String> violations) {
    }
}
