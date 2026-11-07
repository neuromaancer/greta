import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded listener eye/head tracking for the existing seated Autodesk avatars. */
public final class ListenerGaze {
    private static final String[] ACTORS = {"facilitator", "patient-a", "patient-b"};
    private static final Map<String, Actor> actors = new ConcurrentHashMap<>();
    private static final boolean ENABLED = enabled("gmit.gaze.enabled");
    private static final boolean HEAD = enabled("gmit.gaze.head");
    private static final boolean EYES = enabled("gmit.gaze.eyes");
    private static final boolean BACKCHANNEL = Boolean.parseBoolean(System.getProperty("gmit.gaze.backchannel", "true"));
    private static final int FRAME_MILLIS = 40; // Greta Constants.FRAME_DURATION_MILLIS (25 fps).
    private static final int LISTENER_DELAY_MILLIS = 200;
    private static final int MIDPOINT_MIN_MILLIS = 3500;
    private static final int MIDPOINT_TRANSITION_FRAMES = 28;
    private static final int TRANSITION_FRAMES = 18;
    private static final int HANDOFF_TRANSITION_FRAMES = 18;
    private static final int NOD_DURATION_FRAMES = 28; // 1.12 seconds, with a gentle start and finish.
    private static final int NOD_SETTLE_FRAMES = 2;
    private static final double NOD_PITCH_RADIANS = Math.toRadians(4);
    private static volatile Throwable failure;
    private static String speaker;
    private static Method currentFrame;

    private ListenerGaze() { }
    private static boolean enabled(String property) { return Boolean.parseBoolean(System.getProperty(property, "true")); }

    public static synchronized void install(Map<String, Object> objects) throws Exception {
        if (!ENABLED) { System.out.println("GMIT_GAZE disabled"); return; }
        if (!actors.isEmpty()) throw new IllegalStateException("Listener gaze already installed");
        failure = null;
        try {
            for (String id : ACTORS) {
                Actor actor = new Actor(id, objects.get("gmit-" + id + "-avatar"), objects.get("gmit-" + id + "-lipblender"));
                actors.put(id, actor);
                actor.install();
            }
            System.out.println("GMIT_GAZE installed head=" + HEAD + " eyes=" + EYES);
        } catch (Exception error) { close(); throw error; }
    }

    /** Preview-compatible immediate listener focus; the speaker remains neutral. */
    public static synchronized void focus(String activeSpeaker) throws Exception {
        checkHealthy();
        if (actors.isEmpty() || activeSpeaker.equals(speaker)) return;
        Actor target = actors.get(activeSpeaker);
        if (target == null) throw new IllegalArgumentException("Unknown gaze speaker: " + activeSpeaker);
        int now = ((Number)currentFrame.invoke(null)).intValue();
        for (Actor listener : actors.values()) if (listener != target) {
            listener.shift(now, gazeAngles(listener, target), TRANSITION_FRAMES);
            listener.publishTransition(now, now + 24);
            GazeModiffAdapter.scheduleTarget(listener.id, target.id, now + 1);
        }
        speaker = activeSpeaker;
    }

    /**
     * Schedule gaze from the Audio's Greta Timer timestamps, never from BML dispatch.
     * The speaker turns at speech start; listeners begin no earlier than 200 ms later.
     */
    public static synchronized void scheduleSpeech(String activeSpeaker, String speakerPeer,
                                                    long audioStartMillis, long audioEndMillis) throws Exception {
        scheduleSpeech(activeSpeaker, speakerPeer, null, audioStartMillis, audioEndMillis,
                stableSeed(activeSpeaker, speakerPeer, audioStartMillis, audioEndMillis));
    }

    /** Schedules a bounded speech interval, including an optional actual next-speaker handoff. */
    public static synchronized void scheduleSpeech(String activeSpeaker, String speakerPeer, String nextSpeaker,
                                                    long audioStartMillis, long audioEndMillis) throws Exception {
        scheduleSpeech(activeSpeaker, speakerPeer, nextSpeaker, audioStartMillis, audioEndMillis,
                stableSeed(activeSpeaker, speakerPeer, audioStartMillis, audioEndMillis));
    }

