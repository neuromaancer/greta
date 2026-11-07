import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Timing and arm-channel state for a real MeaningMiner gesture realization. */
public final class MeaningGesture {
    static final int FRAME_MILLIS = 40;
    static final int BLEND_FRAMES = 18;
    static final int RETURN_FRAMES = 60; // 2.4 seconds of decelerated arm and hand settling.
    static final int RETURN_DELAY_FRAMES = 4; // Briefly finish the gesture after the voice ends.
    static final int MAX_DIRECT_SETTLE_FRAMES = 16; // Bounded 25 fps release-only tail after a direct return.
    static final int MAX_CAPTURE_TAIL_FRAMES = 125;
    // A capture at audioEnd+125 is inclusive; direct return starts on the next
    // frame, then retains its hold, nominal return, and bounded tail.
    static final long MAX_DIRECT_RESTORE_AFTER_AUDIO_MILLIS =
            (long)(MAX_CAPTURE_TAIL_FRAMES + 1 + RETURN_DELAY_FRAMES + RETURN_FRAMES + MAX_DIRECT_SETTLE_FRAMES) * FRAME_MILLIS;
    static final int WORKSPACE_RETURN_FRAMES = 24;
    static final int WORKSPACE_RETURN_SEPARATED_FRAMES = 12;
    static final int UNCLASP_FRAMES = 3;
    static final int WORKSPACE_FRAMES = 9;
    static final int RECLASP_CLEARANCE_FADE_FRAMES = 5;
    static final int RECLASP_BASELINE_CONTACT_FRAMES = 3;
    static final int MAX_RAW_FRAMES = 1000;
    static final double SHOULDER_CLEARANCE_DEGREES = 6.0;

    private MeaningGesture() { }

    /** Expressive variation retains the chosen schema, hands, and word markers. */
    static final class Style {
        final String name;
        final double leftGain, rightGain, spatial, temporal, fluidity, power;
        private Style(String name, double leftGain, double rightGain,
                double spatial, double temporal, double fluidity, double power) {
            this.name = name; this.leftGain = leftGain; this.rightGain = rightGain;
            this.spatial = spatial; this.temporal = temporal;
            this.fluidity = fluidity; this.power = power;
        }
        static Style named(String name) {
            if ("legacy".equals(name)) return new Style(name, 1, 1, .5, .5, .5, .5);
            if ("balanced".equals(name)) return new Style(name, 1, 1, .35, .42, .7, .25);
            if ("reserved".equals(name)) return new Style(name, .65, .85, .25, .35, .65, .18);
            if ("animated".equals(name)) return new Style(name, 1.1, 1.0, .45, .48, .75, .32);
            throw new IllegalArgumentException("Unknown seated gesture style: " + name);
        }
    }

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
        private final Style style;
        private final double releaseLead;
        // Sort by BAP frame number: a later-arriving queued batch must never
        // erase or hide an earlier scheduled pose.
        private final Map<Integer, Map<String, Double>> raw = new TreeMap<Integer, Map<String, Double>>();
        // First observed BAP value is immutable per joint. It is the stock
        // lexicon's standing neutral used to retarget shoulders/elbows to the
        // seated clasp; wrists and fingers retain their stock handshape.
        private final Map<String, Double> stockNeutral = new LinkedHashMap<String, Double>();
        // Optional actor-specific workspace fit. It is constant for this
        // semantic turn and never changes the captured wrist/finger handshape.
        private final Map<String, Double> semanticArmOffsets;
        // Exact values submitted to the avatar after seated adaptation. Raw
        // capture may continue after a frame has already been queued, so this
        // is intentionally separate from the mutable raw plan used for future
        // frames.
        private final Map<Integer, Map<String, Double>> emitted = new TreeMap<Integer, Map<String, Double>>();
        // Optional controller-owned reverse path. It is deliberately separate
        // from captured semantic BAP frames and remains immutable after bind.
        private List<Map<String, Double>> handReturnPath;
        private List<Double> handReturnClearance;
        private boolean directReturn;
        // Zero preserves the accepted nominal direct-return timeline. A
        // nonzero count is opened only after the terminal collision check has
        // proved that one-degree release needs another frame.
        private int directSettleFrames;
        private int audioStartFrame = -1, audioEndFrame = -1, firstRawFrame = -1, lastRawFrame = -1;
        private boolean bound;

