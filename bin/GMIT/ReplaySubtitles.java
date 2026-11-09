import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Records audio-scheduled replay cues.  Timings are Greta Timer values, not
 * physical loudspeaker-completion observations.
 */
public final class ReplaySubtitles {
    public static final class Cue {
        public final int sequence;
        public final String eventId;
        public final String actor;
        public final String role;
        public final String text;
        public final String sourceEventId;
        public final String sourceTurn;
        public final int chunkIndex;
        public final int chunkCount;
        public final long startMillis;
        public final long endMillis;

        private Cue(int sequence, String eventId, String actor, String text,
                    String sourceEventId, String sourceTurn, int chunkIndex, int chunkCount,
                    long startMillis, long endMillis) {
            this.sequence = sequence;
            this.eventId = require(eventId, "eventId");
            this.actor = require(actor, "actor");
            this.role = roleFor(actor);
            this.text = require(text, "text");
            this.sourceEventId = sourceEventId;
            this.sourceTurn = sourceTurn;
            this.chunkIndex = chunkIndex;
            this.chunkCount = chunkCount;
            this.startMillis = startMillis;
            this.endMillis = endMillis;
        }
    }

    private final File srtFile;
    private final File jsonFile;
    private final List<Cue> cues = new ArrayList<Cue>();
    private long originMillis = -1;
    private boolean finished;
    private boolean completed;

    public ReplaySubtitles(File runDirectory) throws Exception {
        if (runDirectory == null || !runDirectory.isDirectory()) {
            throw new IllegalArgumentException("Subtitle run directory must exist");
        }
        srtFile = new File(runDirectory, "subtitles.srt");
        jsonFile = new File(runDirectory, "subtitles.json");
        writeFiles();
    }

    public synchronized Cue schedule(int sequence, String eventId, String actor, String text,
                                     String sourceEventId, String sourceTurn, int chunkIndex, int chunkCount,
                                     long startMillis, long endMillis) throws Exception {
        if (sequence < 1 || chunkIndex < 1 || chunkCount < chunkIndex || startMillis < 0 || endMillis <= startMillis) {
            throw new IllegalArgumentException("Invalid subtitle cue bounds");
        }
        if (!cues.isEmpty() && startMillis < cues.get(cues.size() - 1).endMillis) {
            throw new IllegalArgumentException("Audio-scheduled subtitle cues overlap");
        }
        if (finished) throw new IllegalStateException("Subtitle export is closed");
        Cue cue = new Cue(sequence, eventId, actor, text, sourceEventId, sourceTurn,
                          chunkIndex, chunkCount, startMillis, endMillis);
        if (originMillis < 0) originMillis = startMillis;
        cues.add(cue);
        writeFiles();
        return cue;
    }

    /** Returns a display-safe cue only during its actual Greta audio interval. */
    public synchronized String textAt(long timerMillis) {
        for (Cue cue : cues) {
            if (timerMillis >= cue.startMillis && timerMillis < cue.endMillis) {
                return cue.role + ": " + displayText(cue.text);
            }
        }
        return "";
    }

    public synchronized void finish(boolean didComplete) throws Exception {
        if (finished && (!completed || didComplete)) return;
        finished = true;
        completed = didComplete;
        writeFiles();
    }

    public File getSrtFile() { return srtFile; }
    public File getJsonFile() { return jsonFile; }
    public synchronized int size() { return cues.size(); }

    private void writeFiles() throws Exception {
        atomicWrite(srtFile, srt());
        atomicWrite(jsonFile, json());
    }