    /**
     * `seed` is supplied by the caller from stable session/event data so that a replay
     * has the same bounded backchannel timing on every run.
     */
    public static synchronized void scheduleSpeech(String activeSpeaker, String speakerPeer, String nextSpeaker,
                                                    long audioStartMillis, long audioEndMillis, long seed) throws Exception {
        checkHealthy();
        if (actors.isEmpty()) return;
        requireActor(activeSpeaker, "speaker");
        requireActor(speakerPeer, "speaker peer");
        if (activeSpeaker.equals(speakerPeer)) throw new IllegalArgumentException("Speaker cannot gaze at self: " + activeSpeaker);
        if (nextSpeaker != null) {
            requireActor(nextSpeaker, "next speaker");
            if (nextSpeaker.equals(activeSpeaker)) nextSpeaker = null; // A chunk continuation is not a handoff.
        }
        if (audioEndMillis <= audioStartMillis || audioEndMillis - audioStartMillis > 15000)
            throw new IllegalArgumentException("Gaze requires a bounded audio interval of at most 15 seconds");
        int now = ((Number)currentFrame.invoke(null)).intValue();
        Map<String, Integer> nodCounts = new java.util.HashMap<>();
        for (Actor actor : actors.values()) nodCounts.put(actor.id, Integer.valueOf(actor.nodCount()));
        TimingPlan plan = planTiming(activeSpeaker, speakerPeer, nextSpeaker, audioStartMillis, audioEndMillis, seed, now, nodCounts);
        for (TimingEvent event : plan.events()) schedule(plan, event);
        speaker = activeSpeaker;
        System.out.println("GMIT_GAZE scheduled speaker=" + activeSpeaker + " peer=" + speakerPeer
                + " audio_start_ms=" + audioStartMillis + " audio_end_ms=" + audioEndMillis
                + " seed=" + seed + " listener_frame=" + plan.listenerFrame
                + " listener_transition_delta_ms=" + (plan.listenerFrame < 0 ? -1 : (long)plan.listenerFrame * FRAME_MILLIS - audioStartMillis)
                + " next_speaker=" + (nextSpeaker == null ? "unknown" : nextSpeaker)
                + " handoff_frame=" + plan.handoffFrame + " nod_count=" + plan.nodCount());
    }

    static TimingPlan planTiming(String activeSpeaker, String speakerPeer, String nextSpeaker,
                                 long audioStartMillis, long audioEndMillis, long seed, int now) {
        return planTiming(activeSpeaker, speakerPeer, nextSpeaker, audioStartMillis, audioEndMillis,
                seed, now, Collections.<String, Integer>emptyMap());
    }

