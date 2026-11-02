import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** Shared group-MI scenery, seated legs, and optional tabletop arm pose. */
public final class SeatedScene {
    private static final String ROOM_FILE = "./Environments/GMIT/group-mi-room.xml";
    private static final String[] ACTORS = {"facilitator", "patient-a", "patient-b"};
    private static final Set<String> ARM_JOINTS = armJointNames();
    private static final boolean ARMS = Boolean.parseBoolean(System.getProperty("gmit.pose.arms", "true"));
    private static final boolean MEANING_GESTURES = Boolean.parseBoolean(System.getProperty("gmit.meaningminer.enabled", "false"));
    private static final List<PoseController> CONTROLLERS = new ArrayList<PoseController>();
    private static volatile boolean installed;
    private static volatile Throwable poseFailure;

    private SeatedScene() { }

    public static synchronized void install(Map<String, Object> objects) throws Exception {
        if (installed) throw new IllegalStateException("Seated scene is already installed");
        poseFailure = null;
        RoomLayout layout = readRoom(new File(ROOM_FILE));
        addRoom(require(objects, "gmit-environment"), layout.root);
        applyCamera(require(objects, "gmit-player"), layout);
        try {
            for (String actor : ACTORS) {
                PoseController controller = new PoseController(actor, layout.pose(actor),
                        require(objects, "gmit-" + actor + "-avatar"), require(objects, "gmit-" + actor + "-body"));
                controller.resetStaticTransform();
                CONTROLLERS.add(controller);
                controller.install();
            }
            installed = true;
            System.out.println("GMIT_SEATED installed room=" + ROOM_FILE + " actors=" + ACTORS.length);
        } catch (Exception failure) {
            close();
            throw failure;
        }
    }

    /** Non-throwing before installation; throws after an overlay failure. */
    public static void checkHealthy() {
        Throwable failure = poseFailure;
        if (failure != null) throw new IllegalStateException("GMIT seated pose overlay failed", failure);
    }

    /** Checks actual root and leg BAP values after idle or speech body output. */
    public static synchronized void verifyPose() throws Exception {
        checkHealthy();
        if (!installed && CONTROLLERS.isEmpty()) return;
        for (PoseController controller : CONTROLLERS) controller.verify();
    }

    public static synchronized void close() {
        for (PoseController controller : CONTROLLERS) controller.close();
        CONTROLLERS.clear();
        installed = false;
    }

    /**
     * Opens capture for one selected Planner/MeaningMiner gesture realization.
     * The bridge calls this before the actor Realizer emits its body BAP frames.
     */
    public static synchronized void beginMeaningTurn(String actor, String eventId) throws Exception {
        if (!MEANING_GESTURES || !ARMS || !installed) return;
        controller(actor).beginMeaningTurn(eventId);
    }

    /**
     * Binds already-captured raw BAP frames to the actual Audio callback.  This
     * does not retime generated gestures or word timing; it only supplies the
     * end bound for the seated return-to-clasp envelope.
     */
    public static synchronized void bindMeaningTurn(String actor, String eventId,
                                                     long audioStartMillis, long audioEndMillis) throws Exception {
        if (!MEANING_GESTURES || !ARMS || !installed) return;
        controller(actor).bindMeaningTurn(eventId, audioStartMillis, audioEndMillis);
    }

    /**
     * Returns the current bound return-to-clasp deadline in Greta Timer
     * milliseconds, or zero when this event did not select a stock gesture.
     * The playback loop may query repeatedly because late queued BAP frames can
     * legitimately extend the captured gesture tail.
     */
    public static synchronized long meaningRestoreMillis(String actor, String eventId) {
        if (!MEANING_GESTURES || !ARMS || !installed || !isKnownActor(actor)) return 0;
        return controller(actor).meaningRestoreMillis(eventId);
    }

    /** Releases a completed selected turn so later speech-only turns cannot inherit it. */
    public static synchronized void finishMeaningTurn(String actor, String eventId) throws Exception {
        if (!MEANING_GESTURES || !ARMS || !installed) return;
        controller(actor).finishMeaningTurn(eventId);
    }

    /** Records actual per-actor stock BAP movement and the eventual clasp restoration. */
    public static synchronized void observeMeaningGesture() throws Exception {
        if (!MEANING_GESTURES || !installed) return;
        for (PoseController controller : CONTROLLERS) controller.observeMeaningGesture();
    }

    private static PoseController controller(String actor) {
        if (!isKnownActor(actor)) throw new IllegalArgumentException("Unknown GMIT actor: " + actor);
        for (PoseController controller : CONTROLLERS) if (actor.equals(controller.actor)) return controller;
        throw new IllegalStateException("Seated actor is unavailable: " + actor);
    }

    private static Object require(Map<String, Object> objects, String id) {
        Object value = objects.get(id);
        if (value == null) throw new IllegalStateException("Missing Modular object: " + id);
        return value;
    }

