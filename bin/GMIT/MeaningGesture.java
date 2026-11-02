import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Timing and arm-channel state for a real MeaningMiner gesture realization. */
public final class MeaningGesture {
    static final int FRAME_MILLIS = 40;
    static final int BLEND_FRAMES = 6;
    static final int RETURN_FRAMES = 20; // 800 ms at Greta's 25 fps clock.
    static final int MAX_RAW_FRAMES = 1000;
    static final double SHOULDER_CLEARANCE_DEGREES = 6.0;

    private MeaningGesture() { }

    static int frameAtOrAfter(long millis) {
        if (millis < 0 || millis > Integer.MAX_VALUE * (long)FRAME_MILLIS)
            throw new IllegalArgumentException("Out-of-range Greta Timer time: " + millis);
        return (int)((millis + FRAME_MILLIS - 1) / FRAME_MILLIS);
    }

    /**
     * MeaningMiner, the Planner and the character lexicon choose the gesture.
     * This object only captures their actual BAP arm/finger output and blends
     * it onto the seated clasp while excluding standing-body channels.
     */
    static final class Turn {
        final String actor, eventId;
        private final double gain;
        // Sort by BAP frame number: a later-arriving queued batch must never
        // erase or hide an earlier scheduled pose.
        private final Map<Integer, Map<String, Double>> raw = new TreeMap<Integer, Map<String, Double>>();
        // First observed BAP value is immutable per joint. It is the stock
        // lexicon's standing neutral used to retarget shoulders/elbows to the
        // seated clasp; wrists and fingers retain their stock handshape.
        private final Map<String, Double> stockNeutral = new LinkedHashMap<String, Double>();
        // Exact values submitted to the avatar after seated adaptation. Raw
        // capture may continue after a frame has already been queued, so this
        // is intentionally separate from the mutable raw plan used for future
        // frames.
        private final Map<Integer, Map<String, Double>> emitted = new TreeMap<Integer, Map<String, Double>>();
        private int audioStartFrame = -1, audioEndFrame = -1, firstRawFrame = -1, lastRawFrame = -1;
        private boolean bound;

        Turn(String actor, String eventId, double gain) {
            if (blank(actor) || blank(eventId)) throw new IllegalArgumentException("Meaning turn needs actor and event id");
            if (Double.isNaN(gain) || Double.isInfinite(gain) || gain < 0 || gain > 2)
                throw new IllegalArgumentException("Meaning seated arm gain must be in [0,2]");
            this.actor = actor; this.eventId = eventId; this.gain = gain;
        }

        synchronized void capture(int frame, Map<String, Double> values) {
            if (frame < 0 || values == null || values.isEmpty()) return;
            Map<String, Double> copy = new LinkedHashMap<String, Double>();
            for (Map.Entry<String, Double> entry : values.entrySet()) {
                Double value = entry.getValue();
                if (entry.getKey() == null || value == null || Double.isNaN(value) || Double.isInfinite(value))
                    throw new IllegalArgumentException("Meaning BAP arm value must be finite");
                copy.put(entry.getKey(), value);
                if (!stockNeutral.containsKey(entry.getKey())) stockNeutral.put(entry.getKey(), value);
            }
            if (copy.isEmpty()) return;
            if (bound) validateFrame(frame);
            Map<String, Double> prior = raw.get(Integer.valueOf(frame));
            if (prior != null) { Map<String, Double> merged = new LinkedHashMap<String, Double>(prior); merged.putAll(copy); copy = merged; }
            if (prior == null && raw.size() >= MAX_RAW_FRAMES) throw new IllegalStateException("Meaning BAP capture exceeds frame bound");
            raw.put(Integer.valueOf(frame), Collections.unmodifiableMap(copy));
            if (firstRawFrame < 0 || frame < firstRawFrame) firstRawFrame = frame;
            if (frame > lastRawFrame) lastRawFrame = frame;
        }

        synchronized void bind(long startMillis, long endMillis) {
            if (endMillis <= startMillis) throw new IllegalArgumentException("Meaning audio end must follow start");
            audioStartFrame = frameAtOrAfter(startMillis); // validates the actual Audio timestamp without retiming raw BAP frames.
            audioEndFrame = frameAtOrAfter(endMillis);
            if (audioEndFrame - audioStartFrame > 375) throw new IllegalArgumentException("Meaning audio interval exceeds 15 seconds");
            if (firstRawFrame >= 0) { validateFrame(firstRawFrame); validateFrame(lastRawFrame); }
            bound = true;
        }

        synchronized boolean isBound() { return bound; }
        synchronized boolean hasRawFrames() { return firstRawFrame >= 0; }
        synchronized int firstRawFrame() { return firstRawFrame; }
        synchronized int lastRawFrame() { return lastRawFrame; }
        synchronized int rawFrameCount() { return raw.size(); }
        synchronized int neutralJointCount() { return stockNeutral.size(); }
        synchronized int replayStartFrame(int currentFrame) { return firstRawFrame < 0 ? -1 : Math.max(firstRawFrame, currentFrame + 1); }
        synchronized int restoreEndFrame() { int end = Math.max(audioEndFrame, lastRawFrame); return end < 0 ? -1 : end + RETURN_FRAMES; }
        synchronized boolean activeAt(int frame) { return bound && firstRawFrame >= 0 && frame >= firstRawFrame && frame <= restoreEndFrame(); }
        synchronized boolean restoredAt(int frame) { return bound && restoreEndFrame() >= 0 && frame > restoreEndFrame(); }