        Turn(String actor, String eventId, double gain) {
            this(actor, eventId, gain, Collections.<String, Double>emptyMap());
        }

        Turn(String actor, String eventId, double gain, Map<String, Double> semanticArmOffsets) {
            this(actor, eventId, gain, semanticArmOffsets, Style.named("legacy"));
        }

        Turn(String actor, String eventId, double gain, Map<String, Double> semanticArmOffsets, Style style) {
            this(actor, eventId, gain, semanticArmOffsets, style, 0);
        }

        Turn(String actor, String eventId, double gain, Map<String, Double> semanticArmOffsets, Style style, double releaseLead) {
            if (blank(actor) || blank(eventId)) throw new IllegalArgumentException("Meaning turn needs actor and event id");
            if (Double.isNaN(gain) || Double.isInfinite(gain) || gain < 0 || gain > 2)
                throw new IllegalArgumentException("Meaning seated arm gain must be in [0,2]");
            this.actor = actor; this.eventId = eventId; this.gain = gain;
            if (style == null) throw new IllegalArgumentException("Gesture style is required");
            this.style = style;
            if (Double.isNaN(releaseLead) || Double.isInfinite(releaseLead) || releaseLead < 0 || releaseLead > .5)
                throw new IllegalArgumentException("Workspace release lead must be in [0,0.5]");
            this.releaseLead = releaseLead;
            Map<String, Double> copiedOffsets = new LinkedHashMap<String, Double>();
            if (semanticArmOffsets != null) for (Map.Entry<String, Double> entry : semanticArmOffsets.entrySet()) {
                String joint = entry.getKey(); Double value = entry.getValue();
                if (!isSemanticOffsetJoint(joint) || value == null || Double.isNaN(value.doubleValue()) || Double.isInfinite(value.doubleValue()) || Math.abs(value.doubleValue()) > 35)
                    throw new IllegalArgumentException("Semantic arm offsets require bounded shoulder or elbow flexion channels");
                copiedOffsets.put(joint, Double.valueOf(value.doubleValue()));
            }
            this.semanticArmOffsets = Collections.unmodifiableMap(copiedOffsets);
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
            int nominalBefore = bound && directReturn ? nominalDirectRestoreEndFrame() : -1;
            Map<String, Double> prior = raw.get(Integer.valueOf(frame));
            if (prior != null) { Map<String, Double> merged = new LinkedHashMap<String, Double>(prior); merged.putAll(copy); copy = merged; }
            if (prior == null && raw.size() >= MAX_RAW_FRAMES) throw new IllegalStateException("Meaning BAP capture exceeds frame bound");
            raw.put(Integer.valueOf(frame), Collections.unmodifiableMap(copy));
            if (firstRawFrame < 0 || frame < firstRawFrame) firstRawFrame = frame;
            if (frame > lastRawFrame) {
                lastRawFrame = frame;
                // A late real end frame moves the nominal direct return. Any
                // tail budget belonged to the old endpoint and must not delay
                // the newly extended semantic trajectory.
                if (bound && directReturn && nominalDirectRestoreEndFrame() > nominalBefore) directSettleFrames = 0;
            }
        }

        synchronized void bind(long startMillis, long endMillis) {
            if (endMillis <= startMillis) throw new IllegalArgumentException("Meaning audio end must follow start");
            audioStartFrame = frameAtOrAfter(startMillis); // validates the actual Audio timestamp without retiming raw BAP frames.
            audioEndFrame = frameAtOrAfter(endMillis);
            if (audioEndFrame - audioStartFrame > 375) throw new IllegalArgumentException("Meaning audio interval exceeds 15 seconds");
            if (firstRawFrame >= 0) { validateFrame(firstRawFrame); validateFrame(lastRawFrame); }
            bound = true;
        }

