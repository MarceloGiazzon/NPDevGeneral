package com.npdev.dsl.v1.resolution;

import com.npdev.dsl.v1.ast.ModelAst;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public record ResolvedModel(
        ModelAst modelAst,
        String canonicalJson,
        String deterministicHashSha256
) {
    public ResolvedModel {
        modelAst = Objects.requireNonNull(modelAst, "modelAst");
        canonicalJson = Objects.requireNonNull(canonicalJson, "canonicalJson");
        deterministicHashSha256 = Objects.requireNonNull(deterministicHashSha256, "deterministicHashSha256");
    }

    public static ResolvedModel from(ModelAst resolvedAst) {
        String canonicalJson = ResolvedModelCanonicalJson.toJson(resolvedAst);
        return new ResolvedModel(resolvedAst, canonicalJson, sha256(canonicalJson));
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
