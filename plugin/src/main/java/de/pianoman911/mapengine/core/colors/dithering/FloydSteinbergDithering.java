package de.pianoman911.mapengine.core.colors.dithering;

import de.pianoman911.mapengine.api.util.ColorBuffer;
import de.pianoman911.mapengine.api.util.FullSpacedColorBuffer;
import de.pianoman911.mapengine.core.colors.ColorPalette;

/**
 * Floyd–Steinberg error diffusion that converts a full-spaced ARGB buffer into
 * Minecraft map color indices.
 * <p>
 * Implementation notes:
 * <ul>
 *   <li>Serpentine (alternating) scan direction reduces worm/checker artifacts.</li>
 *   <li>Accumulated error is kept in float form so fractional error is not
 *       truncated away on every step (integer in-place diffusion leaves
 *       high-frequency residue that reads as salt-and-pepper noise).</li>
 *   <li>Quantized pixels are written straight as map color bytes — no
 *       {@code toRGB → matchColor} round trip after the pass.</li>
 *   <li>Chroma (color) error is diffused at a reduced gain relative to the
 *       brightness error. {@code MapPalette.matchColor} selects nearest colors
 *       with a luminance-weighted metric while the error lives in plain sRGB;
 *       diffusing full chroma against that mismatch turns pastel gradients into
 *       color speckles.</li>
 * </ul>
 */
public final class FloydSteinbergDithering {

    // Floyd-Steinberg error diffusion matrix (right, down-left, down, down-right)
    private static final float FS_RIGHT = 7f / 16f;
    private static final float FS_DOWN_LEFT = 3f / 16f;
    private static final float FS_DOWN = 5f / 16f;
    private static final float FS_DOWN_RIGHT = 1f / 16f;

    /**
     * Gain applied to the chroma part of the diffusion error (0..1).
     * 1 = classic FS in sRGB; lower values suppress color speckling while the
     * mean (brightness) error still diffuses at full strength.
     */
    private static final float CHROMA_GAIN = 0.45f;

    private FloydSteinbergDithering() {
    }

    /**
     * @param buffer  full-spaced ARGB source (not mutated)
     * @param palette color palette used for nearest-color lookup
     * @param threads retained for API compatibility; diffusion is sequential
     * @return map color byte buffer
     */
    public static ColorBuffer dither(FullSpacedColorBuffer buffer, ColorPalette palette, int threads) {
        palette.ensureLoaded();

        int width = buffer.width();
        int height = buffer.height();
        int[] source = buffer.buffer();
        int size = width * height;

        float[] workR = new float[size];
        float[] workG = new float[size];
        float[] workB = new float[size];
        boolean[] opaque = new boolean[size];
        for (int i = 0; i < size; i++) {
            int argb = source[i];
            opaque[i] = ((argb >> 24) & 0xFF) >= 128;
            workR[i] = (argb >> 16) & 0xFF;
            workG[i] = (argb >> 8) & 0xFF;
            workB[i] = argb & 0xFF;
        }

        byte[] out = new byte[size];

        for (int y = 0; y < height; y++) {
            boolean reverse = (y & 1) != 0;
            for (int t = 0; t < width; t++) {
                int x = reverse ? width - 1 - t : t;
                int index = x + y * width;

                if (!opaque[index]) {
                    out[index] = 0;
                    continue;
                }

                int oldR = clamp(workR[index]);
                int oldG = clamp(workG[index]);
                int oldB = clamp(workB[index]);

                byte mapColor = palette.color(oldR, oldG, oldB);
                int quantizedRgb = palette.toRGB(mapColor);
                out[index] = mapColor;

                float errR = oldR - ((quantizedRgb >> 16) & 0xFF);
                float errG = oldG - ((quantizedRgb >> 8) & 0xFF);
                float errB = oldB - (quantizedRgb & 0xFF);

                // Full brightness error, damped color error (see CHROMA_GAIN).
                float mean = (errR + errG + errB) / 3f;
                float diffR = mean + (errR - mean) * CHROMA_GAIN;
                float diffG = mean + (errG - mean) * CHROMA_GAIN;
                float diffB = mean + (errB - mean) * CHROMA_GAIN;

                int step = reverse ? -1 : 1;

                // right (or left when scanning backwards)
                int xNext = x + step;
                if (xNext >= 0 && xNext < width) {
                    diffuse(workR, workG, workB, opaque, xNext + y * width, diffR, diffG, diffB, FS_RIGHT);
                }

                if (y + 1 >= height) {
                    continue;
                }

                // down-left relative to scan direction becomes down-right on reverse rows
                int xDiag = x - step;
                if (xDiag >= 0 && xDiag < width) {
                    diffuse(workR, workG, workB, opaque, xDiag + (y + 1) * width, diffR, diffG, diffB, FS_DOWN_LEFT);
                }
                diffuse(workR, workG, workB, opaque, x + (y + 1) * width, diffR, diffG, diffB, FS_DOWN);
                if (xNext >= 0 && xNext < width) {
                    diffuse(workR, workG, workB, opaque, xNext + (y + 1) * width, diffR, diffG, diffB, FS_DOWN_RIGHT);
                }
            }
        }

        return new ColorBuffer(out, width, height);
    }

    private static void diffuse(float[] workR, float[] workG, float[] workB, boolean[] opaque,
                                int index, float errR, float errG, float errB, float weight) {
        if (!opaque[index]) {
            return;
        }
        workR[index] += errR * weight;
        workG[index] += errG * weight;
        workB[index] += errB * weight;
    }

    private static int clamp(float value) {
        if (value <= 0f) {
            return 0;
        }
        if (value >= 255f) {
            return 255;
        }
        return (int) value;
    }
}