        /** Sets the optional controller-verified opening path before audio timing is bound. */
        synchronized void setHandReturnPath(List<Map<String, Double>> poses, List<Double> clearanceWeights) {
            if (bound) throw new IllegalStateException("Hand return path must be set before bind");
            if (poses == null || clearanceWeights == null) {
                if (poses != null || clearanceWeights != null) throw new IllegalArgumentException("Hand return poses and clearance weights are paired");
                handReturnPath = null; handReturnClearance = null; return;
            }
            int maximumPathLength = WORKSPACE_RETURN_FRAMES - WORKSPACE_RETURN_SEPARATED_FRAMES;
            if (poses.size() < 2 || poses.size() != clearanceWeights.size() || poses.size() > maximumPathLength)
                throw new IllegalArgumentException("Hand return path needs matching base and separated poses within the reclasp duration");
            List<Map<String, Double>> copiedPoses = new ArrayList<Map<String, Double>>();
            List<Double> copiedWeights = new ArrayList<Double>();
            for (int index = 0; index < poses.size(); index++) {
                Map<String, Double> source = poses.get(index);
                Double weight = clearanceWeights.get(index);
                if (source == null || source.isEmpty() || weight == null || Double.isNaN(weight.doubleValue()) || Double.isInfinite(weight.doubleValue())
                        || weight.doubleValue() < 0 || weight.doubleValue() > 1)
                    throw new IllegalArgumentException("Hand return path requires finite poses and clearance weights in [0,1]");
                Map<String, Double> copy = new LinkedHashMap<String, Double>();
                for (Map.Entry<String, Double> entry : source.entrySet()) {
                    String joint = entry.getKey(); Double value = entry.getValue();
                    if (!isReturnPathChannel(joint) || value == null || Double.isNaN(value.doubleValue()) || Double.isInfinite(value.doubleValue()))
                        throw new IllegalArgumentException("Hand return path must contain finite arm/hand channels");
                    copy.put(joint, Double.valueOf(value.doubleValue()));
                }
                if (!copiedPoses.isEmpty() && !copiedPoses.get(0).keySet().equals(copy.keySet()))
                    throw new IllegalArgumentException("Hand return path poses must use identical arm/hand channels");
                copiedPoses.add(Collections.unmodifiableMap(copy));
                copiedWeights.add(Double.valueOf(weight.doubleValue()));
            }
            handReturnPath = Collections.unmodifiableList(copiedPoses);
            handReturnClearance = Collections.unmodifiableList(copiedWeights);
        }

        /** A separated rest can settle in one continuous motion instead of reclasping. */
        synchronized void setDirectReturn(boolean enabled) {
            if (bound) throw new IllegalStateException("Return mode must be set before bind");
            directReturn = enabled;
        }

        synchronized boolean isBound() { return bound; }
        synchronized boolean hasRawFrames() { return firstRawFrame >= 0; }
        synchronized int firstRawFrame() { return firstRawFrame; }
        synchronized int lastRawFrame() { return lastRawFrame; }
        synchronized int rawFrameCount() { return raw.size(); }
        synchronized int neutralJointCount() { return stockNeutral.size(); }
        synchronized int replayStartFrame(int currentFrame) { return firstRawFrame < 0 ? -1 : Math.max(firstRawFrame, currentFrame + 1); }
        synchronized int restoreEndFrame() {
            if (audioEndFrame < 0) return -1;
            if (directReturn) return nominalDirectRestoreEndFrame() + directSettleFrames;
            int end = Math.max(audioEndFrame, lastRawFrame);
            return end < 0 ? -1 : end + WORKSPACE_RETURN_FRAMES;
        }
        synchronized int nominalDirectRestoreEndFrame() {
            return directReturn && audioEndFrame >= 0 ? returnStartFrame() + RETURN_FRAMES : -1;
        }
        synchronized int directSettleFrames() { return directSettleFrames; }
        /** Opens one bounded release-only frame at the current direct endpoint. */
        synchronized boolean extendDirectSettling(int frame) {
            if (!directReturn || frame != restoreEndFrame() || directSettleFrames >= MAX_DIRECT_SETTLE_FRAMES) return false;
            directSettleFrames++;
            return true;
        }
        synchronized boolean activeAt(int frame) { return bound && firstRawFrame >= 0 && frame >= firstRawFrame && frame <= restoreEndFrame(); }
        synchronized boolean restoredAt(int frame) { return bound && restoreEndFrame() >= 0 && frame > restoreEndFrame(); }

