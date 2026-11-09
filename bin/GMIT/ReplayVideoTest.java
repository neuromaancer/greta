import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.imageio.ImageIO;

/** Headless recording checks, including evidence that text changes PNG pixels. */
public final class ReplayVideoTest {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        File directory = Files.createTempDirectory("replay-video-test-").toFile();
        ReplayVideo recording = new ReplayVideo(directory);
        int width = 640, height = 240;
        byte[] rgb = new byte[width * height * 3];
        for (int i = 0; i < rgb.length; i += 3) {
            rgb[i] = 22;
            rgb[i + 1] = 70;
            rgb[i + 2] = 120;
        }
        recording.frame(width, height, rgb, 44, "Facilitator: These words are burned into the image.");
        require(!recording.isDue(83), "native readback must be skipped before the next 40 ms frame");
        require(recording.isDue(84), "the next frame becomes due on the Greta clock");
        recording.frame(width, height, rgb, 60, "must not create a second frame");
        require(!new File(directory, "video-frames/frame-000002.png").exists(), "cadence limit was ignored");

        BufferedImage image = ImageIO.read(new File(directory, "video-frames/frame-000001.png"));
        require((image.getRGB(0, 0) & 0xffffff) == 0x164678, "subtitle burn changed unrelated scene pixels");
        int brightPixels = 0;
        for (int y = height / 2; y < height; y++) for (int x = 0; x < width; x++) {
            int pixel = image.getRGB(x, y);
            if (((pixel >> 16) & 255) > 220 && ((pixel >> 8) & 255) > 220 && (pixel & 255) > 220)
                brightPixels++;
        }
        require(brightPixels > 20, "manifest flag alone is insufficient: caption pixels must exist");

        boolean rejectedBackwards = false;
        try { recording.frame(width, height, rgb, 43, "bad time"); }
        catch (IllegalArgumentException expected) { rejectedBackwards = true; }
        require(rejectedBackwards, "backwards capture timestamps must be rejected");
        recording.audio("event\n\"one", "facilitator", "facilitator-1.wav", 50, 90);
        recording.finish(true, 91);
        String json = new String(Files.readAllBytes(new File(directory, "video-frames.json").toPath()),
                StandardCharsets.UTF_8);
        require(json.contains("\"schema_version\":1") && json.contains("\"status\":\"completed\""),
                "completed manifest has wrong schema or status");
        require(json.contains("\"greta_timer_millis\":44") && json.contains("\"capture_end_greta_timer_millis\":91"),
                "real capture endpoint must follow the last frame");
        require(json.contains("event\\n\\\"one"), "event metadata must remain valid escaped JSON");

        boolean rejectedOverwrite = false;
        try { new ReplayVideo(directory); }
        catch (IllegalStateException expected) { rejectedOverwrite = true; }
        require(rejectedOverwrite, "a second recording must not overwrite an existing manifest");
        boolean rejectedClosed = false;
        try { recording.frame(width, height, rgb, 100, "closed"); }
        catch (IllegalStateException expected) { rejectedClosed = true; }
        require(rejectedClosed, "closed recording accepted new frames");

        File failedDirectory = Files.createTempDirectory("replay-video-finalizer-").toFile();
        ReplaySubtitles failedSubtitles = new ReplaySubtitles(failedDirectory);
        ReplayVideo failedVideo = new ReplayVideo(failedDirectory);
        failedSubtitles.schedule(1, "one", "patient-a", "A speaks.", null, null, 1, 1, 50, 90);
        failedVideo.frame(width, height, rgb, 44, "A: A speaks.");
        failedVideo.audio("one", "patient-a", "patient-a-1.wav", 50, 90);
        boolean finalizationFailed = false;
        try { ReplayVideo.finishTogether(failedSubtitles, failedVideo, true, 44); }
        catch (IllegalArgumentException expected) { finalizationFailed = true; }
        require(finalizationFailed, "the injected second-finalizer failure was not exercised");
        String failedSubtitleJson = new String(Files.readAllBytes(new File(failedDirectory, "subtitles.json").toPath()), StandardCharsets.UTF_8);
        String failedVideoJson = new String(Files.readAllBytes(new File(failedDirectory, "video-frames.json").toPath()), StandardCharsets.UTF_8);
        require(failedSubtitleJson.contains("\"status\": \"partial\"")
                        && failedVideoJson.contains("\"status\":\"partial\""),
                "a later finalizer failure must downgrade both artifacts");
        System.out.println("ReplayVideoTest PASS");
    }
}
