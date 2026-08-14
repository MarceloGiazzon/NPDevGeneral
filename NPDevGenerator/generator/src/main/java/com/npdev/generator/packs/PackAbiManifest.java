package com.npdev.generator.packs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * BT-2 (PACK-ROADMAP.md, Track B): the small manifest a sealed pack jar carries declaring what it is
 * and what kernel ABI it was built against -- {@code META-INF/npdev-pack.properties} inside the jar
 * (the same {@code META-INF} convention every other jar-level manifest uses, not a new mechanism).
 *
 * @param packId           the pack's own real id (never a consuming app's local alias -- same PK-2
 *                          discipline: physical identity comes from the pack, not from who imports it)
 * @param packVersion       the full semver version string this jar was sealed from (e.g. "1.0.0")
 * @param packMajorVersion  parsed major version -- also the version segment in the emitted package
 *                          name ({@code com.npdev.pack.<packId>.v<packMajorVersion>})
 * @param kernelAbiVersion  {@code KernelAbi.CURRENT_ABI_VERSION} at the moment this jar was sealed
 */
public record PackAbiManifest(
        String packId,
        String packVersion,
        int packMajorVersion,
        String kernelAbiVersion
) {

    private static final String KEY_PACK_ID = "packId";
    private static final String KEY_PACK_VERSION = "packVersion";
    private static final String KEY_PACK_MAJOR_VERSION = "packMajorVersion";
    private static final String KEY_KERNEL_ABI_VERSION = "kernelAbiVersion";

    public PackAbiManifest {
        if (packId == null || packId.isBlank()) {
            throw new IllegalArgumentException("packId must not be blank");
        }
        if (packVersion == null || packVersion.isBlank()) {
            throw new IllegalArgumentException("packVersion must not be blank");
        }
        if (packMajorVersion < 0) {
            throw new IllegalArgumentException("packMajorVersion must not be negative");
        }
        if (kernelAbiVersion == null || kernelAbiVersion.isBlank()) {
            throw new IllegalArgumentException("kernelAbiVersion must not be blank");
        }
    }

    /** {@code com.npdev.pack.<packId>.v<packMajorVersion>} -- the pack's own, alias-independent Java
     *  namespace (BT-2 step 2), never the consuming app's package. */
    public String packageName() {
        return "com.npdev.pack." + packId + ".v" + packMajorVersion;
    }

    public Properties toProperties() {
        Properties properties = new Properties();
        properties.setProperty(KEY_PACK_ID, packId);
        properties.setProperty(KEY_PACK_VERSION, packVersion);
        properties.setProperty(KEY_PACK_MAJOR_VERSION, Integer.toString(packMajorVersion));
        properties.setProperty(KEY_KERNEL_ABI_VERSION, kernelAbiVersion);
        return properties;
    }

    public void writeTo(OutputStream out) {
        try {
            toProperties().store(out, "BT-2 sealed pack manifest -- generated, do not hand-edit");
        } catch (IOException writeError) {
            throw new UncheckedIOException(writeError);
        }
    }

    public static PackAbiManifest readFrom(InputStream in) {
        Properties properties = new Properties();
        try {
            properties.load(in);
        } catch (IOException readError) {
            throw new UncheckedIOException(readError);
        }
        String packId = requireProperty(properties, KEY_PACK_ID);
        String packVersion = requireProperty(properties, KEY_PACK_VERSION);
        int packMajorVersion = Integer.parseInt(requireProperty(properties, KEY_PACK_MAJOR_VERSION));
        String kernelAbiVersion = requireProperty(properties, KEY_KERNEL_ABI_VERSION);
        return new PackAbiManifest(packId, packVersion, packMajorVersion, kernelAbiVersion);
    }

    private static String requireProperty(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("sealed pack manifest is missing required property '" + key + "'");
        }
        return value;
    }
}