        /** Deterministic, text-free diagnostic of captured BAP values and audio frame bounds. */
        synchronized String rawTrace() {
            StringBuilder trace = new StringBuilder();
            trace.append("audio_start_frame=").append(audioStartFrame).append('\n');
            trace.append("audio_end_frame=").append(audioEndFrame).append('\n');
            for (Map.Entry<Integer, Map<String, Double>> frame : raw.entrySet()) {
                trace.append(frame.getKey().intValue()).append('|');
                boolean first = true;
                for (Map.Entry<String, Double> joint : new TreeMap<String, Double>(frame.getValue()).entrySet()) {
                    if (!first) trace.append(',');
                    trace.append(joint.getKey()).append('=').append(Double.toString(joint.getValue().doubleValue()));
                    first = false;
                }
                trace.append('\n');
            }
            return trace.toString();
        }

        /**
         * Produces one complete arm/hand pose from the captured stock frame.
         * The opening first releases the clasp before the stock handshape can
         * move across the other hand. Legacy contact rests return through the
         * verified separated workspace; explicitly separated rests can use one
         * continuous, fully guarded interpolation back to their resting pose.
         */
        synchronized Map<String, Double> poseAt(int frame, Map<String, Double> bases) {
            Map<String, Double> pose = new LinkedHashMap<String, Double>();
            if (bases == null) return pose;
            for (Map.Entry<String, Double> entry : bases.entrySet()) {
                String joint = entry.getKey();
                double base = entry.getValue().doubleValue();
                pose.put(joint, Double.valueOf(valueAt(joint, frame, base)));
            }
            return pose;
        }

        /** Backwards-compatible single-channel view used by focused tests. */
        synchronized double valueAt(String joint, int frame, double seatedBase) {
            if (!activeAt(frame)) return seatedBase;
            Double rawValue = valueAtOrBefore(joint, frame);
            // A semantic BAP batch may mask only part of a hand.  Missing
            // channels keep their calibrated target, but fingers still pass
            // through the shared release/reclasp timeline.
            double semantic = semanticValue(joint, rawValue, frame, seatedBase);
            int first = firstRawFrame;
            int semanticStart = semanticStartFrame();
            if (frame < semanticStart) {
                int offset = frame - first;
                if (offset <= UNCLASP_FRAMES) {
                    if (isFinger(joint)) return interpolate(seatedBase, 0, smooth(offset / (double) UNCLASP_FRAMES));
                    if (isProximalArm(joint)) return interpolate(seatedBase, workspaceOffsetValue(joint, seatedBase),
                            releaseLead * smooth(offset / (double) UNCLASP_FRAMES));
                    return seatedBase;
                }
                if (offset <= WORKSPACE_FRAMES) {
                    if (isProximalArm(joint)) return interpolate(seatedBase, workspaceOffsetValue(joint, seatedBase),
                            releaseLead + (1 - releaseLead) * smooth((offset - UNCLASP_FRAMES) / (double) (WORKSPACE_FRAMES - UNCLASP_FRAMES)));
                    return isFinger(joint) ? 0 : seatedBase;
                }
                double prepared = isProximalArm(joint) ? workspaceOffsetValue(joint, seatedBase)
                        : (isFinger(joint) ? 0 : seatedBase);
                return interpolate(prepared, semantic,
                        smooth((offset - WORKSPACE_FRAMES) / (double) (BLEND_FRAMES - WORKSPACE_FRAMES)));
            }
            int returnStart = returnStartFrame();
            if (frame < returnStart) return semantic;
            if (directReturn) {
                if (frame >= nominalDirectRestoreEndFrame()) return seatedBase;
                return interpolate(semantic, seatedBase,
                        settleEase((frame - returnStart) / (double) RETURN_FRAMES));
            }
            int reclaspStart = reclaspStartFrame();
            if (frame < reclaspStart) {
                double separated = returnJoinValue(joint, seatedBase);
                return interpolate(semantic, separated,
                        smooth((frame - returnStart + 1) / (double) WORKSPACE_RETURN_SEPARATED_FRAMES));
            }
            if (frame < restoreEndFrame()) {
                if (handReturnPath != null) return returnPathValue(joint,
                        Math.max(0, handReturnPath.size() - 1 - (frame - reclaspStart)), seatedBase);
                double separated = separatedBase(joint, seatedBase);
                return interpolate(separated, seatedBase,
                        smooth((frame - reclaspStart) / (double) (WORKSPACE_RETURN_FRAMES - WORKSPACE_RETURN_SEPARATED_FRAMES)));
            }
            return seatedBase;
        }

