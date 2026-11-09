import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Headless checks for Greta-audio scheduled subtitle export. */
public final class ReplaySubtitlesTest {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        File directory = Files.createTempDirectory("replay-subtitles-test-").toFile();
        ReplaySubtitles subtitles = new ReplaySubtitles(directory);
        String unicodeText = "Hello\n\n--> <tag>& world \u2014 caf\u00e9";
        subtitles.schedule(1, "evt-1", "facilitator", unicodeText, "source-1", "7", 1, 2, 12000, 13500);
        require(subtitles.textAt(11999).isEmpty(), "subtitle must not display before Audio.start");
        require("Facilitator: Hello --> <tag>& world \u2014 caf\u00e9".equals(subtitles.textAt(12000)), "role label or display text changed");
        require(subtitles.textAt(13500).isEmpty(), "subtitle must clear at Audio.end");
        subtitles.schedule(2, "evt-2", "patient-a", "I agree.", "source-1", "7", 2, 2, 15000, 16250);
        require("A: I agree.".equals(subtitles.textAt(15000)), "patient label must be A");
        String srt = new String(Files.readAllBytes(subtitles.getSrtFile().toPath()), StandardCharsets.UTF_8);
        require(srt.contains("00:00:00,000 --> 00:00:01,500"), "first cue must establish SRT origin");
        require(srt.contains("00:00:03,000 --> 00:00:04,250"), "inter-turn Greta Timer gap was not preserved");
        require(!srt.contains("\n\n-->"), "whitespace must not inject an SRT cue boundary");
        require(srt.contains("Hello --&gt; &lt;tag&gt;&amp; world"), "SRT must escape transcript markup");
        String partial = new String(Files.readAllBytes(subtitles.getJsonFile().toPath()), StandardCharsets.UTF_8);
        require(partial.contains("\"status\": \"partial\""), "open export must be marked partial");
        require(partial.contains("\"physical_audio_completion_observed\": false"), "completion claim must remain truthful");
        require(partial.contains("\"text\":\"Hello\\n\\n--> <tag>& world \u2014 caf\u00e9\""), "sidecar must retain original Unicode text");
        boolean overlapRejected = false;
        try {
            subtitles.schedule(3, "evt-3", "patient-b", "overlap", null, null, 1, 1, 16000, 17000);
        } catch (IllegalArgumentException expected) {
            overlapRejected = true;
        }
        require(overlapRejected, "overlapping audio schedules must be rejected");
        subtitles.finish(true);
        String complete = new String(Files.readAllBytes(subtitles.getJsonFile().toPath()), StandardCharsets.UTF_8);
        require(complete.contains("\"status\": \"completed\""), "successful export not marked completed");
        boolean closedRejected = false;
        try {
            subtitles.schedule(3, "evt-3", "patient-b", "late", null, null, 1, 1, 17000, 18000);
        } catch (IllegalStateException expected) {
            closedRejected = true;
        }
        require(closedRejected, "closed export accepted another cue");
        ReplaySubtitles cancelled = new ReplaySubtitles(Files.createTempDirectory("replay-subtitles-cancel-").toFile());
        cancelled.schedule(1, "evt-cancel", "patient-b", "Cancelled later", null, null, 1, 1, 900, 1200);
        cancelled.finish(false);
        String cancelledJson = new String(Files.readAllBytes(cancelled.getJsonFile().toPath()), StandardCharsets.UTF_8);
        require(cancelledJson.contains("\"status\": \"partial\""), "cancelled export must remain partial");
        System.out.println("ReplaySubtitlesTest PASS");
    }
}