    private String srt() {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < cues.size(); i++) {
            Cue cue = cues.get(i);
            result.append(i + 1).append('\n')
                  .append(timestamp(cue.startMillis - originMillis)).append(" --> ")
                  .append(timestamp(cue.endMillis - originMillis)).append('\n')
                  .append(cue.role).append(": ").append(srtText(cue.text)).append("\n\n");
        }
        return result.toString();
    }

    private String json() {
        StringBuilder result = new StringBuilder();
        result.append("{\n  \"schema_version\": 1,\n")
              .append("  \"timing_origin\": \"first_audio_start_greta_timer_millis\",\n")
              .append("  \"first_audio_start_greta_timer_millis\": ")
              .append(originMillis < 0 ? "null" : Long.toString(originMillis)).append(",\n")
              .append("  \"status\": \"").append(completed ? "completed" : "partial").append("\",\n")
              .append("  \"scheduled_audio_only\": true,\n")
              .append("  \"physical_audio_completion_observed\": false,\n")
              .append("  \"cues\": [");
        for (int i = 0; i < cues.size(); i++) {
            Cue cue = cues.get(i);
            if (i != 0) result.append(',');
            result.append("\n    {\"sequence\":").append(cue.sequence)
                  .append(",\"event_id\":\"").append(jsonString(cue.eventId)).append("\"")
                  .append(",\"actor_id\":\"").append(jsonString(cue.actor)).append("\"")
                  .append(",\"role\":\"").append(jsonString(cue.role)).append("\"")
                  .append(",\"source_event_id\":").append(jsonNullable(cue.sourceEventId))
                  .append(",\"source_turn\":").append(jsonNullable(cue.sourceTurn))
                  .append(",\"chunk_index\":").append(cue.chunkIndex)
                  .append(",\"chunk_count\":").append(cue.chunkCount)
                  .append(",\"audio_start_greta_timer_millis\":").append(cue.startMillis)
                  .append(",\"audio_end_greta_timer_millis\":").append(cue.endMillis)
                  .append(",\"start_millis_from_first_audio\":").append(cue.startMillis - originMillis)
                  .append(",\"end_millis_from_first_audio\":").append(cue.endMillis - originMillis)
                  .append(",\"text\":\"").append(jsonString(cue.text)).append("\"}");
        }
        if (!cues.isEmpty()) result.append('\n');
        return result.append("  ]\n}\n").toString();
    }

    private static String require(String value, String name) {
        if (value == null || value.length() == 0) throw new IllegalArgumentException("Missing subtitle " + name);
        return value;
    }

    private static String roleFor(String actor) {
        if ("facilitator".equals(actor)) return "Facilitator";
        if ("patient-a".equals(actor)) return "A";
        if ("patient-b".equals(actor)) return "B";
        throw new IllegalArgumentException("Unknown subtitle actor: " + actor);
    }

    private static String displayText(String text) {
        StringBuilder result = new StringBuilder();
        boolean whitespace = false;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (Character.isWhitespace(value)) whitespace = result.length() > 0;
            else {
                if (whitespace) result.append(' ');
                result.append(value);
                whitespace = false;
            }
        }
        return result.toString();
    }

    private static String srtText(String text) {
        return displayText(text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String timestamp(long millis) {
        if (millis < 0) throw new IllegalArgumentException("Negative subtitle timestamp");
        long hours = millis / 3600000L;
        millis %= 3600000L;
        long minutes = millis / 60000L;
        millis %= 60000L;
        long seconds = millis / 1000L;
        millis %= 1000L;
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis);
    }

    private static String jsonNullable(String value) {
        return value == null ? "null" : "\"" + jsonString(value) + "\"";
    }

    private static String jsonString(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') result.append("\\\"");
            else if (c == '\\') result.append("\\\\");
            else if (c == '\b') result.append("\\b");
            else if (c == '\f') result.append("\\f");
            else if (c == '\n') result.append("\\n");
            else if (c == '\r') result.append("\\r");
            else if (c == '\t') result.append("\\t");
            else if (c < 0x20) result.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int)c));
            else result.append(c);
        }
        return result.toString();
    }

    private static void atomicWrite(File target, String value) throws Exception {
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        Files.write(temporary.toPath(), value.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