    private static RoomLayout readRoom(File room) throws Exception {
        if (!room.isFile()) throw new IllegalArgumentException("Seated room file does not exist: " + room);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> { throw new SAXException("External entities are disabled"); });
        Element root = builder.parse(room).getDocumentElement();
        if (!"node".equals(root.getTagName()) || !"gmit-group-mi-room".equals(root.getAttribute("id"))) {
            throw new IllegalArgumentException("Room root must be gmit-group-mi-room node");
        }
        Map<String, PoseSpec> poses = new HashMap<String, PoseSpec>();
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE || !"seat".equals(child.getNodeName())) continue;
            Element seat = (Element)child;
            String actor = seat.getAttribute("actor_id");
            if (!isKnownActor(actor) || poses.containsKey(actor)) throw new IllegalArgumentException("Invalid or duplicate room seat actor: " + actor);
            poses.put(actor, new PoseSpec(number(seat, "x", 0), number(seat, "z", 0),
                    number(seat, "yaw_degrees", 0), number(seat, "root_vertical_mm", 0), number(seat, "hip_degrees", 0),
                    number(seat, "knee_degrees", 0), number(seat, "ankle_degrees", 0), number(seat, "sacroiliac_degrees", 0),
                    number(seat, "l_shoulder_flexion_degrees", 0), number(seat, "r_shoulder_flexion_degrees", 0),
                    number(seat, "l_shoulder_abduct_degrees", 0), number(seat, "r_shoulder_abduct_degrees", 0),
                    number(seat, "l_elbow_flexion_degrees", 0), number(seat, "r_elbow_flexion_degrees", 0),
                    number(seat, "l_elbow_twisting_degrees", 0), number(seat, "r_elbow_twisting_degrees", 0),
                    number(seat, "l_wrist_flexion_degrees", 0), number(seat, "r_wrist_flexion_degrees", 0),
                    number(seat, "l_wrist_pivot_degrees", 0), number(seat, "r_wrist_pivot_degrees", 0),
                    number(seat, "l_wrist_twisting_degrees", 0), number(seat, "r_wrist_twisting_degrees", 0), readArmOverrides(seat)));
        }
        for (String actor : ACTORS) if (!poses.containsKey(actor)) throw new IllegalArgumentException("Room seat is missing actor: " + actor);
        return new RoomLayout(root, poses, number(root, "camera_x", 0), number(root, "camera_y", 1.5),
                number(root, "camera_z", 1.3), number(root, "camera_pitch_degrees", 0));
    }

    private static boolean isKnownActor(String actor) {
        for (String known : ACTORS) if (known.equals(actor)) return true;
        return false;
    }

    private static Set<String> armJointNames() {
        Set<String> names = new HashSet<String>();
        for (String side : new String[] {"l", "r"}) {
            Collections.addAll(names, side + "_shoulder_flexion", side + "_shoulder_abduct", side + "_shoulder_twisting",
                    side + "_elbow_flexion", side + "_elbow_twisting", side + "_wrist_flexion", side + "_wrist_pivot", side + "_wrist_twisting",
                    side + "_thumb1_flexion", side + "_thumb1_pivot", side + "_thumb1_twisting", side + "_thumb2_flexion", side + "_thumb3_flexion");
            for (String finger : new String[] {"index", "middle", "ring", "pinky"}) {
                Collections.addAll(names, side + "_" + finger + "0_flexion", side + "_" + finger + "1_flexion",
                        side + "_" + finger + "1_pivot", side + "_" + finger + "1_twisting", side + "_" + finger + "2_flexion", side + "_" + finger + "3_flexion");
            }
        }
        return Collections.unmodifiableSet(names);
    }

    private static Map<String, Double> readArmOverrides(Element seat) {
        Map<String, Double> overrides = new LinkedHashMap<String, Double>();
        NodeList children = seat.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            if (!"arm-joint".equals(child.getNodeName())) throw new IllegalArgumentException("Unsupported seat child " + child.getNodeName());
            Element joint = (Element)child;
            String name = joint.getAttribute("name");
            if (!ARM_JOINTS.contains(name) || overrides.containsKey(name)) throw new IllegalArgumentException("Invalid or duplicate arm-joint name: " + name);
            String rawDegrees = joint.getAttribute("degrees");
            if (rawDegrees == null || rawDegrees.trim().isEmpty()) throw new IllegalArgumentException("arm-joint degrees is required");
            double degrees;
            try { degrees = Double.parseDouble(rawDegrees); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid arm-joint degrees: " + rawDegrees, invalid); }
            if (Double.isNaN(degrees) || Double.isInfinite(degrees) || Math.abs(degrees) > 180.0) throw new IllegalArgumentException("Out-of-range arm-joint degrees: " + rawDegrees);
            overrides.put(name, degrees);
        }
        return overrides;
    }

    private static void addRoom(Object environment, Element room) throws Exception {
        ClassLoader loader = environment.getClass().getClassLoader();
        Object roomNode = createNode(room, loader);
        invoke(environment, "addNode", roomNode);
    }

    private static void applyCamera(Object player, RoomLayout layout) throws Exception {
        invoke(player, "setCameraPositionX", layout.cameraX);
        invoke(player, "setCameraPositionY", layout.cameraY);
        invoke(player, "setCameraPositionZ", layout.cameraZ);
        invoke(player, "setCameraPitch", radians(layout.cameraPitch));
    }

    private static Object createNode(Element source, ClassLoader loader) throws Exception {
        Object target = loader.loadClass("greta.core.util.environment.TreeNode").newInstance();
        setIdentifier(target, source.getAttribute("id"));
        Element position = child(source, "position");
        if (position != null) invoke(target, "setCoordinates", number(position, "x", 0), number(position, "y", 0), number(position, "z", 0));
        Element orientation = child(source, "orientation");
        if (orientation != null) invoke(target, "setOrientation", radians(number(orientation, "x", 0)), radians(number(orientation, "y", 0)), radians(number(orientation, "z", 0)));
        Element scale = child(source, "scale");
        if (scale != null) invoke(target, "setScale", scale(scale, "x"), scale(scale, "y"), scale(scale, "z"));
        NodeList children = source.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            Element element = (Element)child;
            Object childObject;
            if ("node".equals(element.getTagName())) childObject = createNode(element, loader);
            else if ("leaf".equals(element.getTagName())) childObject = createLeaf(element, loader);
            else continue; // Seat metadata is not an Environment node.
            invoke(target, "addChildNode", childObject);
        }
        return target;
    }

    private static Object createLeaf(Element source, ClassLoader loader) throws Exception {
        Object target = loader.loadClass("greta.core.util.environment.Leaf").newInstance();
        setIdentifier(target, source.getAttribute("id"));
        invoke(target, "setReference", source.getAttribute("reference"));
        Element size = child(source, "size");
        if (size == null) throw new IllegalArgumentException("Room leaf is missing size: " + source.getAttribute("id"));
        invoke(target, "setSize", number(size, "x", 1), number(size, "y", 1), number(size, "z", 1));
        return target;
    }

    private static Element child(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && name.equals(child.getNodeName())) return (Element)child;
        }
        return null;
    }

    private static double number(Element source, String name, double fallback) {
        String value = source.getAttribute(name);
        if (value == null || value.trim().isEmpty()) return fallback;
        double parsed = Double.parseDouble(value);
        if (Double.isNaN(parsed) || Double.isInfinite(parsed)) throw new IllegalArgumentException("Invalid numeric room value: " + name);
        return parsed;
    }

    private static double scale(Element source, String name) {
        double value = number(source, name, 1);
        if (value <= 0 || value > 4) throw new IllegalArgumentException("Room scale " + name + " must be >0 and <=4");
        return value;
    }

    private static double radians(double degrees) { return Math.toRadians(degrees); }

    private static void setIdentifier(Object target, String value) throws Exception {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("Room object is missing id");
        invoke(target, "setIdentifier", value);
    }

    private static Object invoke(Object target, String name, Object... arguments) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterTypes().length != arguments.length) continue;
            Class<?>[] types = method.getParameterTypes();
            boolean matches = true;
            for (int i = 0; i < types.length; i++) if (!compatible(types[i], arguments[i])) { matches = false; break; }
            if (matches) return method.invoke(target, arguments);
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }

    private static boolean compatible(Class<?> type, Object value) {
        if (value == null) return !type.isPrimitive();
        if (type.isPrimitive()) return value instanceof Number || type == Boolean.TYPE && value instanceof Boolean;
        return type.isInstance(value);
    }

    private static final class RoomLayout {
        private final Element root; private final Map<String, PoseSpec> poses;
        private final double cameraX, cameraY, cameraZ, cameraPitch;
        RoomLayout(Element root, Map<String, PoseSpec> poses, double cameraX, double cameraY, double cameraZ, double cameraPitch) {
            this.root = root; this.poses = poses; this.cameraX = cameraX; this.cameraY = cameraY; this.cameraZ = cameraZ; this.cameraPitch = cameraPitch;
        }
        PoseSpec pose(String actor) { return poses.get(actor); }
    }

    private static final class PoseSpec {
        private final double x, z, yaw, vertical, hip, knee, ankle, sacroiliac, lShoulderFlexion, rShoulderFlexion, lShoulderAbduct, rShoulderAbduct, lElbowFlexion, rElbowFlexion, lElbowTwisting, rElbowTwisting, lWristFlexion, rWristFlexion, lWristPivot, rWristPivot;
        private final double lWristTwisting, rWristTwisting;
        private final Map<String, Double> armOverrides;
        PoseSpec(double x, double z, double yaw, double vertical, double hip, double knee, double ankle, double sacroiliac, double lShoulderFlexion, double rShoulderFlexion, double lShoulderAbduct, double rShoulderAbduct, double lElbowFlexion, double rElbowFlexion, double lElbowTwisting, double rElbowTwisting, double lWristFlexion, double rWristFlexion, double lWristPivot, double rWristPivot, double lWristTwisting, double rWristTwisting, Map<String, Double> armOverrides) {
            this.x = x; this.z = z; this.yaw = yaw; this.vertical = vertical;
            this.hip = hip; this.knee = knee; this.ankle = ankle; this.sacroiliac = sacroiliac;
            this.lShoulderFlexion = lShoulderFlexion; this.rShoulderFlexion = rShoulderFlexion; this.lShoulderAbduct = lShoulderAbduct; this.rShoulderAbduct = rShoulderAbduct;
            this.lElbowFlexion = lElbowFlexion; this.rElbowFlexion = rElbowFlexion; this.lElbowTwisting = lElbowTwisting; this.rElbowTwisting = rElbowTwisting; this.lWristFlexion = lWristFlexion; this.rWristFlexion = rWristFlexion; this.lWristPivot = lWristPivot; this.rWristPivot = rWristPivot;
            this.lWristTwisting = lWristTwisting; this.rWristTwisting = rWristTwisting;
            this.armOverrides = Collections.unmodifiableMap(new LinkedHashMap<String, Double>(armOverrides));
        }
        double arm(String name, double fallback) { Double value = armOverrides.get(name); return value == null ? fallback : value.doubleValue(); }
    }

    /** Redirects body frames through a seated lower-body and tabletop-arm overlay without touching face or lip channels. */
    private static final class PoseController {
        private final String actor; private final PoseSpec pose; private final Object avatar, body; private final ClassLoader loader;
        private Object proxy;
        private volatile MeaningGesture.Turn meaningTurn;
        private boolean observedMeaningMovement, observedMeaningRestored;
        private boolean observedMeaningBodyMaskFilter;
        private boolean observedMeaningPending;

        PoseController(String actor, PoseSpec pose, Object avatar, Object body) {
            this.actor = actor; this.pose = pose; this.avatar = avatar; this.body = body; this.loader = avatar.getClass().getClassLoader();
        }

        /* Root translations are absolute in MPEG4Agent, so only BAP owns placement. */
        void resetStaticTransform() throws Exception {
            SeatedScene.invoke(avatar, "setCoordinates", 0.0, 0.0, 0.0);
            SeatedScene.invoke(avatar, "setOrientation", 0.0, 0.0, 0.0, 1.0);
        }

        void install() throws Exception {
            Class<?> performer = loader.loadClass("greta.core.animation.mpeg4.bap.BAPFramePerformer");
            proxy = Proxy.newProxyInstance(loader, new Class<?>[] {performer}, new InvocationHandler() {
                @Override public Object invoke(Object instance, Method method, Object[] arguments) throws Throwable {
                    if ("equals".equals(method.getName())) return instance == arguments[0];
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(instance);
                    if ("performBAPFrames".equals(method.getName()) && arguments != null && arguments.length == 2) {
                        try { return forward((List<?>)arguments[0], arguments[1]); }
                        catch (Throwable failure) { poseFailure = failure; throw failure; }
                    }
                    if ("toString".equals(method.getName())) return "GMIT seated BAP overlay " + actor;
                    return null;
                }
            });
            invoke(body, "removeBAPFramePerformer", avatar);
            invoke(body, "addBAPFramePerformer", proxy);
            forward(initialHold(), newID());
        }

        void close() {
            if (proxy == null) return;
            try { invoke(body, "removeBAPFramePerformer", proxy); invoke(body, "addBAPFramePerformer", avatar); }
            catch (Exception failure) { poseFailure = failure; }
            finally { proxy = null; }
        }

        private List<Object> initialHold() throws Exception {
            Class<?> frameClass = loader.loadClass("greta.core.animation.mpeg4.bap.BAPFrame");
            Class<?> timerClass = loader.loadClass("greta.core.util.time.Timer");
            int number = ((Number)timerClass.getMethod("getCurrentFrameNumber").invoke(null)).intValue() + 2;
            Constructor<?> constructor = frameClass.getConstructor(Integer.TYPE);
            List<Object> frames = new ArrayList<Object>(); frames.add(constructor.newInstance(number)); return frames;
        }

        private synchronized Object forward(List<?> source, Object id) throws Exception {
            // Capture the whole Realizer batch before deriving any seated frame.
            // The BAP callback races SmokeProbe on a different thread; keeping
            // capture, adaptation, and avatar submission under one controller
            // lock prevents a partially captured turn from being observed.
            for (Object frame : source) captureMeaningArms(frame);
            MeaningGesture.Turn turn = meaningTurn;
            // Refresh all not-yet-current arm frames after every complete raw
            // batch. APFrameList blends same-frame masked channels with the
            // later submission, so this atomically replaces stale projected
            // return values without cancelling unrelated gaze frames.
            if (turn != null && turn.isBound() && turn.hasRawFrames())
                replayCapturedArms(turn);
            List<Object> overlaid = new ArrayList<Object>();
            for (Object frame : source) {
                Object copy = frame.getClass().getMethod("clone").invoke(frame);
                clearMeaningBodyChannels(copy);
                overlay(copy); ListenerGaze.applyBody(actor, copy); overlaid.add(copy);
            }
            return SeatedScene.invoke(avatar, "performBAPFrames", overlaid, id);
        }

        int currentFrame() throws Exception {
            Class<?> timerClass = loader.loadClass("greta.core.util.time.Timer");
            return ((Number)timerClass.getMethod("getCurrentFrameNumber").invoke(null)).intValue();
        }

        synchronized void beginMeaningTurn(String eventId) {
            if (meaningTurn != null && !meaningTurn.restoredAt(safeCurrentFrame()))
                throw new IllegalStateException("Meaning gesture is already active for " + actor);
            meaningTurn = new MeaningGesture.Turn(actor, eventId, seatedGestureGain());
            observedMeaningMovement = false;
            observedMeaningRestored = false;
            observedMeaningBodyMaskFilter = false;
            observedMeaningPending = false;
            System.out.println("GMIT_MEANING_GESTURE capture_begin actor=" + actor + " event=" + eventId);
        }

        synchronized void bindMeaningTurn(String eventId, long audioStartMillis, long audioEndMillis) {
            MeaningGesture.Turn turn = meaningTurn;
            // MeaningMiner can legitimately return only speech. The bridge
            // binds every MM turn after Audio, but only a selected gesture has
            // called beginMeaningTurn and therefore owns an arm capture.
            if (turn == null || !turn.eventId.equals(eventId)) return;
            turn.bind(audioStartMillis, audioEndMillis);
            try { replayCapturedArms(turn); }
            catch (Exception failure) { throw new IllegalStateException("Cannot replay seated MeaningMiner arm frames", failure); }
            System.out.println("GMIT_MEANING_GESTURE bound actor=" + actor + " event=" + eventId
                    + " raw_frames=" + turn.rawFrameCount() + " first_frame=" + turn.firstRawFrame()
                    + " last_frame=" + turn.lastRawFrame() + " audio_end_frame=" + MeaningGesture.frameAtOrAfter(audioEndMillis));
        }

        synchronized void observeMeaningGesture() throws Exception {
            MeaningGesture.Turn turn = meaningTurn;
            if (turn == null || !turn.isBound()) return;
            Object current = SeatedScene.invoke(avatar, "getCurrentBAPFrame");
            Object frame = current.getClass().getMethod("clone").invoke(current);
            int frameNumber = ((Number)invoke(frame, "getFrameNumber")).intValue();
            if (turn.activeAt(frameNumber) && !observedMeaningMovement) {
                Class<?> types = loader.loadClass("greta.core.animation.mpeg4.bap.BAPType");
                double left = degree(frame, types, "l_shoulder_flexion");
                double right = degree(frame, types, "r_shoulder_flexion");
                if (Math.abs(left - arm("l_shoulder_flexion", pose.lShoulderFlexion)) > .5
                        || Math.abs(right - arm("r_shoulder_flexion", pose.rShoulderFlexion)) > .5) {
                    System.out.println("GMIT_MEANING_GESTURE observed actor=" + actor + " event=" + turn.eventId
                            + " frame=" + frameNumber + " left_shoulder_flexion_deg=" + left
                            + " right_shoulder_flexion_deg=" + right);
                    observedMeaningMovement = true;
                }
            }
            if (turn.restoredAt(frameNumber) && !observedMeaningRestored) {
                Class<?> types = loader.loadClass("greta.core.animation.mpeg4.bap.BAPType");
                for (String joint : ARM_JOINTS) assertArm(frame, types, joint, armBase(joint));
                System.out.println("GMIT_MEANING_GESTURE restored actor=" + actor + " event=" + turn.eventId + " frame=" + frameNumber);
                observedMeaningRestored = true;
            }
        }

        synchronized long meaningRestoreMillis(String eventId) {
            MeaningGesture.Turn turn = meaningTurn;
            if (turn == null || !turn.eventId.equals(eventId) || !turn.isBound()) return 0;
            int frame = turn.restoreEndFrame();
            return frame < 0 ? 0 : frame * (long)MeaningGesture.FRAME_MILLIS;
        }

        synchronized void finishMeaningTurn(String eventId) throws Exception {
            MeaningGesture.Turn turn = meaningTurn;
            if (turn == null || !turn.eventId.equals(eventId)) return;
            int frameNumber = currentFrame();
            if (!turn.restoredAt(frameNumber))
                throw new IllegalStateException("Meaning turn cannot finish before restoration actor=" + actor + " event=" + eventId);
            Object current = SeatedScene.invoke(avatar, "getCurrentBAPFrame");
            Object frame = current.getClass().getMethod("clone").invoke(current);
            Class<?> types = loader.loadClass("greta.core.animation.mpeg4.bap.BAPType");
            for (String joint : ARM_JOINTS) assertArm(frame, types, joint, armBase(joint));
            meaningTurn = null;
            System.out.println("GMIT_MEANING_GESTURE finished actor=" + actor + " event=" + eventId + " frame=" + frameNumber);
        }

        private void overlay(Object frame) throws Exception {
            Class<?> typeClass = loader.loadClass("greta.core.animation.mpeg4.bap.BAPType");
            setDegrees(frame, typeClass, "sacroiliac_tilt", pose.sacroiliac);
            setDegrees(frame, typeClass, "l_hip_flexion", pose.hip); setDegrees(frame, typeClass, "r_hip_flexion", pose.hip);
            setDegrees(frame, typeClass, "l_knee_flexion", pose.knee); setDegrees(frame, typeClass, "r_knee_flexion", pose.knee);
            setDegrees(frame, typeClass, "l_ankle_flexion", pose.ankle); setDegrees(frame, typeClass, "r_ankle_flexion", pose.ankle);
            if (ARMS) applySeatedArms(frame, typeClass);
            setValue(frame, typeClass, "HumanoidRoot_tr_lateral", (int)Math.round(pose.x * 1000.0));
            setValue(frame, typeClass, "HumanoidRoot_tr_vertical", (int)Math.round(pose.vertical));
            setValue(frame, typeClass, "HumanoidRoot_tr_frontal", (int)Math.round(pose.z * 1000.0));
            setDegrees(frame, typeClass, "HumanoidRoot_rt_body_turn", pose.yaw);
        }

        /**
         * Stock lexicon gestures are usually authored for a standing avatar.
         * While a selected MeaningMiner turn owns this actor, retain only its
         * arm/finger BAP source masks. SeatedScene regenerates root and legs,
         * and ListenerGaze owns cervical channels after this filter.
         */
        private void clearMeaningBodyChannels(Object frame) throws Exception {
            if (meaningTurn == null) return; // exact non-MM behavior remains unchanged.
            Class<?> types = loader.loadClass("greta.core.animation.mpeg4.bap.BAPType");
            Object[] all = types.getEnumConstants();
            int removed = 0;
            for (Object channel : all) {
                String name = ((Enum<?>)channel).name();
                if (!ARM_JOINTS.contains(name) && ((Boolean)invoke(frame, "getMask", channel)).booleanValue()) {
                    invoke(frame, "setMask", channel, Boolean.FALSE); removed++;
                }
            }
            if (removed > 0 && !observedMeaningBodyMaskFilter) {
                System.out.println("GMIT_MEANING_GESTURE body_mask_filter actor=" + actor + " event=" + meaningTurn.eventId + " removed=" + removed);
                observedMeaningBodyMaskFilter = true;
            }
        }

        synchronized void verify() throws Exception {
            // APFrameList mutates its current frame on the render/timer path.
            // Clone immediately so all checks use one consistent frame number
            // and channel snapshot.
            Object current = SeatedScene.invoke(avatar, "getCurrentBAPFrame");
            Object frame = current.getClass().getMethod("clone").invoke(current);
            Class<?> typeClass = loader.loadClass("greta.core.animation.mpeg4.bap.BAPType");
            assertMask(frame, typeClass, "l_hip_flexion"); assertMask(frame, typeClass, "r_hip_flexion");
            assertMask(frame, typeClass, "l_knee_flexion"); assertMask(frame, typeClass, "r_knee_flexion");
            assertMask(frame, typeClass, "HumanoidRoot_tr_lateral"); assertMask(frame, typeClass, "HumanoidRoot_tr_frontal"); assertMask(frame, typeClass, "HumanoidRoot_tr_vertical");
            assertDegrees(frame, typeClass, "l_hip_flexion", pose.hip); assertDegrees(frame, typeClass, "r_hip_flexion", pose.hip);
            assertDegrees(frame, typeClass, "l_knee_flexion", pose.knee); assertDegrees(frame, typeClass, "r_knee_flexion", pose.knee);
            assertMask(frame, typeClass, "sacroiliac_tilt"); assertDegrees(frame, typeClass, "sacroiliac_tilt", pose.sacroiliac);
            assertMask(frame, typeClass, "l_ankle_flexion"); assertDegrees(frame, typeClass, "l_ankle_flexion", pose.ankle);
            assertMask(frame, typeClass, "r_ankle_flexion"); assertDegrees(frame, typeClass, "r_ankle_flexion", pose.ankle);
            if (ARMS) {
            int frameNumber = ((Number)invoke(frame, "getFrameNumber")).intValue();
            MeaningGesture.Turn turn = meaningTurn;
            if (turn == null || !turn.activeAt(frameNumber)) {
                assertStaticArms(frame, typeClass);
            } else {
                // Compare against the exact arm frame emitted to the avatar,
                // not the mutable raw capture: another partial BAP batch can
                // arrive after this frame has already been queued.
                int firstEmitted = turn.firstEmittedFrame();
                if (firstEmitted < 0 || frameNumber < firstEmitted) {
                    // Raw BAP has been captured, but no arm frame for this
                    // timer instant has been submitted. The accepted clasp is
                    // therefore the only valid expected state, not a guess
                    // from a future mutable gesture plan.
                    assertStaticArms(frame, typeClass);
                    if (!observedMeaningPending) {
                        System.out.println("GMIT_MEANING_GESTURE pending actor=" + actor + " event=" + turn.eventId
                                + " raw_first=" + turn.firstRawFrame() + " current=" + frameNumber + " first_submitted=" + firstEmitted);
                        observedMeaningPending = true;
                    }
                } else for (String joint : ARM_JOINTS) {
                    assertMask(frame, typeClass, joint);
                    Double emitted = turn.emittedValueAt(joint, frameNumber);
                    if (emitted == null) throw new IllegalStateException("Missing emitted Meaning arm frame actor=" + actor + " channel=" + joint + " frame=" + frameNumber);
                    assertDegrees(frame, typeClass, joint, emitted.doubleValue());
                }
            }
            }
            assertMask(frame, typeClass, "HumanoidRoot_rt_body_turn"); assertDegrees(frame, typeClass, "HumanoidRoot_rt_body_turn", pose.yaw);
            assertValue(frame, typeClass, "HumanoidRoot_tr_lateral", (int)Math.round(pose.x * 1000.0));
            assertValue(frame, typeClass, "HumanoidRoot_tr_frontal", (int)Math.round(pose.z * 1000.0));
            assertValue(frame, typeClass, "HumanoidRoot_tr_vertical", (int)Math.round(pose.vertical));
            observeMeaningGesture();
        }

        private Object newID() throws Exception { return loader.loadClass("greta.core.util.id.IDProvider").getMethod("createID", String.class).invoke(null, "gmit-seated-" + actor); }
        private void applySeatedArms(Object frame, Class<?> types) throws Exception {
            int frameNumber = ((Number)invoke(frame, "getFrameNumber")).intValue();
            MeaningGesture.Turn turn = meaningTurn;
            // Preserve the accepted non-MM clasp exactly: its original fixed
            // channels plus explicit room XML overrides, not a newly masked
            // universe of finger channels.
            if (turn == null || !turn.activeAt(frameNumber)) { applyStaticArms(frame, types); return; }
            Map<String, Double> emitted = new LinkedHashMap<String, Double>();
            for (String joint : ARM_JOINTS) {
                double value = turn.hasValueAt(joint, frameNumber) ? turn.valueAt(joint, frameNumber, armBase(joint)) : armBase(joint);
                setDegrees(frame, types, joint, value); emitted.put(joint, Double.valueOf(value));
            }
            turn.recordEmitted(frameNumber, emitted);
        }
        private void applyStaticArms(Object frame, Class<?> types) throws Exception {
            setDegrees(frame, types, "l_shoulder_flexion", pose.lShoulderFlexion); setDegrees(frame, types, "r_shoulder_flexion", pose.rShoulderFlexion);
            setDegrees(frame, types, "l_shoulder_abduct", pose.lShoulderAbduct); setDegrees(frame, types, "r_shoulder_abduct", pose.rShoulderAbduct);
            setDegrees(frame, types, "l_elbow_flexion", pose.lElbowFlexion); setDegrees(frame, types, "r_elbow_flexion", pose.rElbowFlexion);
            setDegrees(frame, types, "l_elbow_twisting", pose.lElbowTwisting); setDegrees(frame, types, "r_elbow_twisting", pose.rElbowTwisting);
            setDegrees(frame, types, "l_wrist_flexion", pose.lWristFlexion); setDegrees(frame, types, "r_wrist_flexion", pose.rWristFlexion);
            setDegrees(frame, types, "l_wrist_pivot", pose.lWristPivot); setDegrees(frame, types, "r_wrist_pivot", pose.rWristPivot);
            setDegrees(frame, types, "l_wrist_twisting", pose.lWristTwisting); setDegrees(frame, types, "r_wrist_twisting", pose.rWristTwisting);
            for (Map.Entry<String, Double> override : pose.armOverrides.entrySet()) setDegrees(frame, types, override.getKey(), override.getValue().doubleValue());
        }
        private void assertStaticArms(Object frame, Class<?> types) throws Exception {
            assertArm(frame, types, "l_shoulder_flexion", pose.lShoulderFlexion);
            assertArm(frame, types, "l_elbow_flexion", pose.lElbowFlexion);
            assertArm(frame, types, "l_elbow_twisting", pose.lElbowTwisting);
            assertArm(frame, types, "l_wrist_pivot", pose.lWristPivot);
            assertArm(frame, types, "l_wrist_twisting", pose.lWristTwisting);
            assertArm(frame, types, "r_shoulder_flexion", pose.rShoulderFlexion);
            assertArm(frame, types, "r_elbow_flexion", pose.rElbowFlexion);
            assertArm(frame, types, "r_elbow_twisting", pose.rElbowTwisting);
            assertArm(frame, types, "r_wrist_pivot", pose.rWristPivot);
            assertArm(frame, types, "r_wrist_twisting", pose.rWristTwisting);
            for (Map.Entry<String, Double> override : pose.armOverrides.entrySet()) assertArm(frame, types, override.getKey(), override.getValue().doubleValue());
        }
        private void captureMeaningArms(Object frame) throws Exception {
            MeaningGesture.Turn turn = meaningTurn;
            if (turn == null) return;
            int frameNumber = ((Number)invoke(frame, "getFrameNumber")).intValue();
            Class<?> types = loader.loadClass("greta.core.animation.mpeg4.bap.BAPType");
            Map<String, Double> captured = new LinkedHashMap<String, Double>();
            for (String joint : ARM_JOINTS) {
                Object jointType = type(types, joint);
                if (((Boolean)invoke(frame, "getMask", jointType)).booleanValue())
                    captured.put(joint, Double.valueOf(degree(frame, types, joint)));
            }
            turn.capture(frameNumber, captured);
        }
        /**
         * Body BAP normally arrives before the Audio callback.  It was already
         * forwarded with the seated clasp, so replay only its filtered arm
         * channels after binding.  The later arm-only batch cannot overwrite
         * root, legs, neck/gaze, face, lips, or blink frames.
         */
        private void replayCapturedArms(MeaningGesture.Turn turn) throws Exception {
            if (!turn.hasRawFrames()) return;
            int from = turn.replayStartFrame(currentFrame());
            int to = turn.restoreEndFrame();
            if (from < 0 || to < from) return;
            if (to - from > 520) throw new IllegalStateException("Meaning arm replay exceeds bounded frame range");
            Class<?> frameClass = loader.loadClass("greta.core.animation.mpeg4.bap.BAPFrame");
            Constructor<?> constructor = frameClass.getConstructor(Integer.TYPE);
            Class<?> types = loader.loadClass("greta.core.animation.mpeg4.bap.BAPType");
            List<Object> output = new ArrayList<Object>();
            for (int number = from; number <= to; number++) {
                Object armFrame = constructor.newInstance(number);
                Map<String, Double> emitted = new LinkedHashMap<String, Double>();
                for (String joint : ARM_JOINTS) {
                    double base = armBase(joint);
                    double value = turn.valueAt(joint, number, base);
                    setDegrees(armFrame, types, joint, value); emitted.put(joint, Double.valueOf(value));
                }
                turn.recordEmitted(number, emitted);
                output.add(armFrame);
            }
            invoke(avatar, "performBAPFrames", output, newID());
        }
        private int safeCurrentFrame() {
            try { return currentFrame(); }
            catch (Exception ignored) { return Integer.MAX_VALUE; }
        }
        private double seatedGestureGain() {
            // Standing-stock shoulder/elbow deltas need a conservative seated
            // retarget. Wrist/finger handshapes remain unscaled in Turn.
            String value = System.getProperty("gmit.meaningminer.seated_arm_gain", "0.35");
            try { return Double.parseDouble(value); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid gmit.meaningminer.seated_arm_gain", invalid); }
        }
        private double armBase(String name) {
            if ("l_shoulder_flexion".equals(name)) return arm(name, pose.lShoulderFlexion);
            if ("r_shoulder_flexion".equals(name)) return arm(name, pose.rShoulderFlexion);
            if ("l_shoulder_abduct".equals(name)) return arm(name, pose.lShoulderAbduct);
            if ("r_shoulder_abduct".equals(name)) return arm(name, pose.rShoulderAbduct);
            if ("l_elbow_flexion".equals(name)) return arm(name, pose.lElbowFlexion);
            if ("r_elbow_flexion".equals(name)) return arm(name, pose.rElbowFlexion);
            if ("l_elbow_twisting".equals(name)) return arm(name, pose.lElbowTwisting);
            if ("r_elbow_twisting".equals(name)) return arm(name, pose.rElbowTwisting);
            if ("l_wrist_flexion".equals(name)) return arm(name, pose.lWristFlexion);
            if ("r_wrist_flexion".equals(name)) return arm(name, pose.rWristFlexion);
            if ("l_wrist_pivot".equals(name)) return arm(name, pose.lWristPivot);
            if ("r_wrist_pivot".equals(name)) return arm(name, pose.rWristPivot);
            if ("l_wrist_twisting".equals(name)) return arm(name, pose.lWristTwisting);
            if ("r_wrist_twisting".equals(name)) return arm(name, pose.rWristTwisting);
            return arm(name, 0);
        }
        private double arm(String name, double fallback) { return pose.arm(name, fallback); }
        private double degree(Object frame, Class<?> types, String name) throws Exception {
            return ((Number)SeatedScene.invoke(frame, "getDegreeValue", type(types, name))).doubleValue();
        }
        @SuppressWarnings({"unchecked", "rawtypes"}) private static Object type(Class<?> types, String name) { return Enum.valueOf((Class<Enum>)types.asSubclass(Enum.class), name); }
        private static void setDegrees(Object frame, Class<?> types, String name, double value) throws Exception { SeatedScene.invoke(frame, "setDegreeValue", type(types, name), value); }
        private static void setValue(Object frame, Class<?> types, String name, int value) throws Exception { SeatedScene.invoke(frame, "setMaskAndValue", type(types, name), true, value); }
        private void assertMask(Object frame, Class<?> types, String name) throws Exception { if (!((Boolean)SeatedScene.invoke(frame, "getMask", type(types, name))).booleanValue()) throw changed(name + ".mask", false, true); }
        private void assertArm(Object frame, Class<?> types, String name, double fallback) throws Exception { assertMask(frame, types, name); assertDegrees(frame, types, name, pose.arm(name, fallback)); }
        private void assertDegrees(Object frame, Class<?> types, String name, double expected) throws Exception { double actual = ((Number)SeatedScene.invoke(frame, "getDegreeValue", type(types, name))).doubleValue(); if (Math.abs(actual - expected) > 0.5) throw changed(name, actual, expected); }
        private void assertValue(Object frame, Class<?> types, String name, int expected) throws Exception { int actual = ((Number)SeatedScene.invoke(frame, "getValue", type(types, name))).intValue(); if (actual != expected) throw changed(name, actual, expected); }
        private IllegalStateException changed(String channel, Object actual, Object expected) { return new IllegalStateException("GMIT seated pose changed actor=" + actor + " channel=" + channel + " actual=" + actual + " expected=" + expected); }
    }
}
