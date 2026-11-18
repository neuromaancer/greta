import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.IIOImage;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/** Local, timestamped Ogre frames with pixel subtitles; encoding is external. */
public final class ReplayVideo {
    private static final int MAX_FRAMES = 15000;
    private static final int FRAME_MILLIS = 40;
    private final File frames;
    private final File manifest;
    private final boolean fullHD = Boolean.getBoolean("gmit.video.fullHD");
    private final List<String> frameRows = new ArrayList<String>();
    private final List<String> audioRows = new ArrayList<String>();
    private int width, height;
    private long firstAudio = -1, lastAudioEnd = -1, lastFrame = -1, captureEnd = -1;
    private boolean finished, completed;

    public ReplayVideo(File output) throws Exception {
        if (output == null || !output.isDirectory())
            throw new IllegalArgumentException("Video run directory must exist");
        manifest = new File(output, "video-frames.json");
        if (manifest.exists()) throw new IllegalStateException("Refusing to replace video manifest");
        frames = new File(output, "video-frames");
        Files.createDirectory(frames.toPath());
        write(false);
    }

    /** Check before native readback, not merely before PNG encoding. */
    public boolean isDue(long timerMillis) {
        if (timerMillis < 0 || (lastFrame >= 0 && timerMillis < lastFrame))
            throw new IllegalArgumentException("Video clock moved backwards");
        return !finished && (lastFrame < 0 || timerMillis - lastFrame >= FRAME_MILLIS);
    }

    public void frame(int w, int h, byte[] rgb, long timerMillis, String subtitle) throws Exception {
        if (finished) throw new IllegalStateException("Video recording is closed");
        if (!isDue(timerMillis)) return;
        if (w <= 0 || h <= 0 || w > 4096 || h > 4096 || rgb == null
                || (long)w * h * 3 != rgb.length)
            throw new IllegalArgumentException("Invalid Ogre RGB frame");
        if (width != 0 && (w != width || h != height))
            throw new IllegalArgumentException("Video dimensions changed during capture");
        if (frameRows.size() >= MAX_FRAMES)
            throw new IllegalStateException("Video frame limit exceeded");

        String name = String.format(Locale.ROOT, "frame-%06d.%s", frameRows.size() + 1, fullHD ? "ppm" : "png");
        File file = new File(frames, name);
        if (fullHD) {
            writePpm(file, w, h, rgb, subtitle);
        } else {
            BufferedImage image = sceneImage(w, h, rgb);
            draw(image, subtitle);
            writeFrame(image, file);
        }
        width = w;
        height = h;
        lastFrame = timerMillis;
        captureEnd = timerMillis;
        frameRows.add("{\"file\":\"video-frames/" + name + "\",\"greta_timer_millis\":" + timerMillis + "}");
        if (frameRows.size() % 20 == 0) write(false);
    }

    // Ogre supplies a fresh packed RGB readback. Draw directly into those
    // bytes so Full HD recording can keep sampling while the actors move.
    static void writePpm(File file, int w, int h, byte[] rgb, String caption) throws Exception {
        if (caption != null && !caption.isEmpty()) {
            java.awt.image.DataBufferByte data = new java.awt.image.DataBufferByte(rgb, rgb.length);
            java.awt.image.WritableRaster raster = java.awt.image.Raster.createInterleavedRaster(
                    data, w, h, 3 * w, 3, new int[]{0, 1, 2}, null);
            java.awt.image.ColorModel colors = new java.awt.image.ComponentColorModel(
                    java.awt.color.ColorSpace.getInstance(java.awt.color.ColorSpace.CS_sRGB),
                    false, false, java.awt.Transparency.OPAQUE, java.awt.image.DataBuffer.TYPE_BYTE);
            draw(new BufferedImage(colors, raster, false, null), caption);
        }
        try (java.io.OutputStream out = Files.newOutputStream(file.toPath())) {
            out.write(("P6\n" + w + " " + h + "\n255\n").getBytes(StandardCharsets.US_ASCII));
            out.write(rgb);
        }
    }

