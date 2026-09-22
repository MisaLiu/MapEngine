package de.pianoman911.mapengine.core.colors;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import de.pianoman911.mapengine.api.colors.IMapColors;
import de.pianoman911.mapengine.api.util.ColorBuffer;
import de.pianoman911.mapengine.api.util.Converter;
import de.pianoman911.mapengine.api.util.FullSpacedColorBuffer;
import de.pianoman911.mapengine.core.MapEnginePlugin;
import de.pianoman911.mapengine.core.colors.dithering.FloydSteinbergDithering;
import de.pianoman911.mapengine.core.util.MapUtil;
import org.bukkit.Bukkit;
import org.bukkit.map.MapPalette;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class ColorPalette implements IMapColors {

    protected final CompletableFuture<ColorPalette> loadFuture = new CompletableFuture<>(); // Used for run code after the palette is loaded
    private final MapEnginePlugin plugin;
    private final File file;
    protected byte[] colors;
    protected byte[] available;
    protected int[] rgb;
    protected int[] reverseColors;
    protected int retries = 0;

    public ColorPalette(MapEnginePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "colors.bin");
        this.load();
    }

    @Override
    public final byte color(Color color) {
        return this.color(color.getRGB());
    }

    @Override
    public final byte color(final int rgb) {
        if (((rgb >> 24) & 0xFF) < 128) {
            return 0;
        }
        return this.colors[rgb & 0xFFFFFF];
    }

    public byte[] colors(int[] rgb, int threads) {
        byte[] result = new byte[rgb.length];
        CompletableFuture<?>[] futures = new CompletableFuture[threads];
        int size = rgb.length / threads;
        for (int i = 0; i < threads; i++) {
            int finalI = i;
            futures[i] = CompletableFuture.runAsync(() -> {
                int start = finalI * size;
                int end = (finalI + 1) * size;
                if (finalI == threads - 1) end = rgb.length;
                for (int j = start; j < end; j++) {
                    int color = rgb[j];
                    if (((color >> 24) & 0xFF) < 128) continue;
                    result[j] = this.color(rgb[j]);
                }
            });
        }
        CompletableFuture.allOf(futures).join();
        return result;
    }

    @Override
    public byte[] convertImage(BufferedImage image) {
        int[] rgb = new int[image.getWidth() * image.getHeight()];
        image.getRGB(0, 0, image.getWidth(), image.getHeight(), rgb, 0, image.getWidth());
        return this.colors(rgb);
    }

    @Override
    public FullSpacedColorBuffer adjustColors(FullSpacedColorBuffer buffer, Converter converter) {
        return switch (converter) {
            case DIRECT -> {
                ColorBuffer mc = this.convertDirect(buffer);
                yield new FullSpacedColorBuffer(this.plugin.colorPalette().toRGBs(mc.data()), mc.width(), mc.height());
            }
            case FLOYD_STEINBERG -> {
                ColorBuffer mc = FloydSteinbergDithering.dither(buffer, this.plugin.colorPalette(), buffer.height() / MapUtil.MAP_HEIGHT + 1);
                yield new FullSpacedColorBuffer(this.plugin.colorPalette().toRGBs(mc.data()), mc.width(), mc.height());
            }
        };
    }

    @Override
    public final Color toColor(byte color) {
        return new Color(this.toRGB(color));
    }

    @Override
    public final byte color(int r, int g, int b) {
        return this.colors[this.dataIndex(r, g, b)];
    }

    private final int dataIndex(int r, int g, int b) {
        return ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | ((b & 0xFF));
    }

    @SuppressWarnings("deprecation")
    private void generateColors() {
        // the initialization of bukkit's built-in color cache isn't thread-safe,
        // so force-initialize the cache once synchronously to prevent errors
        MapPalette.matchColor(0, 0, 0);

        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            this.plugin.getLogger().info("Generating palette...");

            // build into locals and publish only once complete, so a concurrent
            // render can never observe a half-filled palette
            byte[] colors = new byte[256 * 256 * 256];
            int[] reverseColors = new int[256 * 256 * 256];
            long start = System.currentTimeMillis();
            long last = start;

            Set<Byte> usedColors = new HashSet<>();
            for (int red = 0; red < 256; red++) {
                for (int green = 0; green < 256; green++) {
                    for (int blue = 0; blue < 256; blue++) {
                        byte color = MapPalette.matchColor(red, green, blue);
                        int index = this.dataIndex(red, green, blue);

                        colors[index] = color;
                        reverseColors[index] = MapPalette.getColor(color).getRGB();
                        usedColors.add(color);
                    }
                }

                if (last + 250 < System.currentTimeMillis() || red == 255) {
                    this.plugin.getLogger().info("Generating palette... " + String.format("%.2f", (red * 100 / 255.0)) + "%");
                    last = System.currentTimeMillis();
                }
            }

            byte[] available = new byte[usedColors.size()];
            int[] rgb = new int[256];

            int i = 0;
            for (byte color : usedColors) {
                available[i++] = color;
                rgb[color >= 0 ? color : color + 256] = MapPalette.getColor(color).getRGB();
            }

            this.colors = colors;
            this.reverseColors = reverseColors;
            this.available = available;
            this.rgb = rgb;

            this.plugin.getLogger().info("Palette generated! Took " + (System.currentTimeMillis() - start) + "ms");
            this.save();

            if (this.checkValidity()) {
                this.plugin.getLogger().info("Color palette is valid!" + (this.retries > 0 ? " (Retried " + this.retries + " times)" : ""));
                this.loadFuture.complete(this);
                return;
            }

            this.plugin.getLogger().warning("Color palette is invalid!" + (this.retries > 0 ? " (Retried " + this.retries + " times)" : ""));
            if (this.retries < 10) {
                this.retries++;
                this.plugin.getLogger().warning("Retrying... (" + this.retries + "/10)");
                this.generateColors();
            } else {
                this.plugin.getLogger().warning("Failed to load color palette!");
                this.loadFuture.completeExceptionally(new IllegalStateException("Failed to generate color palette"));
                Bukkit.getPluginManager().disablePlugin(this.plugin);
            }
        });
    }

    @SuppressWarnings({"UnstableApiUsage", "deprecation"})
    private void save() {
        this.plugin.getLogger().info("Saving palette...");
        ByteArrayDataOutput output = ByteStreams.newDataOutput();

        output.writeInt(Bukkit.getUnsafe().getDataVersion());
        output.writeInt(this.colors.length);

        for (byte color : this.colors) {
            output.writeByte(color);
        }

        try (FileOutputStream fileOutputStream = new FileOutputStream(this.file); GZIPOutputStream gzip = new GZIPOutputStream(fileOutputStream)) {
            gzip.write(output.toByteArray());
        } catch (IOException exception) {
            throw new RuntimeException(exception);
        }

        this.plugin.getLogger().info("Palette saved!");
    }

    @SuppressWarnings({"UnstableApiUsage", "deprecation"})
    private void load() {
        if (!this.file.exists()) {
            this.file.getParentFile().mkdir();
            this.generateColors();
            return;
        }

        try (FileInputStream fileInput = new FileInputStream(this.file);
             GZIPInputStream gzipInput = new GZIPInputStream(fileInput)) {
            ByteArrayDataInput input = ByteStreams.newDataInput(gzipInput.readAllBytes());

            if (input.readInt() == Bukkit.getUnsafe().getDataVersion()) {
                Set<Byte> usedColors = new HashSet<>();
                this.colors = new byte[input.readInt()];
                this.reverseColors = new int[this.colors.length];

                for (int i = 0; i < this.colors.length; i++) {
                    this.colors[i] = input.readByte();
                    this.reverseColors[i] = MapPalette.getColor(this.colors[i]).getRGB();
                    usedColors.add(this.colors[i]);
                }

                this.available = new byte[usedColors.size()];
                this.rgb = new int[256];

                int i = 0;
                for (Byte color : usedColors) {
                    this.available[i++] = color;
                    this.rgb[color >= 0 ? color : color + 256] = MapPalette.getColor(color).getRGB();
                }

                if (!this.checkValidity()) {
                    this.plugin.getLogger().warning("Incompatible color palette saved, regenerating...");
                    this.generateColors();
                    return;
                }

                this.plugin.getLogger().info("Loaded color palette");
                this.loadFuture.complete(this);
                return;
            }
        } catch (IOException exception) {
            throw new RuntimeException(exception);
        }

        this.plugin.getLogger().info("Incompatible color palette saved");
        this.generateColors();
    }

    @Override
    public final int toRGB(byte color) {
        return this.rgb[color >= 0 ? color : color + 256];
    }

    @Override
    public final int[] toRGBs(byte[] colors) {
        int[] rgb = new int[colors.length];
        for (int i = 0; i < colors.length; i++) {
            rgb[i] = this.toRGB(colors[i]);
        }
        return rgb;
    }

    public final int closestColor(final int rgb) {
        final int alpha = (rgb >> 24) & 0xFF;
        if (alpha < 128) {
            return 0;
        }
        final int ret = this.reverseColors[rgb & 0xFFFFFF];
        return (ret & 0xFFFFFF) | (alpha << 24);
    }

    @SuppressWarnings("deprecation") // magic value
    private boolean checkValidity() {
        boolean valid = true;
        for (int i = 0; i < 256; i++) {
            byte color = (byte) i;
            int engine = this.toRGB(color);
            int bukkit = MapPalette.getColor(color).getRGB();

            if (engine != bukkit) {
                this.plugin.getLogger().warning("Color " + color + " is invalid! MapEngine: " + engine + " Bukkit: " + bukkit);
                valid = false;
            }
        }
        return valid;
    }
}