    /** A snapshot of scheduled nod counts keeps planning deterministic and session-local. */
    static TimingPlan planTiming(String activeSpeaker, String speakerPeer, String nextSpeaker,
                                 long audioStartMillis, long audioEndMillis, long seed, int now,
                                 Map<String, Integer> nodCounts) {
        if (nodCounts == null) throw new IllegalArgumentException("Nod counts are required");
        for (String id : ACTORS) if (nodCount(nodCounts, id) < 0)
            throw new IllegalArgumentException("Negative nod count for " + id);
        validateActorId(activeSpeaker, "speaker"); validateActorId(speakerPeer, "speaker peer");
        if (activeSpeaker.equals(speakerPeer)) throw new IllegalArgumentException("Speaker cannot gaze at self: " + activeSpeaker);
        if (nextSpeaker != null) { validateActorId(nextSpeaker, "next speaker"); if (nextSpeaker.equals(activeSpeaker)) nextSpeaker = null; }
        if (audioEndMillis <= audioStartMillis || audioEndMillis - audioStartMillis > 15000)
            throw new IllegalArgumentException("Gaze requires a bounded audio interval of at most 15 seconds");
        int speakerFrame = Math.max(now + 1, frameAtOrAfter(audioStartMillis));
        int listenerFrame = Math.max(now + 1, frameAtOrAfter(audioStartMillis + LISTENER_DELAY_MILLIS));
        int endFrame = frameAtOrAfter(audioEndMillis);
        if (listenerFrame >= endFrame) listenerFrame = -1;
        List<TimingEvent> events = new ArrayList<>();
        int handoff = -1;
        String initialTarget = speakerPeer;
        if (nextSpeaker != null && !nextSpeaker.equals(activeSpeaker)) {
            int candidate = endFrame - HANDOFF_TRANSITION_FRAMES;
            if (candidate >= speakerFrame + TRANSITION_FRAMES) handoff = candidate;
            else initialTarget = nextSpeaker; // Too short to arrive twice: face the actual next speaker immediately.
        }
        events.add(TimingEvent.gaze("speaker-start", activeSpeaker, initialTarget, speakerFrame, TRANSITION_FRAMES));
        if (listenerFrame >= 0) for (String id : ACTORS) if (!id.equals(activeSpeaker))
            events.add(TimingEvent.gaze("listener-start", id, activeSpeaker, listenerFrame, TRANSITION_FRAMES));
        String midpointPeer = null;
        long duration = audioEndMillis - audioStartMillis;
        if (duration >= MIDPOINT_MIN_MILLIS) {
            int midpoint = frameAtOrAfter(audioStartMillis + duration / 2);
            String secondPeer = remainingPeer(activeSpeaker, speakerPeer);
            boolean endsAtKnownNext = nextSpeaker != null && nextSpeaker.equals(secondPeer);
            // A known end target wins over an ornamental midpoint switch. If the
            // midpoint already reaches it, that focus remains through the end.
            boolean leavesHandoffWindow = handoff < 0 || endsAtKnownNext
                    || midpoint + MIDPOINT_TRANSITION_FRAMES <= handoff;
            if (midpoint >= speakerFrame + TRANSITION_FRAMES
                    && midpoint + MIDPOINT_TRANSITION_FRAMES <= endFrame && leavesHandoffWindow) {
                midpointPeer = secondPeer;
                events.add(TimingEvent.gaze("speaker-midpoint", activeSpeaker, secondPeer, midpoint, MIDPOINT_TRANSITION_FRAMES));
            }
        }
        if (handoff >= 0 && nextSpeaker != null && !nextSpeaker.equals(midpointPeer))
            events.add(TimingEvent.gaze("speaker-handoff", activeSpeaker, nextSpeaker, handoff, HANDOFF_TRANSITION_FRAMES));
        else if (nextSpeaker != null && nextSpeaker.equals(midpointPeer)) handoff = -1;
        if (BACKCHANNEL && listenerFrame >= 0) addOneNod(events, activeSpeaker, listenerFrame,
                handoff < 0 ? endFrame : handoff, seed, nodCounts);
        return new TimingPlan(speakerFrame, listenerFrame, endFrame, handoff, events);
    }

    private static int nodCount(Map<String, Integer> counts, String actor) {
        Integer count = counts.get(actor);
        return count == null ? 0 : count.intValue();
    }

    private static void addOneNod(List<TimingEvent> events, String speakerId, int listenerFrame,
                                  int latestFrame, long seed, Map<String, Integer> nodCounts) {
        // Callers commonly derive adjacent per-event seeds. Random's first draw
        // does not decorrelate those well enough for a visible 50% gate, so mix
        // the full 64-bit value before using it for every backchannel decision.
        Random random = new Random(mixSeed(seed));
        if (!random.nextBoolean()) return; // Deliberately occasional, never a required behavior.
        List<String> listeners = new ArrayList<>();
        int fewestNods = Integer.MAX_VALUE;
        for (String id : ACTORS) if (!id.equals(speakerId)) {
            int count = nodCount(nodCounts, id);
            if (count < fewestNods) { listeners.clear(); fewestNods = count; }
            if (count == fewestNods) listeners.add(id);
        }
        String listener = listeners.get(random.nextInt(listeners.size()));
        int earliest = listenerFrame + TRANSITION_FRAMES + NOD_SETTLE_FRAMES;
        int lastStart = latestFrame - NOD_SETTLE_FRAMES - NOD_DURATION_FRAMES;
        if (lastStart < earliest) return;
        int start = earliest + random.nextInt(lastStart - earliest + 1);
        events.add(TimingEvent.nod(listener, start, NOD_DURATION_FRAMES, NOD_PITCH_RADIANS));
    }