    static BufferedImage sceneImage(int w, int h, byte[] rgb) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] pixels = ((java.awt.image.DataBufferInt)image.getRaster().getDataBuffer()).getData();
        for (int pixel = 0, i = 0; pixel < pixels.length; pixel++, i += 3)
            pixels[pixel] = ((rgb[i] & 255) << 16) | ((rgb[i + 1] & 255) << 8) | (rgb[i + 2] & 255);
        return image;
    }

    static void writeFrame(BufferedImage image, File file) throws Exception {
        java.util.Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
        if (!writers.hasNext()) throw new IllegalStateException("PNG encoder is unavailable");
        ImageWriter writer = writers.next();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(file)) {
            writer.setOutput(output);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            // Java 8's built-in PNG writer chooses its own compression.
            // Newer writers allow a faster, still lossless capture setting.
            if (parameters.canWriteCompressed()) {
                parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                parameters.setCompressionQuality(.85f);
            }
            writer.write(null, new IIOImage(image, null, null), parameters);
        } finally {
            writer.dispose();
        }
    }

    public void audio(String event, String actor, String wav, long start, long end) throws Exception {
        if (finished) throw new IllegalStateException("Video recording is closed");
        if (event == null || event.isEmpty() || wav == null || wav.isEmpty()
                || wav.indexOf('/') >= 0 || wav.indexOf('\\') >= 0
                || !("facilitator".equals(actor) || "patient-a".equals(actor) || "patient-b".equals(actor))
                || start < 0 || end <= start || (lastAudioEnd >= 0 && start < lastAudioEnd))
            throw new IllegalArgumentException("Invalid video audio mapping");
        if (firstAudio < 0) firstAudio = start;
        lastAudioEnd = end;
        audioRows.add("{\"event_id\":\"" + quote(event) + "\",\"actor_id\":\"" + quote(actor)
                + "\",\"file\":\"" + quote(wav) + "\",\"start_greta_timer_millis\":" + start
                + ",\"end_greta_timer_millis\":" + end + "}");
        write(false);
    }

    public void finish(boolean complete, long actualTimerEnd) throws Exception {
        if (finished && (!completed || complete)) return;
        if (actualTimerEnd < 0 || actualTimerEnd < lastFrame
                || (complete && (frameRows.isEmpty() || audioRows.isEmpty()
                    || actualTimerEnd <= lastFrame || actualTimerEnd < lastAudioEnd)))
            throw new IllegalArgumentException("Video end must follow captured frames and audio");
        captureEnd = actualTimerEnd;
        write(complete);
        completed = complete;
        finished = true;
    }

    /** A later exporter failure must not leave the earlier sidecar successful. */
    public static void finishTogether(ReplaySubtitles subtitles, ReplayVideo video,
            boolean complete, long actualTimerEnd) throws Exception {
        try {
            if (subtitles != null) subtitles.finish(complete);
            if (video != null) video.finish(complete, actualTimerEnd);
        } catch (Exception failure) {
            try { if (subtitles != null) subtitles.finish(false); }
            catch (Exception downgradeFailure) { failure.addSuppressed(downgradeFailure); }
            try { if (video != null) video.finish(false, actualTimerEnd); }
            catch (Exception downgradeFailure) { failure.addSuppressed(downgradeFailure); }
            throw failure;
        }
    }

    private static void draw(BufferedImage image, String subtitle) {
        if (subtitle == null || subtitle.isEmpty()) return;
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int pad = Math.max(12, image.getWidth() / 70);
            graphics.setFont(new Font("SansSerif", Font.BOLD, Math.max(18, image.getWidth() / 42)));
            FontMetrics metrics = graphics.getFontMetrics();
            List<String> lines = wrap(subtitle, metrics, image.getWidth() - pad * 4);
            int lineHeight = metrics.getHeight();
            int boxHeight = lines.size() * lineHeight + pad * 2;
            int top = image.getHeight() - boxHeight - pad;
            graphics.setColor(new Color(0, 0, 0, 190));
            graphics.fillRoundRect(pad, top, image.getWidth() - pad * 2, boxHeight, 12, 12);
            graphics.setColor(Color.WHITE);
            int baseline = top + pad + metrics.getAscent();
            for (String line : lines) {
                graphics.drawString(line, pad * 2, baseline);
                baseline += lineHeight;
            }
        } finally {
            graphics.dispose();
        }
    }

    private static List<String> wrap(String value, FontMetrics metrics, int maxWidth) {
        List<String> lines = new ArrayList<String>();
        StringBuilder line = new StringBuilder();
        for (String word : value.split("\\s+")) {
            if (word.isEmpty()) continue;
            String next = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && metrics.stringWidth(next) > maxWidth) {
                lines.add(line.toString());
                line.setLength(0);
            }
            for (int offset = 0; offset < word.length();) {
                int codePoint = word.codePointAt(offset);
                String character = new String(Character.toChars(codePoint));
                if (line.length() > 0 && metrics.stringWidth(line + character) > maxWidth) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                line.append(character);
                offset += Character.charCount(codePoint);
            }
            line.append(' ');
        }
        if (line.length() > 0) lines.add(line.toString().trim());
        return lines;
    }

    private void write(boolean complete) throws Exception {
        String json = "{\n\"schema_version\":1,\n\"status\":\"" + (complete ? "completed" : "partial")
                + "\",\n\"clock\":\"GretaTimer\",\n\"subtitles_burned_in\":true,\n"
                + "\"width\":" + width + ",\n\"height\":" + height + ",\n\"target_fps\":25,\n"
                + "\"first_audio_start_greta_timer_millis\":" + (firstAudio < 0 ? "null" : firstAudio)
                + ",\n\"capture_end_greta_timer_millis\":" + (captureEnd < 0 ? "null" : captureEnd)
                + ",\n\"frames\":[" + String.join(",", frameRows)
                + "],\n\"audio\":[" + String.join(",", audioRows) + "]\n}\n";
        File temporary = new File(manifest.getParentFile(), manifest.getName() + ".tmp");
        Files.write(temporary.toPath(), json.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temporary.toPath(), manifest.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary.toPath(), manifest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String quote(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') escaped.append("\\\\");
            else if (c == '"') escaped.append("\\\"");
            else if (c == '\n') escaped.append("\\n");
            else if (c == '\r') escaped.append("\\r");
            else if (c == '\t') escaped.append("\\t");
            else if (c < 0x20) escaped.append(String.format(Locale.ROOT, "\\u%04x", (int)c));
            else escaped.append(c);
        }
        return escaped.toString();
    }
}