        /**
         * Scalar air-gap request for paired-hand correction. Zero retains the
         * accepted calibrated-contact threshold; it does not claim any hand
         * topology is collision-free. Motion ownership remains separate.
         */
        synchronized double clearanceWeightAt(int frame) {
            if (!activeAt(frame) || frame >= restoreEndFrame()) return 0;
            int semanticStart = semanticStartFrame();
            if (frame < semanticStart) {
                int releaseOffset = frame - firstRawFrame;
                if (releaseOffset <= UNCLASP_FRAMES) return 0;
                if (releaseOffset <= WORKSPACE_FRAMES)
                    return smooth((releaseOffset - UNCLASP_FRAMES) / (double) (WORKSPACE_FRAMES - UNCLASP_FRAMES));
                return 1.0;
            }
            if (directReturn) return frame < nominalDirectRestoreEndFrame() ? 1.0 : 0.0;
            int reclaspStart = reclaspStartFrame();
            if (frame < reclaspStart) return handReturnClearance == null ? 1.0
                    : handReturnClearance.get(handReturnClearance.size() - 1).doubleValue();
            if (handReturnClearance != null)
                return handReturnClearance.get(Math.max(0, handReturnClearance.size() - 1 - (frame - reclaspStart))).doubleValue();
            int reclaspOffset = frame - reclaspStart;
            if (reclaspOffset >= RECLASP_CLEARANCE_FADE_FRAMES) return 0;
            return 1.0 - smooth(reclaspOffset / (double) (RECLASP_CLEARANCE_FADE_FRAMES - 1));
        }

        synchronized String phaseNameAt(int frame) {
            if (!bound || firstRawFrame < 0) return "static";
            if (!activeAt(frame)) return frame > restoreEndFrame() ? "restored" : "static";
            if (frame < semanticStartFrame()) return "unclasp";
            if (frame < returnStartFrame()) return "semantic";
            if (directReturn) {
                if (frame < nominalDirectRestoreEndFrame()) return "return";
                return frame < restoreEndFrame() ? "settling" : "restored";
            }
            if (frame < reclaspStartFrame()) return "return-separated";
            if (frame < restoreEndFrame()) return "reclasp";
            return "restored";
        }

        private double semanticValue(String joint, Double rawValue, int frame, double seatedBase) {
            // A partial BAP frame may introduce this joint only later. Its
            // future stock-neutral value must not create a phantom delta in
            // an earlier frame with no value for this channel.
            if (rawValue == null) return workspaceOffsetValue(joint, seatedBase);
            double raw = rawValue.doubleValue();
            double displacement = raw - seatedBase;
            if (isProximalArm(joint)) {
                Double neutral = stockNeutral.get(joint);
                if (neutral != null) displacement = raw - neutral.doubleValue();
                displacement *= gain * (joint.startsWith("l_") ? style.leftGain : style.rightGain);
            }
            // Gain is deliberately proximal-only: semantic wrist/finger
            // handshapes remain the stock lexicon's full-strength motion.
            double seated = seatedBase + displacement;
            if ("legacy".equals(style.name)) {
                if ("l_shoulder_abduct".equals(joint)) seated += SHOULDER_CLEARANCE_DEGREES * sideMotionWeight("l_", frame);
                if ("r_shoulder_abduct".equals(joint)) seated -= SHOULDER_CLEARANCE_DEGREES * sideMotionWeight("r_", frame);
            }
            // Legacy callers retain their old angular restriction. Styled
            // motion uses explicit body/table geometry instead: the sign of a
            // joint angle alone does not determine world-space hand clearance.
            if ("legacy".equals(style.name) && isTabletopFlexion(joint)) seated = Math.min(seated, seatedBase);
            return workspaceOffsetValue(joint, seated);
        }