    /** SplitMix64 finalizer: deterministic avalanche for adjacent external seeds. */
    private static long mixSeed(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private static long stableSeed(String speakerId, String peerId, long start, long end) {
        long result = 531L;
        result = 31 * result + speakerId.hashCode(); result = 31 * result + peerId.hashCode();
        result = 31 * result + start; return 31 * result + end;
    }

    private static void validateActorId(String id, String label) {
        for (String candidate : ACTORS) if (candidate.equals(id)) return;
        throw new IllegalArgumentException("Unknown gaze " + label + ": " + id);
    }

    private static Actor requireActor(String id, String label) {
        Actor actor = actors.get(id);
        if (actor == null) throw new IllegalArgumentException("Unknown gaze " + label + ": " + id);
        return actor;
    }

    private static int frameAtOrAfter(long millis) {
        if (millis < 0 || millis > Integer.MAX_VALUE * (long)FRAME_MILLIS) throw new IllegalArgumentException("Out-of-range Timer time: " + millis);
        return (int)((millis + FRAME_MILLIS - 1) / FRAME_MILLIS);
    }

    private static String remainingPeer(String speakerId, String firstPeer) {
        for (String id : ACTORS) if (!id.equals(speakerId) && !id.equals(firstPeer)) return id;
        throw new IllegalArgumentException("No second gaze peer for " + speakerId);
    }

    /** Executes one already validated plan event without introducing a second pose owner. */
    private static void schedule(TimingPlan plan, TimingEvent event) throws Exception {
        Actor actor = requireActor(event.actor, event.kind);
        if (event.nod) {
            actor.nod(event.start, event.duration, event.pitch);
            actor.publishTransition(event.start - 1, plan.endFrame);
            System.out.println("GMIT_GAZE backchannel actor=" + event.actor + " kind=nod"
                    + " start_frame=" + event.start + " end_frame=" + (event.start + event.duration)
                    + " pitch_deg=" + Math.toDegrees(event.pitch));
            return;
        }
        Actor target = requireActor(event.target, event.kind + " target");
        actor.shift(event.start - 1, gazeAngles(actor, target), event.duration);
        actor.publishTransition(event.start - 1, plan.endFrame);
        GazeModiffAdapter.scheduleTarget(event.actor, event.target, event.start);
        System.out.println("GMIT_GAZE target actor=" + event.actor + " kind=" + event.kind
                + " target=" + event.target + " transition_frame=" + event.start
                + " duration_frames=" + event.duration
                + " handoff=" + event.kind.equals("speaker-handoff"));
    }

    /** Immutable deterministic scheduling result for headless timing regression checks. */
    static final class TimingPlan {
        final int speakerFrame, listenerFrame, endFrame, handoffFrame;
        private final List<TimingEvent> events;
        TimingPlan(int speakerFrame, int listenerFrame, int endFrame, int handoffFrame, List<TimingEvent> events) {
            this.speakerFrame = speakerFrame; this.listenerFrame = listenerFrame;
            this.endFrame = endFrame; this.handoffFrame = handoffFrame;
            this.events = Collections.unmodifiableList(new ArrayList<>(events));
        }
        List<TimingEvent> events() { return events; }
        int nodCount() { int count = 0; for (TimingEvent event : events) if (event.nod) count++; return count; }
        String speakerFinalTarget(String speakerId) {
            String target = null;
            for (TimingEvent event : events) if (!event.nod && speakerId.equals(event.actor)) target = event.target;
            return target;
        }
    }

    static final class TimingEvent {
        final String kind, actor, target;
        final int start, duration;
        final boolean nod;
        final double pitch;
        private TimingEvent(String kind, String actor, String target, int start, int duration, boolean nod, double pitch) {
            this.kind = kind; this.actor = actor; this.target = target; this.start = start;
            this.duration = duration; this.nod = nod; this.pitch = pitch;
        }
        static TimingEvent gaze(String kind, String actor, String target, int start, int duration) {
            return new TimingEvent(kind, actor, target, start, duration, false, 0);
        }
        static TimingEvent nod(String actor, int start, int duration, double pitch) {
            return new TimingEvent("listener-nod", actor, null, start, duration, true, pitch);
        }
    }

    private static double[] gazeAngles(Actor viewer, Actor gazeTarget) throws Exception {
        double[] origin = viewer.headWorld();
        double[] lookAt = gazeTarget.headWorld();
        double dx = lookAt[0] - origin[0], dy = lookAt[1] - origin[1], dz = lookAt[2] - origin[2];
        if (Math.hypot(dx, dz) < 0.2) throw new IllegalStateException("Gaze head targets overlap: " + viewer.id);
        Object body = invoke(viewer.avatar, "getCurrentBAPFrame");
        double rootYaw = ((Number)invoke(body, "getRadianValue", viewer.bap("HumanoidRoot_rt_body_turn"))).doubleValue();
        double yaw = Math.atan2(dx, dz) - rootYaw;
        yaw = Math.atan2(Math.sin(yaw), Math.cos(yaw));
        double pitch = -Math.atan2(dy, Math.hypot(dx, dz));
        double headYaw = HEAD ? clamp(yaw * 0.8, 65) : 0;
        double headPitch = HEAD ? clamp(pitch * 0.7, 20) : 0;
        double eyeYaw = EYES ? clamp(yaw - headYaw, 25) : 0;
        double eyePitch = EYES ? clamp(pitch - headPitch, 15) : 0;
        return new double[] {headYaw, headPitch, eyeYaw, eyePitch};
    }

    /** Runs after the seated overlay; root, arm, leg, and roll channels are preserved. */
    public static void applyBody(String actorId, Object frame) throws Exception {
        Actor actor = actors.get(actorId);
        if (actor == null || !HEAD) return;
        double[] angles = actor.bodyAngles(((Number)invoke(frame, "getFrameNumber")).intValue());
        for (int joint = 1; joint <= 7; joint++) {
            invoke(frame, "setRadianValue", actor.bap("vc" + joint + "_torsion"), angles[0] / 7);
            invoke(frame, "setRadianValue", actor.bap("vc" + joint + "_tilt"), angles[1] / 7);
        }
    }

    public static void checkHealthy() {
        if (failure != null) throw new IllegalStateException("Listener gaze overlay failed", failure);
    }

    /** Software channel checks, separate from rendered/audible human observation. */
    public static synchronized void verify() throws Exception {
        checkHealthy();
        if (actors.isEmpty() || speaker == null) return;
        int now = ((Number)currentFrame.invoke(null)).intValue();
        for (Actor actor : actors.values()) {
            int phaseStart = actor.phaseStartAt(now);
            if (phaseStart < 0 || now < phaseStart + 30) continue;
            double[] expected = actor.bodyAngles(now);
            Object body = invoke(actor.avatar, "getCurrentBAPFrame");
            Object face = invoke(actor.avatar, "getCurrentFAPFrame");
            double yaw = 0, pitch = 0;
            for (int i = 1; i <= 7; i++) {
                yaw += ((Number)invoke(body, "getRadianValue", actor.bap("vc" + i + "_torsion"))).doubleValue();
                pitch += ((Number)invoke(body, "getRadianValue", actor.bap("vc" + i + "_tilt"))).doubleValue();
            }
            if (HEAD) { near(actor.id, "head yaw", yaw, expected[0]); near(actor.id, "head pitch", pitch, expected[1]); }
            if (EYES) {
                for (String side : new String[] {"l", "r"}) {
                    near(actor.id, "eye yaw", ((Number)invoke(face, "getValue", actor.fap("yaw_" + side + "_eyeball"))).doubleValue() / 100000.0, expected[2]);
                    near(actor.id, "eye pitch", ((Number)invoke(face, "getValue", actor.fap("pitch_" + side + "_eyeball"))).doubleValue() / 100000.0, expected[3]);
                }
            }
            if (actor.verifiedStart != phaseStart) {
                System.out.println("GMIT_GAZE verified actor=" + actor.id + " speaker=" + speaker
                        + " phase_start_frame=" + phaseStart
                        + " head_yaw_deg=" + Math.toDegrees(yaw) + " head_pitch_deg=" + Math.toDegrees(pitch));
                actor.verifiedStart = phaseStart;
            }
        }
    }

    public static synchronized void close() {
        for (Actor actor : actors.values()) actor.close();
        actors.clear(); speaker = null;
    }

    private static void near(String actor, String channel, double actual, double expected) {
        if (Math.abs(actual - expected) > Math.toRadians(3)) throw new IllegalStateException("Gaze " + channel + " changed for " + actor + ": " + actual + " expected " + expected);
    }
    private static double clamp(double value, double degrees) { double bound = Math.toRadians(degrees); return Math.max(-bound, Math.min(bound, value)); }
    private static final class Actor {
        final String id;
        final Object avatar, blender, head;
        final Class<?> bapType, fapType;
        Object proxy;
        volatile int start;
        int verifiedStart = -1;
        private final List<Transition> transitions = new ArrayList<>();
        private final List<Nod> nods = new ArrayList<>();

        Actor(String id, Object avatar, Object blender) throws Exception {
            if (avatar == null || blender == null) throw new IllegalStateException("Missing gaze actor chain: " + id);
            this.id = id; this.avatar = avatar; this.blender = blender;
            head = invoke(avatar, "getHeadNode");
            ClassLoader loader = avatar.getClass().getClassLoader();
            bapType = loader.loadClass("greta.core.animation.mpeg4.bap.BAPType");
            fapType = loader.loadClass("greta.core.animation.mpeg4.fap.FAPType");
            currentFrame = loader.loadClass("greta.core.util.time.Timer").getMethod("getCurrentFrameNumber");
        }
        Object bap(String name) { return type(bapType, name); }
        Object fap(String name) { return type(fapType, name); }

        double[] headWorld() throws Exception {
            Object location = invoke(head, "getGlobalCoordinates");
            Object body = invoke(avatar, "getCurrentBAPFrame");
            // Ogre's derived Bone position includes skeleton rotations, but root
            // translation is applied to agentNode outside that skeleton. The
            // Environment avatar is intentionally at zero in the seated scene.
            return new double[] {
                ((Number)invoke(location, "x")).doubleValue() + ((Number)invoke(body, "getValue", bap("HumanoidRoot_tr_lateral"))).doubleValue() / 1000.0,
                ((Number)invoke(location, "y")).doubleValue() + ((Number)invoke(body, "getValue", bap("HumanoidRoot_tr_vertical"))).doubleValue() / 1000.0,
                ((Number)invoke(location, "z")).doubleValue() + ((Number)invoke(body, "getValue", bap("HumanoidRoot_tr_frontal"))).doubleValue() / 1000.0
            };
        }
        synchronized void shift(int now, double[] destination) { shift(now, destination, 18); }
        synchronized void shift(int now, double[] destination, int durationFrames) {
            if (durationFrames <= 0) throw new IllegalArgumentException("Gaze transition duration must be positive");
            double[] from = at(now);
            while (!transitions.isEmpty() && transitions.get(transitions.size() - 1).start >= now) transitions.remove(transitions.size() - 1);
            transitions.add(new Transition(now, from, destination, durationFrames));
            start = now;
        }
        synchronized int phaseStartAt(int frame) {
            Transition selected = null;
            for (Transition candidate : transitions) {
                if (candidate.start > frame) break;
                selected = candidate;
            }
            return selected == null ? -1 : selected.start;
        }
        synchronized double[] at(int frame) {
            Transition selected = null;
            for (Transition candidate : transitions) {
                if (candidate.start > frame) break;
                selected = candidate;
            }
            if (selected == null) return new double[4];
            return selected.at(frame);
        }
        synchronized void nod(int start, int durationFrames, double pitch) {
            if (durationFrames <= 0 || !Double.isFinite(pitch)) throw new IllegalArgumentException("Invalid nod timing or pitch");
            nods.add(new Nod(start, durationFrames, pitch));
        }
        synchronized int nodCount() { return nods.size(); }
        synchronized double[] bodyAngles(int frame) {
            double[] result = at(frame);
            for (Nod nod : nods) result[1] += nod.at(frame);
            return result;
        }
        private static final class Transition {
            final int start, durationFrames;
            final double[] from, to;
            Transition(int start, double[] from, double[] to, int durationFrames) { this.start = start; this.from = from; this.to = to; this.durationFrames = durationFrames; }
            double[] at(int frame) {
            double t = Math.max(0, Math.min(1, (frame - start) / (double)durationFrames));
            t = t * t * (3 - 2 * t);
            double[] result = new double[4];
            for (int i = 0; i < result.length; i++) result[i] = from[i] + (to[i] - from[i]) * t;
            return result;
            }
        }
        private static final class Nod {
            final int start, durationFrames;
            final double pitch;
            Nod(int start, int durationFrames, double pitch) { this.start = start; this.durationFrames = durationFrames; this.pitch = pitch; }
            double at(int frame) {
                double t = (frame - start) / (double)durationFrames;
                return t <= 0 || t >= 1 ? 0 : pitch * .5 * (1.0 - Math.cos(2.0 * Math.PI * t));
            }
        }
        Object face(Object source) throws Exception {
            Object copy = invoke(source, "clone");
            double[] angles = at(((Number)invoke(copy, "getFrameNumber")).intValue());
            for (String side : new String[] {"l", "r"}) {
                invoke(copy, "setMaskAndValue", fap("yaw_" + side + "_eyeball"), true, (int)Math.round(angles[2] * 100000));
                invoke(copy, "setMaskAndValue", fap("pitch_" + side + "_eyeball"), true, (int)Math.round(angles[3] * 100000));
            }
            GazeModiffAdapter.applyFace(id, copy);
            return copy;
        }
        void publishTransition(int now, int end) throws Exception {
            // Cover the bounded speech interval: previously queued speech frames
            // can contain the old gaze. APFrameList merges these gaze-only masks,
            // preserving arms, lips, and blink. Idle actors also need these frames.
            ClassLoader loader = avatar.getClass().getClassLoader();
            Class<?> bodyClass = loader.loadClass("greta.core.animation.mpeg4.bap.BAPFrame");
            Class<?> faceClass = loader.loadClass("greta.core.animation.mpeg4.fap.FAPFrame");
            List<Object> bodies = new ArrayList<>(), faces = new ArrayList<>();
            for (int frame = now + 1; frame <= end; frame++) {
                if (HEAD) { Object body = bodyClass.getConstructor(Integer.TYPE).newInstance(frame); applyBody(id, body); bodies.add(body); }
                if (EYES) faces.add(face(faceClass.getConstructor(Integer.TYPE).newInstance(frame)));
            }
            Object request = loader.loadClass("greta.core.util.id.IDProvider").getMethod("createID", String.class).invoke(null, "gmit-gaze-" + id);
            if (HEAD) invoke(avatar, "performBAPFrames", bodies, request);
            if (EYES) invoke(avatar, "performFAPFrames", faces, request);
        }
        void install() throws Exception {
            if (!EYES) return;
            Class<?> performer = avatar.getClass().getClassLoader().loadClass("greta.core.animation.mpeg4.fap.FAPFramePerformer");
            proxy = Proxy.newProxyInstance(performer.getClassLoader(), new Class<?>[] {performer}, (instance, method, args) -> {
                if (method.getName().equals("equals")) return instance == args[0];
                if (method.getName().equals("hashCode")) return System.identityHashCode(instance);
                if (method.getName().equals("toString")) return "GMIT listener eyes " + id;
                try {
                    if (method.getName().equals("performFAPFrames")) {
                        List<Object> copies = new ArrayList<>();
                        for (Object frame : (List<?>)args[0]) copies.add(face(frame));
                        return invoke(avatar, "performFAPFrames", copies, args[1]);
                    }
                    if (method.getName().equals("performFAPFrame")) return invoke(avatar, "performFAPFrame", face(args[0]), args[1]);
                } catch (Throwable error) { failure = error; throw error; }
                return null;
            });
            invoke(blender, "removeFAPFramePerformer", avatar);
            invoke(blender, "addFAPFramePerformer", proxy);
        }
        void close() {
            if (proxy == null) return;
            try { invoke(blender, "removeFAPFramePerformer", proxy); invoke(blender, "addFAPFramePerformer", avatar); }
            catch (Exception error) { failure = error; }
            proxy = null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object type(Class<?> types, String name) { return Enum.valueOf((Class<Enum>)types.asSubclass(Enum.class), name); }
    private static Object invoke(Object target, String name, Object... arguments) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterTypes().length != arguments.length) continue;
            boolean match = true;
            Class<?>[] types = method.getParameterTypes();
            for (int i = 0; i < types.length; i++) {
                if (!(types[i].isInstance(arguments[i]) || types[i].isPrimitive() && (arguments[i] instanceof Number || arguments[i] instanceof Boolean))) { match = false; break; }
            }
            if (match) return method.invoke(target, arguments);
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }
}
