package com.npdev.generator.emitters;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * FORM P5-T7: the home-screen icon of an installable experience -- a full-bleed square in the experience's accent
 * colour (full-bleed so a launcher may mask it to any shape: the same file serves the {@code any} and the
 * {@code maskable} icon purpose) with a soft disc and the first letter of the experience in the middle.
 *
 * <p>Byte-for-byte deterministic, which the generator determinism check needs, and independent of the machine: no
 * fonts and no Graphics2D (a headless Linux JDK without fontconfig fails on the first one), only pixel arithmetic
 * over a built-in 5x7 letter table, one fixed anti-aliasing rule for the disc, and the JDK's PNG writer over an
 * in-memory stream (no temp file, no timestamp or other metadata chunk).
 */
final class ExperienceIcons {

    private static final String[] GLYPH_ROWS = {
            "A 01110 10001 10001 11111 10001 10001 10001", "B 11110 10001 10001 11110 10001 10001 11110",
            "C 01110 10001 10000 10000 10000 10001 01110", "D 11110 10001 10001 10001 10001 10001 11110",
            "E 11111 10000 10000 11110 10000 10000 11111", "F 11111 10000 10000 11110 10000 10000 10000",
            "G 01110 10001 10000 10111 10001 10001 01111", "H 10001 10001 10001 11111 10001 10001 10001",
            "I 01110 00100 00100 00100 00100 00100 01110", "J 00111 00010 00010 00010 00010 10010 01100",
            "K 10001 10010 10100 11000 10100 10010 10001", "L 10000 10000 10000 10000 10000 10000 11111",
            "M 10001 11011 10101 10101 10001 10001 10001", "N 10001 11001 10101 10011 10001 10001 10001",
            "O 01110 10001 10001 10001 10001 10001 01110", "P 11110 10001 10001 11110 10000 10000 10000",
            "Q 01110 10001 10001 10001 10101 10010 01101", "R 11110 10001 10001 11110 10100 10010 10001",
            "S 01111 10000 10000 01110 00001 00001 11110", "T 11111 00100 00100 00100 00100 00100 00100",
            "U 10001 10001 10001 10001 10001 10001 01110", "V 10001 10001 10001 10001 10001 01010 00100",
            "W 10001 10001 10001 10101 10101 10101 01010", "X 10001 10001 01010 00100 01010 10001 10001",
            "Y 10001 10001 01010 00100 00100 00100 00100", "Z 11111 00001 00010 00100 01000 10000 11111",
            "0 01110 10001 10011 10101 11001 10001 01110", "1 00100 01100 00100 00100 00100 00100 01110",
            "2 01110 10001 00001 00010 00100 01000 11111", "3 11110 00001 00001 01110 00001 00001 11110",
            "4 00010 00110 01010 10010 11111 00010 00010", "5 11111 10000 11110 00001 00001 10001 01110",
            "6 00110 01000 10000 11110 10001 10001 01110", "7 11111 00001 00010 00100 01000 01000 01000",
            "8 01110 10001 10001 01110 10001 10001 01110", "9 01110 10001 10001 01111 00001 00010 01100"
    };
    private static final Map<Character, String[]> GLYPHS = new HashMap<>();

    static {
        for (String line : GLYPH_ROWS) {
            String[] parts = line.split(" ");
            String[] rows = new String[7];
            System.arraycopy(parts, 1, rows, 0, 7);
            GLYPHS.put(parts[0].charAt(0), rows);
        }
    }

    private ExperienceIcons() {
    }

    /** The first ASCII letter or digit of {@code text} (accents folded), upper-cased; {@code '?'} when there is none. */
    static char initial(String text) {
        String folded = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD);
        for (int i = 0; i < folded.length(); i++) {
            char c = Character.toUpperCase(folded.charAt(i));
            if (GLYPHS.containsKey(c)) {
                return c;
            }
        }
        return '?';
    }

    /** {@code accentRgb} is 0xRRGGBB. A square PNG of {@code size} pixels. */
    static byte[] png(char letter, int size, int accentRgb) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        int[] disc = blend(accentRgb, 0xFFFFFF, 0.18);
        int ink = luminance(accentRgb) < 140 ? 0xFFFFFF : 0x0B1220;
        double center = (size - 1) / 2.0;
        double radius = size * 0.34; // inside the 80% safe zone of a maskable icon
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double distance = Math.sqrt((x - center) * (x - center) + (y - center) * (y - center));
                double cover = Math.max(0.0, Math.min(1.0, radius - distance + 0.5));
                image.setRGB(x, y, mix(accentRgb, pack(disc), cover));
            }
        }
        String[] rows = GLYPHS.getOrDefault(letter, GLYPHS.get('N'));
        int cell = Math.max(1, (int) (size * 0.36 / 7));
        int left = Math.round((size - 5 * cell) / 2f);
        int top = Math.round((size - 7 * cell) / 2f);
        for (int r = 0; r < 7; r++) {
            for (int c = 0; c < 5; c++) {
                if (rows[r].charAt(c) == '1') {
                    for (int dy = 0; dy < cell; dy++) {
                        for (int dx = 0; dx < cell; dx++) {
                            image.setRGB(left + c * cell + dx, top + r * cell + dy, ink);
                        }
                    }
                }
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
            if (!ImageIO.write(image, "png", stream)) {
                throw new IllegalStateException("No PNG writer available");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed encoding the experience icon", e);
        }
        return out.toByteArray();
    }

    private static int[] blend(int from, int to, double amount) {
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            int shift = 16 - 8 * i;
            double a = (from >> shift) & 0xFF;
            double b = (to >> shift) & 0xFF;
            out[i] = (int) Math.round(a + (b - a) * amount);
        }
        return out;
    }

    private static int pack(int[] rgb) {
        return (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
    }

    private static int mix(int under, int over, double cover) {
        int[] a = blend(under, over, cover);
        return pack(a);
    }

    private static int luminance(int rgb) {
        return (int) Math.round(0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF));
    }
}
