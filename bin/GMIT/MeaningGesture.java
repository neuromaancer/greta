import java.util.Collections;
import java.util.List;

/**
 * Small, deterministic, facilitator-only tabletop gesture plan selected from
 * MeaningMiner image-schema labels.  This class has no Greta dependency so its
 * timing and channel bounds can be checked headlessly.
 */
public final class MeaningGesture {
    static final int FRAME_MILLIS = 40;
    static final int MIN_DURATION_FRAMES = 60;
    static final int RAMP_FRAMES = 8;

    private MeaningGesture() { }

    static Plan create(String eventId, List<String> schemas, int startFrame, int endFrame) {
        if (eventId == null || eventId.trim().isEmpty() || schemas == null || schemas.isEmpty()) return null;
        if (startFrame < 0 || endFrame - startFrame < MIN_DURATION_FRAMES) return null;
        String schema = firstSchema(schemas);
        if (schema == null) return null;
        Profile profile = profile(schema);
        // Return to the clasped pose before audio ends, leaving a small quiet tail.
        int finish = endFrame - 6;
        if (finish - startFrame < MIN_DURATION_FRAMES - 6) return null;
        return new Plan(eventId, schema, startFrame, finish, profile);
    }

    private static String firstSchema(List<String> schemas) {
        for (String schema : schemas) if (schema != null && !schema.trim().isEmpty()) return schema.trim();
        return null;
    }

    private static Profile profile(String schema) {
        String normalized = schema.toLowerCase(java.util.Locale.ROOT);
        // The two profiles stay inside the same small tabletop envelope.  The
        // selection is semantic-label driven, while unfamiliar labels use offer.
        if (normalized.contains("force") || normalized.contains("path") || normalized.contains("process"))
            return new Profile(-7, -5, 10, -8, 9, 0.55);
        return new Profile(-5, -3, 7, -6, 7, 0.50);
    }

    static final class Plan {
        final String eventId, schema;
        final int startFrame, finishFrame;
        final Profile profile;

        Plan(String eventId, String schema, int startFrame, int finishFrame, Profile profile) {
            this.eventId = eventId; this.schema = schema; this.startFrame = startFrame;
            this.finishFrame = finishFrame; this.profile = profile;
        }

        boolean covers(int frame) { return frame >= startFrame && frame <= finishFrame; }
        boolean expired(int frame) { return frame > finishFrame; }

        double weight(int frame) {
            if (!covers(frame)) return 0;
            int span = finishFrame - startFrame;
            int ramp = Math.min(RAMP_FRAMES, Math.max(1, span / 4));
            double in = Math.min(1, (frame - startFrame) / (double)ramp);
            double out = Math.min(1, (finishFrame - frame) / (double)ramp);
            return smooth(Math.min(in, out));
        }

        private static double smooth(double value) { return value * value * (3 - 2 * value); }
    }

    static final class Profile {
        final double shoulderFlexion, shoulderAbduct, elbowFlexion, elbowTwisting, wristFlexion, release;
        Profile(double shoulderFlexion, double shoulderAbduct, double elbowFlexion, double elbowTwisting, double wristFlexion, double release) {
            this.shoulderFlexion = shoulderFlexion; this.shoulderAbduct = shoulderAbduct;
            this.elbowFlexion = elbowFlexion; this.elbowTwisting = elbowTwisting;
            this.wristFlexion = wristFlexion; this.release = release;
        }
    }
}
