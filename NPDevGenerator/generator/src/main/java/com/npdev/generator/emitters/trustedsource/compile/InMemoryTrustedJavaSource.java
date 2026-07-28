package com.npdev.generator.emitters.trustedsource.compile;

import java.net.URI;
import javax.tools.SimpleJavaFileObject;

/**
 * In-memory {@code JavaFileObject} wrapping a trusted-source Java string so the platform
 * compiler's {@code JavacTask} can parse it without touching disk.
 *
 * <p>Split out of {@code TrustedSourceEmitter} (2.B.2) -- self-contained.
 */
public final class InMemoryTrustedJavaSource extends SimpleJavaFileObject {
    private final String source;

    public InMemoryTrustedJavaSource(String relativePath, String source) {
        super(URI.create("string:///" + relativePath.replace('\\', '/')), Kind.SOURCE);
        this.source = source;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        return source;
    }
}
