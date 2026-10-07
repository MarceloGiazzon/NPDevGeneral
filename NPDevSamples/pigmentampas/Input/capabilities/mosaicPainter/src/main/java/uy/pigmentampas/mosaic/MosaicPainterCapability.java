package uy.pigmentampas.mosaic;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pigmentampas P5 -- the "photo becomes a mosaic" escape hatch: ordinary Java beside the model
 * ({@code customCapabilities}), compiled into the generated app and called from a procedure.
 *
 * <p>It never touches files, the database or the network (the SEC-3 sandbox forbids it, and it does
 * not need to): the procedure reads the photo through {@code fileStore.readImage} (a {@code data:}
 * URI), the approved-cap palette and the collection through queries, and passes them in. Args arrive
 * in ALPHABETICAL key order of the procedure step's {@code args} object.
 *
 * <p>Deliberately written without lambdas or streams -- the plugin bytecode gate refuses
 * {@code LambdaMetafactory}.
 */
public final class MosaicPainterCapability {

    private static final int SAMPLES_PER_AXIS = 5;

    /**
     * {@code args: { input, owned, palette, photo }} -> {@code { cells: [{row, col, capId}] }}.
     *
     * @param input   the open Mosaic (rows, cols, layout, ownerUsername, onlyOwnedCaps)
     * @param owned   collection rows {@code {capId, ownerUsername, quantity}} the caller can read
     * @param palette approved caps {@code {id, color}} ({@code color} = {@code #RRGGBB})
     * @param photo   {@code fileStore.readImage} result ({@code dataUri})
     */
    public Map<String, Object> paintFromPhoto(Map<String, Object> input, List<Object> owned,
                                              List<Object> palette, Map<String, Object> photo) {
        int rows = intValue(input.get("rows"), 20);
        int cols = intValue(input.get("cols"), 33);
        if (rows < 1 || cols < 1 || rows * cols > 10000) {
            throw new IllegalArgumentException("Board must be between 1x1 and 10000 cells (got " + rows + "x" + cols + ").");
        }
        boolean hex = !"SQUARE".equalsIgnoreCase(String.valueOf(input.get("layout")));
        boolean onlyOwned = Boolean.TRUE.equals(input.get("onlyOwnedCaps"))
                || "true".equalsIgnoreCase(String.valueOf(input.get("onlyOwnedCaps")));

        Set<String> ownedCapIds = null;
        if (onlyOwned) {
            ownedCapIds = new HashSet<>();
            String owner = String.valueOf(input.get("ownerUsername"));
            for (Object row : owned == null ? List.of() : owned) {
                if (row instanceof Map<?, ?> item && owner.equals(String.valueOf(item.get("ownerUsername")))
                        && intValue(item.get("quantity"), 0) > 0 && item.get("capId") != null) {
                    ownedCapIds.add(String.valueOf(item.get("capId")));
                }
            }
        }

        List<String> capIds = new ArrayList<>();
        List<double[]> capLabs = new ArrayList<>();
        for (Object entry : palette == null ? List.of() : palette) {
            if (!(entry instanceof Map<?, ?> cap) || cap.get("id") == null) {
                continue;
            }
            String id = String.valueOf(cap.get("id"));
            int rgb = parseHex(cap.get("color"));
            if (rgb < 0 || (ownedCapIds != null && !ownedCapIds.contains(id))) {
                continue;
            }
            capIds.add(id);
            capLabs.add(lab(rgb));
        }
        if (capIds.isEmpty()) {
            throw new IllegalArgumentException(onlyOwned
                    ? "None of your collection's caps is approved with a dominant colour yet -- untick 'only caps I own' or add caps to your collection."
                    : "No approved cap has a dominant colour yet -- set dominantColor on the catalogue first.");
        }

        BufferedImage image = decode(photo);
        // Board geometry in cell units: odd hex rows shift half a cell right, and hex rows pack at
        // sqrt(3)/2 of a cell's width. The photo is centre-cropped ("cover") to the board's aspect so
        // the picture keeps its proportions instead of being stretched.
        double boardW = cols + (hex && rows > 1 ? 0.5 : 0.0);
        double rowPitch = hex ? Math.sqrt(3.0) / 2.0 : 1.0;
        double boardH = (rows - 1) * rowPitch + 1.0;
        double imgW = image.getWidth();
        double imgH = image.getHeight();
        double scale = Math.max(boardW / imgW, boardH / imgH);
        double cropW = boardW / scale;
        double cropH = boardH / scale;
        double offX = (imgW - cropW) / 2.0;
        double offY = (imgH - cropH) / 2.0;
        double cellPx = 1.0 / scale;

        Map<Integer, Integer> memo = new HashMap<>();
        List<Map<String, Object>> cells = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            double cy = offY + (r * rowPitch + 0.5) * cellPx;
            double shift = hex && (r % 2 == 1) ? 0.5 : 0.0;
            for (int c = 0; c < cols; c++) {
                double cx = offX + (c + 0.5 + shift) * cellPx;
                int rgb = averageAround(image, cx, cy, cellPx * 0.45);
                Integer best = memo.get(rgb);
                if (best == null) {
                    best = nearest(lab(rgb), capLabs);
                    memo.put(rgb, best);
                }
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("row", r);
                cell.put("col", c);
                cell.put("capId", capIds.get(best));
                cells.add(cell);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cells", cells);
        return out;
    }

    /**
     * {@code args: { photo }} -> {@code { dominantColor, accentColor }} as {@code #RRGGBB}. Samples the
     * centred disc (a cap is round; the corners are table or background), skips transparent pixels,
     * buckets colours at 4 bits per channel, and takes the most frequent bucket's mean as dominant and
     * the most frequent bucket that is visibly different (CIELAB distance > 25) as accent.
     */
    public Map<String, Object> capColors(Map<String, Object> photo) {
        BufferedImage image = decode(photo);
        int w = image.getWidth();
        int h = image.getHeight();
        double cx = w / 2.0;
        double cy = h / 2.0;
        double radius = Math.min(w, h) * 0.46;
        int step = Math.max(1, (int) Math.floor(Math.min(w, h) / 160.0));
        Map<Integer, long[]> buckets = new HashMap<>();
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                double dx = x + 0.5 - cx;
                double dy = y + 0.5 - cy;
                if (dx * dx + dy * dy > radius * radius) {
                    continue;
                }
                int argb = image.getRGB(x, y);
                if (((argb >>> 24) & 0xFF) < 128) {
                    continue;
                }
                int red = (argb >> 16) & 0xFF;
                int green = (argb >> 8) & 0xFF;
                int blue = argb & 0xFF;
                int key = ((red >> 4) << 8) | ((green >> 4) << 4) | (blue >> 4);
                long[] sum = buckets.get(key);
                if (sum == null) {
                    sum = new long[4];
                    buckets.put(key, sum);
                }
                sum[0]++;
                sum[1] += red;
                sum[2] += green;
                sum[3] += blue;
            }
        }
        if (buckets.isEmpty()) {
            throw new IllegalArgumentException("The cap image has no opaque pixels to read a colour from.");
        }
        List<long[]> ranked = new ArrayList<>(buckets.values());
        ranked.sort(new java.util.Comparator<long[]>() {
            @Override
            public int compare(long[] a, long[] b) {
                return Long.compare(b[0], a[0]);
            }
        });
        int dominant = meanRgb(ranked.get(0));
        double[] dominantLab = lab(dominant);
        int accent = dominant;
        for (int i = 1; i < ranked.size(); i++) {
            int candidate = meanRgb(ranked.get(i));
            if (distance(dominantLab, lab(candidate)) > 25.0) {
                accent = candidate;
                break;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dominantColor", hex(dominant));
        out.put("accentColor", hex(accent));
        return out;
    }

    // ------------------------------------------------------------------------------------------

    private static BufferedImage decode(Map<String, Object> photo) {
        Object uri = photo == null ? null : photo.get("dataUri");
        String text = uri == null ? "" : String.valueOf(uri);
        int comma = text.indexOf(',');
        if (!text.startsWith("data:") || comma < 0) {
            throw new IllegalArgumentException("Expected the photo as a data: URI (fileStore.readImage result).");
        }
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(text.substring(comma + 1))));
        } catch (Exception exception) {
            throw new IllegalArgumentException("The photo could not be decoded: " + exception.getMessage());
        }
        if (image == null) {
            throw new IllegalArgumentException("The photo's format is not readable here (use PNG, JPEG or GIF).");
        }
        return image;
    }

    /** Mean colour of a SAMPLES_PER_AXIS^2 grid inside the square of half-size {@code half} around (cx, cy). */
    private static int averageAround(BufferedImage image, double cx, double cy, double half) {
        long red = 0;
        long green = 0;
        long blue = 0;
        int n = 0;
        int maxX = image.getWidth() - 1;
        int maxY = image.getHeight() - 1;
        for (int i = 0; i < SAMPLES_PER_AXIS; i++) {
            double fy = cy - half + (2.0 * half) * (i + 0.5) / SAMPLES_PER_AXIS;
            int y = (int) Math.max(0, Math.min(maxY, Math.floor(fy)));
            for (int j = 0; j < SAMPLES_PER_AXIS; j++) {
                double fx = cx - half + (2.0 * half) * (j + 0.5) / SAMPLES_PER_AXIS;
                int x = (int) Math.max(0, Math.min(maxX, Math.floor(fx)));
                int argb = image.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xFF;
                // Transparent areas read as white, the colour of the board's background.
                red += blend((argb >> 16) & 0xFF, alpha);
                green += blend((argb >> 8) & 0xFF, alpha);
                blue += blend(argb & 0xFF, alpha);
                n++;
            }
        }
        // Quantise to 5 bits per channel: keeps the nearest-cap memo small without a visible change.
        int r = (int) (red / n) & 0xF8;
        int g = (int) (green / n) & 0xF8;
        int b = (int) (blue / n) & 0xF8;
        return (r << 16) | (g << 8) | b;
    }

    private static int blend(int channel, int alpha) {
        return (channel * alpha + 255 * (255 - alpha)) / 255;
    }

    private static int nearest(double[] target, List<double[]> candidates) {
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < candidates.size(); i++) {
            double d = distance(target, candidates.get(i));
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    /** CIE76 delta-E: Euclidean distance in CIELAB. */
    private static double distance(double[] a, double[] b) {
        double dl = a[0] - b[0];
        double da = a[1] - b[1];
        double db = a[2] - b[2];
        return Math.sqrt(dl * dl + da * da + db * db);
    }

    /** sRGB (D65) -> CIELAB. */
    static double[] lab(int rgb) {
        double r = linear(((rgb >> 16) & 0xFF) / 255.0);
        double g = linear(((rgb >> 8) & 0xFF) / 255.0);
        double b = linear((rgb & 0xFF) / 255.0);
        double x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047;
        double y = (0.2126729 * r + 0.7151522 * g + 0.0721750 * b);
        double z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883;
        double fx = labF(x);
        double fy = labF(y);
        double fz = labF(z);
        return new double[] {116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz)};
    }

    private static double linear(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double labF(double t) {
        double delta = 6.0 / 29.0;
        return t > delta * delta * delta ? Math.cbrt(t) : t / (3.0 * delta * delta) + 4.0 / 29.0;
    }

    private static int meanRgb(long[] sum) {
        int r = (int) (sum[1] / sum[0]);
        int g = (int) (sum[2] / sum[0]);
        int b = (int) (sum[3] / sum[0]);
        return (r << 16) | (g << 8) | b;
    }

    private static int parseHex(Object value) {
        if (value == null) {
            return -1;
        }
        String text = String.valueOf(value).trim();
        if (text.startsWith("#")) {
            text = text.substring(1);
        }
        if (text.length() != 6) {
            return -1;
        }
        try {
            return Integer.parseInt(text, 16);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static String hex(int rgb) {
        String digits = Integer.toHexString(rgb & 0xFFFFFF).toUpperCase(Locale.ROOT);
        StringBuilder out = new StringBuilder("#");
        for (int i = digits.length(); i < 6; i++) {
            out.append('0');
        }
        return out.append(digits).toString();
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return (int) Math.round(Double.parseDouble(String.valueOf(value).trim()));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