        synchronized double valueAt(String joint, int frame, double seatedBase) {
            if (!activeAt(frame)) return seatedBase;
            Double rawValue = valueAtOrBefore(joint, frame);
            if (rawValue == null) return seatedBase;
            double displacement = rawValue.doubleValue() - seatedBase;
            if (isProximalArm(joint)) {
                Double neutral = stockNeutral.get(joint);
                if (neutral != null) displacement = rawValue.doubleValue() - neutral.doubleValue();
                displacement *= gain;
            }
            // Gain is deliberately proximal-only: semantic wrist/finger
            // handshapes remain the stock lexicon's full-strength motion.
            double weight = blendWeight(frame);
            double seated = seatedBase + displacement * weight;
            if ("l_shoulder_abduct".equals(joint)) seated += SHOULDER_CLEARANCE_DEGREES * weight * sideMotionWeight("l_", frame);
            if ("r_shoulder_abduct".equals(joint)) seated -= SHOULDER_CLEARANCE_DEGREES * weight * sideMotionWeight("r_", frame);
            // The seated calibration uses negative shoulder/elbow flexion for
            // tabletop lift. A positive stock delta lowers a free hand under
            // the table, so retain the calibrated maximum only. This clips
            // downward standing semantics to the available tabletop range; it
            // is deliberately not presented as collision solving.
            if (isTabletopFlexion(joint)) seated = Math.min(seated, seatedBase);
            return seated;
        }

        synchronized boolean hasValueAt(String joint, int frame) { return activeAt(frame) && valueAtOrBefore(joint, frame) != null; }

        synchronized void recordEmitted(int frame, Map<String, Double> values) {
            if (frame < 0 || values == null || values.isEmpty()) return;
            emitted.put(Integer.valueOf(frame), Collections.unmodifiableMap(new LinkedHashMap<String, Double>(values)));
        }

        synchronized int firstEmittedFrame() { return emitted.isEmpty() ? -1 : emitted.keySet().iterator().next().intValue(); }

        synchronized Double emittedValueAt(String joint, int frame) {
            Map<String, Double> values = emitted.get(Integer.valueOf(frame));
            return values == null ? null : values.get(joint);
        }

        private Double valueAtOrBefore(String joint, int frame) {
            Double found = null;
            for (Map.Entry<Integer, Map<String, Double>> entry : raw.entrySet()) {
                if (entry.getKey().intValue() > frame) break;
                Double candidate = entry.getValue().get(joint);
                if (candidate != null) found = candidate;
            }
            return found;
        }

        private double blendWeight(int frame) {
            if (frame <= lastRawFrame) return smooth(Math.min(1.0, Math.max(0.0, (frame - firstRawFrame) / (double)BLEND_FRAMES)));
            int returnStart = Math.max(lastRawFrame + 1, audioEndFrame);
            if (frame < returnStart) return 1.0;
            return smooth(Math.max(0.0, 1.0 - (frame - returnStart + 1) / (double)RETURN_FRAMES));
        }

        /**
         * Small table-clearance only while the stock gesture has a real
         * shoulder/elbow displacement. It is zero at the captured neutral and
         * during the deterministic return, so it cannot become a new gesture
         * template or change the resting clasp.
         */
        private double sideMotionWeight(String side, int frame) {
            double maximum = 0;
            for (String suffix : new String[] {"shoulder_flexion", "shoulder_abduct", "shoulder_twisting", "elbow_flexion", "elbow_twisting"}) {
                String joint = side + suffix;
                Double rawValue = valueAtOrBefore(joint, frame);
                Double neutral = stockNeutral.get(joint);
                if (rawValue != null && neutral != null) maximum = Math.max(maximum, Math.abs(rawValue.doubleValue() - neutral.doubleValue()));
            }
            // A one-degree deadband prevents baseline/noise clearance; 8+
            // degrees is a fully active stock proximal motion.
            return smooth(Math.min(1.0, Math.max(0.0, (maximum - 1.0) / 7.0)));
        }

        private void validateFrame(int frame) {
            if (frame < audioStartFrame - 50 || frame > audioEndFrame + 125)
                throw new IllegalArgumentException("Meaning BAP frame lies outside bounded audio tail: " + frame);
        }

        private static boolean isProximalArm(String joint) {
            return (joint.startsWith("l_") || joint.startsWith("r_"))
                    && (joint.indexOf("_shoulder_") >= 1 || joint.indexOf("_elbow_") >= 1);
        }

        private static boolean isTabletopFlexion(String joint) {
            return "l_shoulder_flexion".equals(joint) || "r_shoulder_flexion".equals(joint)
                    || "l_elbow_flexion".equals(joint) || "r_elbow_flexion".equals(joint);
        }

        private static double smooth(double value) { return value * value * (3.0 - 2.0 * value); }
        private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    }
}
