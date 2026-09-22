package de.pianoman911.mapengine.core.colors.dithering;

import de.pianoman911.mapengine.api.util.ColorBuffer;
import de.pianoman911.mapengine.api.util.FullSpacedColorBuffer;
import de.pianoman911.mapengine.core.colors.ColorPalette;

public class FloydSteinbergDithering {

    // Floyd-Steinberg error diffusion matrix
    private static final float FS_ERROR = 7f / 16f;
    private static final float FS_ERROR2 = 1f / 16f;
    private static final float FS_ERROR3 = 5f / 16f;
    private static final float FS_ERROR4 = 3f / 16f;

    /**
     * My own implementation of Floyd-Steinberg dithering algorithm, to convert a FullSpacedColorBuffer (24Bit Colors) to a ColorBuffer (Minecraft Colors).
     * It's not an accurate implementation, so it corrects the errors at the end.
     * On the other hand, it's extremely fast.
     *
     * @param buffer  The FullSpacedColorBuffer to dither
     * @param palette The ColorPalette to use
     * @param threads retained for API compatibility; error diffusion is processed sequentially
     * @return The dithered ColorBuffer
     */
    @SuppressWarnings("Duplicates") // It's duplicated, but it's faster than using a method
    public static ColorBuffer dither(FullSpacedColorBuffer buffer, ColorPalette palette, int threads) {
        // Error diffusion is sequential by definition. Splitting rows between
        // workers lets one worker read rows while another worker is still
        // changing them, which produces nondeterministic color noise.
        int[] src = buffer.buffer().clone();
        int w = buffer.width();
        int h = buffer.height();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int index = x + y * w;
                int rgb = src[index];

                if (((rgb >> 24) & 0xFF) < 128) {
                    src[index] = 0;
                    continue;
                }

                int oldR = (rgb >> 16) & 0xFF;
                int oldG = (rgb >> 8) & 0xFF;
                int oldB = (rgb) & 0xFF;

                int mc = palette.closestColor(rgb);

                int a;
                int r = (mc >> 16) & 0xFF;
                int g = (mc >> 8) & 0xFF;
                int b = (mc) & 0xFF;

                int errorR = oldR - r;
                int errorG = oldG - g;
                int errorB = oldB - b;

                src[index] = mc;

                if (!(x == w - 1)) {
                    index = x + 1 + y * w;
                    rgb = src[index];
                    a = (rgb >> 24) & 0xFF;
                    r = Math.max(0, Math.min(255, (int) (((rgb >> 16) & 0xFF) + (errorR * FS_ERROR))));
                    g = Math.max(0, Math.min(255, (int) (((rgb >> 8) & 0xFF) + (errorG * FS_ERROR))));
                    b = Math.max(0, Math.min(255, (int) (((rgb) & 0xFF) + (errorB * FS_ERROR))));
                    src[index] = (a << 24) | (r << 16) | (g << 8) | b;

                    if (!(y == h - 1)) {
                        index = x + 1 + (y + 1) * w;
                        rgb = src[index];
                        a = (rgb >> 24) & 0xFF;
                        r = Math.max(0, Math.min(255, (int) (((rgb >> 16) & 0xFF) + (errorR * FS_ERROR2))));
                        g = Math.max(0, Math.min(255, (int) (((rgb >> 8) & 0xFF) + (errorG * FS_ERROR2))));
                        b = Math.max(0, Math.min(255, (int) (((rgb) & 0xFF) + (errorB * FS_ERROR2))));
                        src[index] = (a << 24) | (r << 16) | (g << 8) | b;
                    }
                }

                if (!(y == h - 1)) {
                    index = x + (y + 1) * w;
                    rgb = src[index];
                    a = (rgb >> 24) & 0xFF;
                    r = Math.max(0, Math.min(255, (int) (((rgb >> 16) & 0xFF) + (errorR * FS_ERROR3))));
                    g = Math.max(0, Math.min(255, (int) (((rgb >> 8) & 0xFF) + (errorG * FS_ERROR3))));
                    b = Math.max(0, Math.min(255, (int) (((rgb) & 0xFF) + (errorB * FS_ERROR3))));
                    src[index] = (a << 24) | (r << 16) | (g << 8) | b;

                    if (!(x == 0)) {
                        index = x - 1 + (y + 1) * w;
                        rgb = src[index];
                        a = (rgb >> 24) & 0xFF;
                        r = Math.max(0, Math.min(255, (int) (((rgb >> 16) & 0xFF) + (errorR * FS_ERROR4))));
                        g = Math.max(0, Math.min(255, (int) (((rgb >> 8) & 0xFF) + (errorG * FS_ERROR4))));
                        b = Math.max(0, Math.min(255, (int) (((rgb) & 0xFF) + (errorB * FS_ERROR4))));
                        src[index] = (a << 24) | (r << 16) | (g << 8) | b;
                    }
                }
            }
        }

        return new ColorBuffer(palette.colors(src), buffer.width(), buffer.height());
    }
}
