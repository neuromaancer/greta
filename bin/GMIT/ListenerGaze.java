import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded listener eye/head tracking for the existing seated Autodesk avatars. */
public final class ListenerGaze {
    private static final String[] ACTORS = {"facilitator", "patient-a", "patient-b"};
    private static final Map<String, Actor> actors = new ConcurrentHashMap<>();
    private static final boolean ENABLED = enabled("gmit.gaze.enabled");
    private static final boolean HEAD = enabled("gmit.gaze.head");
    private static final boolean EYES = enabled("gmit.gaze.eyes");
    private static final int FRAME_MILLIS = 40; // Greta Constants.FRAME_DURATION_MILLIS (25 fps).
    private static final int LISTENER_DELAY_MILLIS = 200;
    private static final int MIDPOINT_MIN_MILLIS = 3500;
    private static final int MIDPOINT_TRANSITION_FRAMES = 28;
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
        transition(activeSpeaker, null, now + 1, now + 1, now + 24);
        speaker = activeSpeaker;
    }

    /**
     * Schedule gaze from the Audio's Greta Timer timestamps, never from BML dispatch.
     * The speaker turns at speech start; listeners begin no earlier than 200 ms later.
     */
    public static synchronized void scheduleSpeech(String activeSpeaker, String speakerPeer,
                                                    long audioStartMillis, long audioEndMillis) throws Exception {
        checkHealthy();
        if (actors.isEmpty()) return;
        Actor target = requireActor(activeSpeaker, "speaker");
        Actor peer = requireActor(speakerPeer, "speaker peer");
        if (target == peer) throw new IllegalArgumentException("Speaker cannot gaze at self: " + activeSpeaker);
        if (audioEndMillis <= audioStartMillis || audioEndMillis - audioStartMillis > 15000)
            throw new IllegalArgumentException("Gaze requires a bounded audio interval of at most 15 seconds");
        int now = ((Number)currentFrame.invoke(null)).intValue();
        int speakerFrame = Math.max(now + 1, frameAtOrAfter(audioStartMillis));
        long desiredListenerMillis = audioStartMillis + LISTENER_DELAY_MILLIS;
        int listenerFrame = Math.max(now + 1, frameAtOrAfter(desiredListenerMillis));
        // Do not schedule a listener head switch after a short utterance has ended.
        if ((long)listenerFrame * FRAME_MILLIS >= audioEndMillis) listenerFrame = -1;
        int endFrame = frameAtOrAfter(audioEndMillis);
        transition(activeSpeaker, speakerPeer, speakerFrame, listenerFrame, endFrame);
        long duration = audioEndMillis - audioStartMillis;
        if (duration >= MIDPOINT_MIN_MILLIS) {
            String secondPeer = remainingPeer(activeSpeaker, speakerPeer);
            int midpointFrame = frameAtOrAfter(audioStartMillis + duration / 2);
            scheduleSpeakerSwitch(activeSpeaker, secondPeer, midpointFrame, endFrame);
            System.out.println("GMIT_GAZE speaker_switch speaker=" + activeSpeaker
                    + " first_peer=" + speakerPeer + " second_peer=" + secondPeer
                    + " midpoint_frame=" + midpointFrame
                    + " midpoint_delta_ms=" + ((long)midpointFrame * FRAME_MILLIS - audioStartMillis));
        }
        speaker = activeSpeaker;
        long listenerScheduledMillis = listenerFrame < 0 ? -1 : (long)listenerFrame * FRAME_MILLIS;
        System.out.println("GMIT_GAZE scheduled speaker=" + activeSpeaker + " peer=" + speakerPeer
                + " audio_start_ms=" + audioStartMillis + " audio_end_ms=" + audioEndMillis
                + " speaker_frame=" + speakerFrame + " listener_frame=" + listenerFrame
                + " listener_transition_delta_ms=" + (listenerScheduledMillis < 0 ? -1 : listenerScheduledMillis - audioStartMillis));
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

    /** Schedules independent speaker and listener transitions without touching mouth, blink, or seated pose channels. */
    private static void transition(String activeSpeaker, String speakerPeer, int speakerFrame, int listenerFrame, int endFrame) throws Exception {
        Actor target = requireActor(activeSpeaker, "speaker");
        Actor peer = speakerPeer == null ? null : requireActor(speakerPeer, "speaker peer");
        double[] destination = target.headWorld();
        for (Actor listener : actors.values()) {
            double yaw = 0, pitch = 0;
            int start = listener == target ? speakerFrame : listenerFrame;
            Actor gazeTarget = listener == target ? peer : target;
            if (start < 0) continue;
            if (gazeTarget != null) {
                double[] origin = listener.headWorld();
                double[] lookAt = gazeTarget.headWorld();
                double dx = lookAt[0] - origin[0], dy = lookAt[1] - origin[1], dz = lookAt[2] - origin[2];
                if (Math.hypot(dx, dz) < 0.2) throw new IllegalStateException("Gaze head targets overlap: " + listener.id);
                Object body = invoke(listener.avatar, "getCurrentBAPFrame");
                double rootYaw = ((Number)invoke(body, "getRadianValue", listener.bap("HumanoidRoot_rt_body_turn"))).doubleValue();
                yaw = Math.atan2(dx, dz) - rootYaw;
                yaw = Math.atan2(Math.sin(yaw), Math.cos(yaw));
                pitch = -Math.atan2(dy, Math.hypot(dx, dz));
            }
            double headYaw = HEAD ? clamp(yaw * 0.8, 65) : 0;
            double headPitch = HEAD ? clamp(pitch * 0.7, 20) : 0;
            double eyeYaw = EYES ? clamp(yaw - headYaw, 25) : 0;
            double eyePitch = EYES ? clamp(pitch - headPitch, 15) : 0;
            // Interpolate from the preceding frame so the first nonzero movement
            // is at the requested frame, not one extra 40 ms frame later.
            listener.shift(start - 1, new double[] {headYaw, headPitch, eyeYaw, eyePitch});
            listener.publishTransition(start - 1, Math.max(start + 23, endFrame));
            System.out.println("GMIT_GAZE target actor=" + listener.id + " speaker=" + activeSpeaker
                    + " role=" + (listener == target ? "speaker" : "listener")
                    + " node=" + invoke((gazeTarget == null ? target : gazeTarget).head, "getIdentifier") + " world=" + vector(gazeTarget == null ? destination : gazeTarget.headWorld())
                    + " transition_frame=" + start
                    + " local_yaw_deg=" + Math.toDegrees(yaw) + " local_pitch_deg=" + Math.toDegrees(pitch)
                    + " head_yaw_deg=" + Math.toDegrees(headYaw) + " eye_yaw_deg=" + Math.toDegrees(eyeYaw));
        }
    }

    /** A future speaker-only overlay replaces only that speaker's queued gaze masks. */
    private static void scheduleSpeakerSwitch(String speakerId, String peerId, int midpointFrame, int endFrame) throws Exception {
        Actor speakerActor = requireActor(speakerId, "speaker");
        Actor peer = requireActor(peerId, "speaker peer");
        if (speakerActor == peer || midpointFrame >= endFrame) return;
        // The preceding-frame origin makes the first nonzero value occur at midpointFrame.
        speakerActor.shift(midpointFrame - 1, gazeAngles(speakerActor, peer), MIDPOINT_TRANSITION_FRAMES);
        speakerActor.publishTransition(midpointFrame - 1, endFrame);
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
        double[] angles = actor.at(((Number)invoke(frame, "getFrameNumber")).intValue());
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
            double[] expected = actor.at(now);
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
    private static String vector(double[] value) { return value[0] + "," + value[1] + "," + value[2]; }

    private static final class Actor {
        final String id;
        final Object avatar, blender, head;
        final Class<?> bapType, fapType;
        Object proxy;
        volatile int start;
        int verifiedStart = -1;
        private final List<Transition> transitions = new ArrayList<>();

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
        Object face(Object source) throws Exception {
            Object copy = invoke(source, "clone");
            double[] angles = at(((Number)invoke(copy, "getFrameNumber")).intValue());
            for (String side : new String[] {"l", "r"}) {
                invoke(copy, "setMaskAndValue", fap("yaw_" + side + "_eyeball"), true, (int)Math.round(angles[2] * 100000));
                invoke(copy, "setMaskAndValue", fap("pitch_" + side + "_eyeball"), true, (int)Math.round(angles[3] * 100000));
            }
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