        private double workspaceOffsetValue(String joint, double seated) {
            Double offset = semanticArmOffsets.get(joint);
            if (offset == null) return seated;
            // Apply the seated workspace fit after stock retargeting. Its
            // safety is established by geometry, not by the offset's sign.
            return seated + offset.doubleValue();
        }

        private int semanticStartFrame() { return firstRawFrame + BLEND_FRAMES; }
        synchronized int returnStartFrame() {
            int start = Math.max(lastRawFrame + 1, audioEndFrame);
            return directReturn ? start + RETURN_DELAY_FRAMES : start;
        }
        synchronized int reclaspStartFrame() { return returnStartFrame() + WORKSPACE_RETURN_SEPARATED_FRAMES; }
        private double returnJoinValue(String joint, double seatedBase) {
            if (handReturnPath == null) return separatedBase(joint, seatedBase);
            return returnPathValue(joint, handReturnPath.size() - 1, seatedBase);
        }
        private double returnPathValue(String joint, int index, double seatedBase) {
            Double target = handReturnPath.get(index).get(joint);
            return target == null ? separatedBase(joint, seatedBase) : target.doubleValue();
        }
        private static double settleEase(double value) {
            double t = Math.max(0.0, Math.min(1.0, value));
            return t * t * t * (10.0 + t * (-15.0 + 6.0 * t));
        }
        private static double separatedBase(String joint, double seatedBase) {
            return isFinger(joint) ? 0.0 : seatedBase;
        }
        private static boolean isHandChannel(String joint) { return isFinger(joint) || joint.indexOf("_wrist_") >= 1; }
        private static boolean isReturnPathChannel(String joint) {
            return joint != null && (isProximalArm(joint) || isHandChannel(joint));
        }
        private static boolean isFinger(String joint) {
            return joint.indexOf("_thumb") >= 1 || joint.indexOf("_index") >= 1
                    || joint.indexOf("_middle") >= 1 || joint.indexOf("_ring") >= 1
                    || joint.indexOf("_pinky") >= 1;
        }
        private static double interpolate(double from, double to, double weight) { return from + (to - from) * Math.max(0.0, Math.min(1.0, weight)); }

        synchronized boolean hasValueAt(String joint, int frame) { return activeAt(frame) && valueAtOrBefore(joint, frame) != null; }

        synchronized void recordEmitted(int frame, Map<String, Double> values) {
            if (frame < 0 || values == null || values.isEmpty()) return;
            emitted.put(Integer.valueOf(frame), Collections.unmodifiableMap(new LinkedHashMap<String, Double>(values)));
        }

        synchronized int firstEmittedFrame() { return emitted.isEmpty() ? -1 : emitted.keySet().iterator().next().intValue(); }
        synchronized int lastEmittedFrame() {
            if (emitted.isEmpty()) return -1;
            int last = -1;
            for (Integer frame : emitted.keySet()) last = frame.intValue();
            return last;
        }

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
            if (frame < audioStartFrame - 50 || frame > audioEndFrame + MAX_CAPTURE_TAIL_FRAMES)
                throw new IllegalArgumentException("Meaning BAP frame lies outside bounded audio tail: " + frame);
        }

        private static boolean isProximalArm(String joint) {
            return (joint.startsWith("l_") || joint.startsWith("r_"))
                    && (joint.indexOf("_shoulder_") >= 1 || joint.indexOf("_elbow_") >= 1);
        }

        private static boolean isSemanticOffsetJoint(String joint) {
            return "l_shoulder_abduct".equals(joint) || "r_shoulder_abduct".equals(joint)
                    || "l_shoulder_flexion".equals(joint) || "r_shoulder_flexion".equals(joint)
                    || "l_shoulder_twisting".equals(joint) || "r_shoulder_twisting".equals(joint)
                    || "l_elbow_flexion".equals(joint) || "r_elbow_flexion".equals(joint);
        }

        private static boolean isTabletopFlexion(String joint) {
            return "l_shoulder_flexion".equals(joint) || "r_shoulder_flexion".equals(joint)
                    || "l_elbow_flexion".equals(joint) || "r_elbow_flexion".equals(joint);
        }

        private static double smooth(double value) { return value * value * (3.0 - 2.0 * value); }
        private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    }
}
