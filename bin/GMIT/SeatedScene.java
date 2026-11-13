import java.io.File;
import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** Shared group-MI scenery, seated legs, and optional tabletop arm pose. */
public final class SeatedScene {
    private static final String ROOM_FILE = System.getProperty("gmit.room.file", "./Environments/GMIT/group-mi-room.xml");
    private static final String[] ACTORS = {"facilitator", "patient-a", "patient-b"};
    private static final Set<String> ARM_JOINTS = armJointNames();
    private static final boolean ARMS = Boolean.parseBoolean(System.getProperty("gmit.pose.arms", "true"));
    private static final boolean MEANING_GESTURES = Boolean.parseBoolean(System.getProperty("gmit.meaningminer.enabled", "false"));
    private static final boolean HAND_SAFETY = Boolean.parseBoolean(System.getProperty("gmit.meaningminer.handSafety", "true"));
    private static final boolean REST_VARIATION = Boolean.getBoolean("gmit.rest.variation");
    private static final int REST_FRAMES = SeatedRestPoseLibrary.FRAMES;
    private static final int ARM_LOOKAHEAD_FRAMES = 25; // One second; diagnostic render capture can exceed the 100 ms pump cadence.
    private static final List<PoseController> CONTROLLERS = new ArrayList<PoseController>();
    private static volatile boolean installed;
    private static volatile Throwable poseFailure;
    private static File evidenceDirectory;

    private SeatedScene() { }

    public static synchronized void install(Map<String, Object> objects) throws Exception {
        if (installed) throw new IllegalStateException("Seated scene is already installed");
        if (REST_VARIATION && (!ARMS || !MEANING_GESTURES || !HAND_SAFETY))
            throw new IllegalArgumentException("Rest variation requires seated MeaningMiner and hand safety");
        poseFailure = null;
        RoomLayout layout = readRoom(new File(ROOM_FILE));
        addRoom(require(objects, "gmit-environment"), layout.root);
        applyCamera(require(objects, "gmit-player"), layout);
        try {
            for (String actor : ACTORS) {
                PoseController controller = new PoseController(actor, layout.pose(actor), layout.table,
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

    /** Snapshot the actual three avatar skeletons after the seated hold has rendered. */
    public static void prepareHandSafety(File output) throws Exception {
        evidenceDirectory = output;
        if (!MEANING_GESTURES || !ARMS || !HAND_SAFETY) return;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            final CountDownLatch finished = new CountDownLatch(1);
            final Throwable[] problem = new Throwable[1];
            final boolean[] ready = new boolean[1];
            greta.auxiliary.player.ogre.Ogre.call(new greta.auxiliary.player.ogre.OgreThread.Callback() {
                @Override public void run() {
                    try {
                        for (PoseController controller : CONTROLLERS) if (controller.nativeAgent() == null) return;
                        for (PoseController controller : CONTROLLERS) controller.prepareHandSafety();
                        ready[0] = true;
                    } catch (Throwable failure) { problem[0] = failure; }
                    finally { finished.countDown(); }
                }
            });
            if (!finished.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out capturing Autodesk hand geometry");
            if (problem[0] != null) throw new IllegalStateException("Cannot initialize hand collision protection", problem[0]);
            if (ready[0]) return;
            Thread.sleep(200);
        }
        throw new IllegalStateException("Native Autodesk avatars did not become ready within 60 seconds");
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

    /** Room-owned density policy; standalone semantic extraction retains all intentions. */
    public static synchronized String meaningGestureAdmission(String actor) {
        return installed ? controller(actor).pose.admission : "all";
    }

    /** Change only expressivity; preserve the selected gesture and word markers. */
    public static synchronized void applyMeaningStyle(String actor, Object gesture) throws Exception {
        if (!MEANING_GESTURES || !ARMS || !installed) return;
        MeaningGesture.Style style = controller(actor).pose.style;
        if ("legacy".equals(style.name)) return;
        String[] names = {"setSPC", "setTMP", "setFLD", "setPWR"};
        double[] values = {style.spatial, style.temporal, style.fluidity, style.power};
        for (int i = 0; i < names.length; i++)
            gesture.getClass().getMethod(names[i], Double.TYPE).invoke(gesture, Double.valueOf(values[i]));
        System.out.println("GMIT_MEANING_STYLE actor=" + actor + " style=" + style.name
                + " spatial=" + style.spatial + " temporal=" + style.temporal
                + " fluidity=" + style.fluidity + " power=" + style.power
                + " left_gain=" + style.leftGain + " right_gain=" + style.rightGain);
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

    /** The first speaker uses the tabletop anchor; the two listeners start differently. */
    public static synchronized void setOpeningRests(String firstSpeaker) throws Exception {
        if (!REST_VARIATION) return;
        controller(firstSpeaker); // Validate the actor before changing any pose.
        String[] listenerRests = {"stacked", "mixed"};
        int next = 0;
        for (PoseController controller : CONTROLLERS) {
            String rest = controller.actor.equals(firstSpeaker) ? "tabletop" : listenerRests[next++];
            controller.setOpeningRest(rest);
        }
    }

    public static synchronized long prepareRestForSpeech(String actor, String eventId) throws Exception {
        return REST_VARIATION ? controller(actor).moveToTabletop(eventId) : 0;
    }

    public static synchronized long chooseRestAfterSpeech(String actor, String eventId) throws Exception {
        return REST_VARIATION ? controller(actor).chooseRest(eventId) : 0;
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
                    number(seat, "l_wrist_twisting_degrees", 0), number(seat, "r_wrist_twisting_degrees", 0), readArmOverrides(seat), readGestureOffsets(seat),
                    MeaningGesture.Style.named(seat.hasAttribute("gesture_style") ? seat.getAttribute("gesture_style") : "legacy"),
                    number(seat, "gesture_release_lead", 0),
                    seat.hasAttribute("gesture_admission") ? seat.getAttribute("gesture_admission") : "all",
                    seat.hasAttribute("gesture_return") ? seat.getAttribute("gesture_return") : "workspace",
                    seat.hasAttribute("tabletop_contact") ? seat.getAttribute("tabletop_contact") : "false"));
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
            if ("gesture-arm-offset".equals(child.getNodeName())) continue;
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

    private static Map<String, Double> readGestureOffsets(Element seat) {
        Map<String, Double> offsets = new LinkedHashMap<String, Double>();
        NodeList children = seat.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE || !"gesture-arm-offset".equals(child.getNodeName())) continue;
            Element joint = (Element)child;
            String name = joint.getAttribute("name");
            if (!name.matches("[lr]_(shoulder_(abduct|flexion|twisting)|elbow_flexion)") || offsets.containsKey(name))
                throw new IllegalArgumentException("Invalid or duplicate gesture-arm-offset name: " + name);
            if (!joint.hasAttribute("degrees")) throw new IllegalArgumentException("gesture-arm-offset degrees is required");
            double degrees = number(joint, "degrees", Double.NaN);
            if (!Double.isFinite(degrees) || Math.abs(degrees) > 35)
                throw new IllegalArgumentException("Out-of-range gesture-arm-offset degrees: " + degrees);
            offsets.put(name, Double.valueOf(degrees));
        }
        return offsets;
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
        private final double[] table;
        private final double cameraX, cameraY, cameraZ, cameraPitch;
        RoomLayout(Element root, Map<String, PoseSpec> poses, double cameraX, double cameraY, double cameraZ, double cameraPitch) {
            this.root = root; this.poses = poses; this.cameraX = cameraX; this.cameraY = cameraY; this.cameraZ = cameraZ; this.cameraPitch = cameraPitch;
            this.table = readTableBounds(root);
        }
        PoseSpec pose(String actor) { return poses.get(actor); }
    }

    /** Derive collision geometry from the same min-corner table node as rendering. */
    private static double[] readTableBounds(Element room) {
        Element table = null;
        NodeList nodes = room.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element && "gmit-group-mi-table".equals(((Element)node).getAttribute("id"))) {
                if (table != null) throw new IllegalArgumentException("Duplicate seated table");
                table = (Element)node;
            }
        }
        if (table == null) throw new IllegalArgumentException("Seated collision checks require a table");
        Element position = child(table, "position"), leaf = child(table, "leaf");
        Element size = leaf == null ? null : child(leaf, "size");
        if (position == null || size == null) throw new IllegalArgumentException("Table position and size are required");
        Element orientation = child(table, "orientation"), scaling = child(table, "scale");
        for (String axis : new String[] {"x", "y", "z"})
            if (orientation != null && Math.abs(number(orientation, axis, 0)) > 1e-8)
                throw new IllegalArgumentException("Table boundary requires an axis-aligned table");
        double x = number(position, "x", 0), y = number(position, "y", 0), z = number(position, "z", 0);
        double width = number(size, "x", 0) * (scaling == null ? 1 : scale(scaling, "x"));
        double height = number(size, "y", 0) * (scaling == null ? 1 : scale(scaling, "y"));
        double depth = number(size, "z", 0) * (scaling == null ? 1 : scale(scaling, "z"));
        if (width <= 0 || height <= 0 || depth <= 0) throw new IllegalArgumentException("Table dimensions must be positive");
        return new double[] {x, x + width, z, z + depth, y + height};
    }

    private static final class PoseSpec {
        private final double x, z, yaw, vertical, hip, knee, ankle, sacroiliac, lShoulderFlexion, rShoulderFlexion, lShoulderAbduct, rShoulderAbduct, lElbowFlexion, rElbowFlexion, lElbowTwisting, rElbowTwisting, lWristFlexion, rWristFlexion, lWristPivot, rWristPivot;
        private final double lWristTwisting, rWristTwisting;
        private final Map<String, Double> armOverrides, gestureOffsets;
        private final MeaningGesture.Style style;
        private final double releaseLead;
        private final String admission;
        private final boolean directReturn;
        private final boolean tabletopContact;
        PoseSpec(double x, double z, double yaw, double vertical, double hip, double knee, double ankle, double sacroiliac, double lShoulderFlexion, double rShoulderFlexion, double lShoulderAbduct, double rShoulderAbduct, double lElbowFlexion, double rElbowFlexion, double lElbowTwisting, double rElbowTwisting, double lWristFlexion, double rWristFlexion, double lWristPivot, double rWristPivot, double lWristTwisting, double rWristTwisting, Map<String, Double> armOverrides, Map<String, Double> gestureOffsets, MeaningGesture.Style style, double releaseLead, String admission, String returnMode, String tabletopContact) {
            this.x = x; this.z = z; this.yaw = yaw; this.vertical = vertical;
            this.hip = hip; this.knee = knee; this.ankle = ankle; this.sacroiliac = sacroiliac;
            this.lShoulderFlexion = lShoulderFlexion; this.rShoulderFlexion = rShoulderFlexion; this.lShoulderAbduct = lShoulderAbduct; this.rShoulderAbduct = rShoulderAbduct;
            this.lElbowFlexion = lElbowFlexion; this.rElbowFlexion = rElbowFlexion; this.lElbowTwisting = lElbowTwisting; this.rElbowTwisting = rElbowTwisting; this.lWristFlexion = lWristFlexion; this.rWristFlexion = rWristFlexion; this.lWristPivot = lWristPivot; this.rWristPivot = rWristPivot;
            this.lWristTwisting = lWristTwisting; this.rWristTwisting = rWristTwisting;
            this.armOverrides = Collections.unmodifiableMap(new LinkedHashMap<String, Double>(armOverrides));
            this.gestureOffsets = Collections.unmodifiableMap(new LinkedHashMap<String, Double>(gestureOffsets));
            this.style = style;
            if (releaseLead < 0 || releaseLead > .5) throw new IllegalArgumentException("Invalid gesture release lead");
            this.releaseLead = releaseLead;
            if (!"all".equals(admission) && !"ideational-main".equals(admission))
                throw new IllegalArgumentException("Invalid gesture admission policy: " + admission);
            this.admission = admission;
            if (!"workspace".equals(returnMode) && !"direct".equals(returnMode))
                throw new IllegalArgumentException("Invalid gesture return mode: " + returnMode);
            this.directReturn = "direct".equals(returnMode);
            if (!"true".equals(tabletopContact) && !"false".equals(tabletopContact))
                throw new IllegalArgumentException("Invalid tabletop contact setting: " + tabletopContact);
            this.tabletopContact = "true".equals(tabletopContact);
            if (this.tabletopContact && !this.directReturn)
                throw new IllegalArgumentException("Tabletop contact requires a separated direct-return rest");
        }
        double arm(String name, double fallback) { Double value = armOverrides.get(name); return value == null ? fallback : value.doubleValue(); }
    }

    /** Redirects body frames through a seated lower-body and tabletop-arm overlay without touching face or lip channels. */
    private static final class PoseController {
        private final String actor; private final PoseSpec pose; private final Object avatar, body; private final ClassLoader loader;
        private final double[] table;
        private Object proxy;
        private volatile MeaningGesture.Turn meaningTurn;
        private boolean observedMeaningMovement, observedMeaningRestored;
        private boolean observedMeaningBodyMaskFilter;
        private boolean observedMeaningPending;
        private HandCollision.Model handModel;
        private HandCollision.Model restModel;
        private double handClearance, handContact, fingerPalmContact;
        private Map<String, Double> handBase;
        private final Map<String, List<Map<String, Double>>> restPaths = new LinkedHashMap<>();
        private final Random restRandom;
        private String lastRest = "tabletop";
        private Map<String, Double> idleRest;
        private List<Map<String, Double>> restMovement = Collections.emptyList();
        private int restMovementStart;
        private int lastVerifiedRestFrame = -1;
        private final Map<List<Object>, HandCollision.Result> projectionMemo = new LinkedHashMap<List<Object>, HandCollision.Result>(512, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<List<Object>, HandCollision.Result> eldest) { return size() > 512; }
        };
        private final Map<List<Object>, HandCollision.Result> calibratedOpening = new LinkedHashMap<List<Object>, HandCollision.Result>();
        private Map<List<Object>, HandCollision.Result> checkedReturn = Collections.emptyMap();
        private MeaningGesture.TabletopApproach returnApproach = MeaningGesture.TabletopApproach.DIRECT;
        private final List<Map<String, Double>> handOpeningPath = new ArrayList<Map<String, Double>>();
        private final List<Double> handOpeningWeights = new ArrayList<Double>();
        private final TreeMap<Integer, Map<String, Double>> safePoses = new TreeMap<Integer, Map<String, Double>>();
        private final TreeMap<Integer, Map<String, Double>> candidatePoses = new TreeMap<Integer, Map<String, Double>>();
        private final TreeMap<Integer, Double> requiredClearances = new TreeMap<Integer, Double>();
        private final TreeMap<Integer, Double> requiredFingerPalmClearances = new TreeMap<Integer, Double>();
        private final TreeMap<Integer, String> safetyTrace = new TreeMap<Integer, String>();
        private int safetyObservedFrames;
        private double observedTorso, observedUpperArm, observedTable, observedUpperTorso, observedUpperTable;
        private int safetyLastObservedFrame = -1;
        private long safetyProjectionNanos, safetyMaxBatchNanos, safetyMemoHits, safetySolves;

        private Object nativeAgent() throws Exception {
            Class<?> registry = loader.loadClass("greta.auxiliary.player.ogre.OgreEnvironementListener");
            Object matched = null;
            for (Object agent : (List<?>)registry.getField("agents").get(null)) {
                if (SeatedScene.invoke(agent, "getMPEG4Animatable") == avatar) {
                    if (matched != null) throw new IllegalStateException("Duplicate native avatar for " + actor);
                    matched = agent;
                }
            }
            return matched;
        }

        void prepareHandSafety() throws Exception {
            Object matched = nativeAgent();
            if (matched == null) throw new IllegalStateException("Native Autodesk avatar is unavailable for " + actor);
            handModel = HandCollision.capture(matched);
            Object current = SeatedScene.invoke(avatar, "getCurrentBAPFrame");
            Object snapshot = current.getClass().getMethod("clone").invoke(current);
            Class<?> types = loader.loadClass("greta.core.animation.mpeg4.bap.BAPType");
            Map<String, Double> actual = new LinkedHashMap<String, Double>();
            for (Object channel : types.getEnumConstants()) actual.put(((Enum<?>)channel).name(),
                    Double.valueOf(((Number)SeatedScene.invoke(snapshot, "getDegreeValue", channel)).doubleValue()));
            double residual = handModel.nativeResidual(actual);
            double tolerance = handModel.nativeTolerance(actual);
            handModel = handModel.withFixedPose(actual).withBoundaries(HandCollision.Boundaries.seated(
                    table[0], table[1], table[2], table[3], table[4], pose.x, pose.vertical / 1000.0, pose.z));
            handBase = Collections.unmodifiableMap(armBases());
            if (pose.tabletopContact) handModel = handModel.withTableContactRest(handBase, .03);
            HandCollision.Metrics resting = handModel.measure(handBase);
            System.out.println("GMIT_BODY_SAFETY baseline actor=" + actor + " torso=" + resting.handsToTorso
                    + " upper_arm=" + resting.handsToUpperArms + " table=" + resting.tabletop
                    + " upper_arm_torso=" + resting.upperArmsToTorso + " upper_arm_table=" + resting.upperArmsToTable
                    + " table_bounds=" + Arrays.toString(table)
                    + " tabletop_contact=" + pose.tabletopContact + " contact_release_m=" + (pose.tabletopContact ? .03 : 0));
            if (resting.boundaryMinimum() < -1e-8)
                throw new IllegalStateException("Resting hands violate body/table boundaries for " + actor);
            handClearance = handModel.recommendedClearance(handBase);
            if (pose.directReturn && (resting.minimum() < handClearance || resting.fingerPalm < handClearance))
                throw new IllegalStateException("Direct return requires separated resting hands for " + actor);
            // A separated rest pose needs the normal air gap, not its entire
            // initial hand distance enforced on every semantic gesture.
            handContact = Math.min(handClearance, resting.minimum());
            fingerPalmContact = Math.min(handClearance, resting.fingerPalm);
            System.out.println("GMIT_HAND_SAFETY workspace actor=" + actor + " offsets=" + pose.gestureOffsets);
            Files.write(new File(evidenceDirectory, "hand-skeleton-" + actor + ".txt").toPath(),
                    handModel.debugSnapshot().getBytes(StandardCharsets.UTF_8));
            System.out.println("GMIT_HAND_SAFETY calibration actor=" + actor + " max_fk_error=" + residual + " tolerance=" + tolerance);
            if (!Double.isFinite(residual) || residual > tolerance)
                throw new IllegalStateException("Native hand geometry validation failed for " + actor + ": " + residual);
            try (ObjectOutputStream snapshotFile = new ObjectOutputStream(new FileOutputStream(
                    new File(evidenceDirectory, "hand-skeleton-" + actor + ".ser")))) {
                // Trusted local diagnostic only: no model loader is exposed by this extension.
                snapshotFile.writeObject(handModel);
            }
            prepareOpening();
            if (REST_VARIATION) prepareRestPaths();
            System.out.println("GMIT_HAND_SAFETY ready actor=" + actor + " clearance_native_units=" + handClearance);
        }

        private void prepareOpening() {
            // A checked release also supplies the exact reverse path into the
            // clasp. Returning from an arbitrary nearby arm correction can
            // put the fingers on the wrong side of one another.
            MeaningGesture.Turn opening = new MeaningGesture.Turn(actor, "hand-opening-calibration", 0.35, pose.gestureOffsets, pose.style, pose.releaseLead);
            Map<String, Double> separated = new LinkedHashMap<String, Double>(handBase);
            for (String joint : separated.keySet()) {
                if (joint.contains("_thumb") || joint.contains("_index") || joint.contains("_middle")
                        || joint.contains("_ring") || joint.contains("_pinky")) separated.put(joint, Double.valueOf(0));
            }
            opening.capture(0, separated); opening.bind(0, 1000);
            Map<String, Double> previous = handBase, previousCandidate = handBase;
            double previousClearance = handContact, previousPalmClearance = fingerPalmContact;
            handOpeningPath.clear(); handOpeningWeights.clear(); calibratedOpening.clear();
            handOpeningPath.add(handBase); handOpeningWeights.add(Double.valueOf(0));
            long start = System.nanoTime();
            for (int frame = 1; frame <= MeaningGesture.WORKSPACE_FRAMES; frame++) {
                Map<String, Double> candidate = Collections.unmodifiableMap(opening.poseAt(frame, handBase));
                double weight = opening.clearanceWeightAt(frame);
                double clearance = handContact + (Math.max(handContact, handClearance) - handContact) * weight;
                double palmClearance = fingerPalmContact + (Math.max(fingerPalmContact, handClearance) - fingerPalmContact) * weight;
                HandCollision.Result result = handModel.correct(candidate, handBase, .5,
                        previous, previousCandidate, previousClearance, clearance, previousPalmClearance, palmClearance);
                if (!result.cleared) throw new IllegalStateException("Cannot calibrate checked hand release actor=" + actor + " frame=" + frame);
                calibratedOpening.put(projectionKey("normal", candidate, previous, previousCandidate, handBase,
                        Double.valueOf(.5), Double.valueOf(previousClearance), Double.valueOf(clearance),
                        Double.valueOf(previousPalmClearance), Double.valueOf(palmClearance)), result);
                handOpeningPath.add(result.pose); handOpeningWeights.add(Double.valueOf(weight));
                previous = result.pose; previousCandidate = candidate;
                previousClearance = clearance; previousPalmClearance = palmClearance;
            }
            System.out.println("GMIT_HAND_SAFETY opening_calibrated actor=" + actor + " frames=" + calibratedOpening.size()
                    + " elapsed_ms=" + (System.nanoTime() - start) / 1000000);
        }

        PoseController(String actor, PoseSpec pose, double[] table, Object avatar, Object body) {
            this.actor = actor; this.pose = pose; this.table = table.clone(); this.avatar = avatar; this.body = body; this.loader = avatar.getClass().getClassLoader();
            this.restRandom = new Random(Long.getLong("gmit.rest.seed", 711L).longValue() ^ actor.hashCode());
        }

        private void prepareRestPaths() throws Exception {
            restModel = handModel.withRoundTableRestBoundary();
            Constructor<?> bap = loader.loadClass("greta.core.animation.mpeg4.bap.BAPFrame").getConstructor(Integer.TYPE);
            Class<?> types = loader.loadClass("greta.core.animation.mpeg4.bap.BAPType");
            for (Map.Entry<String, List<Map<String, Double>>> entry : SeatedRestPoseLibrary.paths(actor, handBase).entrySet()) {
                List<Map<String, Double>> poses = new ArrayList<>();
                for (Map<String, Double> partial : entry.getValue()) {
                    if (!ARM_JOINTS.containsAll(partial.keySet()))
                        throw new IllegalArgumentException("Unexpected arm channel in rest " + entry.getKey());
                    Map<String, Double> full = new LinkedHashMap<>(handBase);
                    full.putAll(partial);
                    poses.add(Collections.unmodifiableMap(full));
                }
                if (poses.size() != REST_FRAMES + 1 || !sameArmPose(handBase, poses.get(0)))
                    throw new IllegalArgumentException("Rest path must start at tabletop and contain 30 steps");
                Map<String, Double> previous = quantizedArmPose(poses.get(0), bap, types);
                double minimum = restMargin(previous);
                for (int index = 1; index < poses.size(); index++) {
                    Map<String, Double> current = poses.get(index);
                    Map<String, Double> quantized = quantizedArmPose(current, bap, types);
                    for (String joint : ARM_JOINTS) {
                        double target = current.get(joint).doubleValue();
                        if (!Double.isFinite(target) || Math.abs(target) > 180
                                || Math.abs(quantized.get(joint).doubleValue() - previous.get(joint).doubleValue()) > 6)
                            throw new IllegalStateException("Invalid rest motion actor=" + actor + " pose=" + entry.getKey() + " joint=" + joint);
                    }
                    minimum = Math.min(minimum, restMargin(quantized));
                    minimum = Math.min(minimum, restModel.pathMarginAll(previous, quantized, handClearance, handClearance));
                    previous = quantized;
                }
                if (!(minimum >= -1e-8))
                    throw new IllegalStateException("Rest path clearance failed actor=" + actor + " pose=" + entry.getKey() + " minimum=" + minimum);
                restPaths.put(entry.getKey(), Collections.unmodifiableList(poses));
                System.out.println("GMIT_REST calibrated actor=" + actor + " pose=" + entry.getKey() + " minimum_margin=" + minimum);
            }
            restPaths.put("tabletop", Collections.singletonList(handBase));
        }

        private Map<String, Double> quantizedArmPose(Map<String, Double> pose, Constructor<?> bap, Class<?> types) throws Exception {
            Object frame = bap.newInstance(0);
            for (String joint : ARM_JOINTS) setDegrees(frame, types, joint, pose.get(joint).doubleValue());
            return readArmPose(frame, types);
        }

        private Map<String, Double> readArmPose(Object frame, Class<?> types) throws Exception {
            Map<String, Double> values = new LinkedHashMap<>();
            for (String joint : ARM_JOINTS) values.put(joint, Double.valueOf(degree(frame, types, joint)));
            return values;
        }

        private double restMargin(Map<String, Double> values) {
            HandCollision.Metrics metrics = restModel.measure(values);
            return Math.min(metrics.boundaryMinimum(), Math.min(metrics.minimum(), metrics.fingerPalm) - handClearance);
        }

        private synchronized void setOpeningRest(String rest) throws Exception {
            List<Map<String, Double>> path = restPaths.get(rest);
            if (path == null) throw new IllegalStateException("Missing opening rest " + rest + " for " + actor);
            lastRest = rest;
            idleRest = path.get(path.size() - 1);
            restMovementStart = currentFrame() + 2;
            restMovement = Collections.singletonList(idleRest);
            emitRestFrames(restMovement, restMovementStart);
            System.out.println("GMIT_REST opening actor=" + actor + " pose=" + rest);
        }

        private synchronized long moveToTabletop(String eventId) throws Exception {
            if ("tabletop".equals(lastRest)) return 0;
            List<Map<String, Double>> path = new ArrayList<>(restPaths.get(lastRest));
            Collections.reverse(path);
            return startRestMovement(eventId, "incoming", lastRest, "tabletop", path);
        }

        private synchronized long chooseRest(String eventId) throws Exception {
            List<String> choices = new ArrayList<>(restPaths.keySet());
            choices.remove(lastRest);
            Collections.sort(choices);
            String previous = lastRest;
            String selected = choices.get(restRandom.nextInt(choices.size()));
            List<Map<String, Double>> path = restPaths.get(selected);
            long deadline = path.size() > 1 ? startRestMovement(eventId, "outgoing", "tabletop", selected, path) : 0;
            lastRest = selected;
            System.out.println("GMIT_REST selected actor=" + actor + " event=" + eventId + " prior=" + previous + " pose=" + lastRest);
            return deadline;
        }

        private long startRestMovement(String eventId, String phase, String from, String to,
                                        List<Map<String, Double>> path) throws Exception {
            int now = currentFrame();
            if (meaningTurn != null || (!restMovement.isEmpty() && now < restMovementStart + restMovement.size() - 1))
                throw new IllegalStateException("Arm motion still active for " + actor);
            restMovementStart = now + 2;
            restMovement = path;
            idleRest = path.get(path.size() - 1);
            emitRestFrames(path, restMovementStart);
            int end = restMovementStart + path.size() - 1;
            System.out.println("GMIT_REST transition actor=" + actor + " event=" + eventId + " phase=" + phase
                    + " from=" + from + " to=" + to + " start_frame=" + restMovementStart + " end_frame=" + end);
            return (end + 1L) * MeaningGesture.FRAME_MILLIS;
        }

        private void emitRestFrames(List<Map<String, Double>> poses, int firstFrame) throws Exception {
            Constructor<?> constructor = loader.loadClass("greta.core.animation.mpeg4.bap.BAPFrame").getConstructor(Integer.TYPE);
            Class<?> types = loader.loadClass("greta.core.animation.mpeg4.bap.BAPType");
            List<Object> frames = new ArrayList<>();
            for (Map<String, Double> pose : poses) {
                Object frame = constructor.newInstance(firstFrame + frames.size());
                for (String joint : ARM_JOINTS) setDegrees(frame, types, joint, pose.get(joint).doubleValue());
                frames.add(frame);
            }
            invoke(avatar, "performBAPFrames", frames, newID());
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
            if (turn != null && turn.isBound() && turn.hasRawFrames()) {
                // A later Realizer batch can change the remaining return even
                // when the first projected frame has an identical pose.
                checkedReturn = Collections.emptyMap();
                returnApproach = MeaningGesture.TabletopApproach.DIRECT;
                replayCapturedArms(turn, true);
            }
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
            meaningTurn = new MeaningGesture.Turn(actor, eventId, seatedGestureGain(), pose.gestureOffsets, pose.style, pose.releaseLead);
            meaningTurn.setDirectReturn(pose.directReturn);
            if (REST_VARIATION && pose.directReturn) meaningTurn.setDirectReturnFrames(REST_FRAMES);
            if (HAND_SAFETY) meaningTurn.setHandReturnPath(handOpeningPath, handOpeningWeights);
            checkedReturn = Collections.emptyMap();
            returnApproach = MeaningGesture.TabletopApproach.DIRECT;
            observedMeaningMovement = false;
            observedMeaningRestored = false;
            observedMeaningBodyMaskFilter = false;
            observedMeaningPending = false;
            safePoses.clear(); candidatePoses.clear(); requiredClearances.clear(); requiredFingerPalmClearances.clear(); safetyTrace.clear(); projectionMemo.clear(); safetyObservedFrames = 0; safetyLastObservedFrame = -1;
            observedTorso = observedUpperArm = observedTable = observedUpperTorso = observedUpperTable = Double.POSITIVE_INFINITY;
            projectionMemo.putAll(calibratedOpening);
            safetyProjectionNanos = 0; safetyMaxBatchNanos = 0; safetyMemoHits = 0; safetySolves = 0;
            if (HAND_SAFETY && handModel == null) throw new IllegalStateException("Hand geometry is not ready for " + actor);
            System.out.println("GMIT_MEANING_GESTURE capture_begin actor=" + actor + " event=" + eventId);
        }

        synchronized void bindMeaningTurn(String eventId, long audioStartMillis, long audioEndMillis) {
            MeaningGesture.Turn turn = meaningTurn;
            // MeaningMiner can legitimately return only speech. The bridge
            // binds every MM turn after Audio, but only a selected gesture has
            // called beginMeaningTurn and therefore owns an arm capture.
            if (turn == null || !turn.eventId.equals(eventId)) return;
            turn.bind(audioStartMillis, audioEndMillis);
            try { replayCapturedArms(turn, true); }
            catch (Exception failure) { throw new IllegalStateException("Cannot replay seated MeaningMiner arm frames", failure); }
            System.out.println("GMIT_MEANING_GESTURE bound actor=" + actor + " event=" + eventId
                    + " raw_frames=" + turn.rawFrameCount() + " first_frame=" + turn.firstRawFrame()
                    + " last_frame=" + turn.lastRawFrame() + " audio_end_frame=" + MeaningGesture.frameAtOrAfter(audioEndMillis));
        }

        synchronized void observeMeaningGesture() throws Exception {
            MeaningGesture.Turn turn = meaningTurn;
            if (turn == null || !turn.isBound()) {
                if (turn == null && !restMovement.isEmpty()) observeRestPose();
                return;
            }
            // Keep a bounded future window alive even when the Realizer stops
            // sending body frames during a hold or the return to the clasp.
            replayCapturedArms(turn, false);
            Object current = SeatedScene.invoke(avatar, "getCurrentBAPFrame");
            Object frame = current.getClass().getMethod("clone").invoke(current);
            int frameNumber = ((Number)invoke(frame, "getFrameNumber")).intValue();
            if (HAND_SAFETY && turn.activeAt(frameNumber) && frameNumber != safetyLastObservedFrame && safePoses.containsKey(frameNumber)) {
                Class<?> types = loader.loadClass("greta.core.animation.mpeg4.bap.BAPType");
                Map<String, Double> observed = new LinkedHashMap<String, Double>();
                for (String joint : ARM_JOINTS) observed.put(joint, Double.valueOf(degree(frame, types, joint)));
                HandCollision.Metrics geometry = handModel.measure(observed);
                observedTorso = Math.min(observedTorso, geometry.handsToTorso);
                observedUpperArm = Math.min(observedUpperArm, geometry.handsToUpperArms);
                observedTable = Math.min(observedTable, geometry.tabletop);
                observedUpperTorso = Math.min(observedUpperTorso, geometry.upperArmsToTorso);
                observedUpperTable = Math.min(observedUpperTable, geometry.upperArmsToTable);
                // Degree values are quantized when converted to BAP integers.
                if (geometry.boundaryMinimum() < -handModel.nativeTolerance(observed))
                    throw new IllegalStateException("Observed body/table clearance failed actor=" + actor + " frame=" + frameNumber);
                safetyObservedFrames++; safetyLastObservedFrame = frameNumber;
            }
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

        private void observeRestPose() throws Exception {
            Object current = SeatedScene.invoke(avatar, "getCurrentBAPFrame");
            Object frame = current.getClass().getMethod("clone").invoke(current);
            int frameNumber = ((Number)invoke(frame, "getFrameNumber")).intValue();
            if (frameNumber < restMovementStart || frameNumber == lastVerifiedRestFrame) return;
            Class<?> types = loader.loadClass("greta.core.animation.mpeg4.bap.BAPType");
            assertStaticArms(frame, types);
            double margin = restMargin(readArmPose(frame, types));
            if (!(margin >= -1e-8))
                throw new IllegalStateException("Observed unsafe rest motion actor=" + actor + " frame=" + frameNumber + " margin=" + margin);
            lastVerifiedRestFrame = frameNumber;
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
            writeSafetyTrace(turn);
            if (HAND_SAFETY) System.out.println("GMIT_HAND_SAFETY finished actor=" + actor + " event=" + eventId
                    + " projected_frames=" + safePoses.size() + " observed_frames=" + safetyObservedFrames
                    + " solve_ms=" + safetyProjectionNanos / 1000000 + " max_batch_ms=" + safetyMaxBatchNanos / 1000000
                    + " solves=" + safetySolves + " memo_hits=" + safetyMemoHits
                    + " observed_torso_min=" + observedTorso + " observed_upper_arm_min=" + observedUpperArm + " observed_table_min=" + observedTable
                    + " observed_upper_arm_torso_min=" + observedUpperTorso + " observed_upper_arm_table_min=" + observedUpperTable);
            if (turn.nominalDirectRestoreEndFrame() >= 0)
                System.out.println("GMIT_HAND_SAFETY direct_settle_end actor=" + actor + " event=" + eventId
                        + " nominal_end_frame=" + turn.nominalDirectRestoreEndFrame() + " actual_end_frame=" + turn.restoreEndFrame()
                        + " extension_frames=" + turn.directSettleFrames());
            meaningTurn = null;
            checkedReturn = Collections.emptyMap();
            projectionMemo.clear();
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
                    if (emitted == null) throw new IllegalStateException("Missing emitted Meaning arm frame actor=" + actor
                            + " channel=" + joint + " frame=" + frameNumber + " timer=" + currentFrame()
                            + " first_emitted=" + firstEmitted + " last_emitted=" + turn.lastEmittedFrame());
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
            if (frameNumber > currentFrame() + ARM_LOOKAHEAD_FRAMES) {
                // The observer will supply these arm channels near their due
                // time. Future standing-source masks must not bypass it.
                for (String joint : ARM_JOINTS) invoke(frame, "setMask", type(types, joint), Boolean.FALSE);
                return;
            }
            Map<String, Double> emitted = projectArms(turn, frameNumber);
            for (String joint : ARM_JOINTS) setDegrees(frame, types, joint, emitted.get(joint).doubleValue());
            turn.recordEmitted(frameNumber, emitted);
        }

        private Map<String, Double> armBases() {
            Map<String, Double> base = new LinkedHashMap<String, Double>();
            for (String joint : ARM_JOINTS) base.put(joint, Double.valueOf(armBase(joint)));
            return base;
        }

        private Map<String, Double> projectArms(MeaningGesture.Turn turn, int frame) throws Exception {
            if (HAND_SAFETY && safePoses.containsKey(Integer.valueOf(frame))) return safePoses.get(Integer.valueOf(frame));
            Map<String, Double> base = HAND_SAFETY ? handBase : armBases();
            Map<String, Double> target = turn.poseAt(frame, base);
            if (!HAND_SAFETY) return target;
            double weight = turn.clearanceWeightAt(frame);
            String phase = turn.phaseNameAt(frame);
            // The interlaced release/return retains the calibrated contact
            // depth before requiring an air gap. This is still a checked
            // moving pose, not the solver's exact-rest exemption.
            double motionPhase = "unclasp".equals(phase) || "reclasp".equals(phase) ? .5 : weight;
            String projectionMode = "settling".equals(phase) ? "release-only" : "normal";
            Map.Entry<Integer, Map<String, Double>> prior = safePoses.lowerEntry(Integer.valueOf(frame));
            double clearance = handContact + (Math.max(handContact, handClearance) - handContact) * weight;
            double palmClearance = fingerPalmContact + (Math.max(fingerPalmContact, handClearance) - fingerPalmContact) * weight;
            Map<String, Double> predecessor = prior == null ? base : prior.getValue();
            Map<String, Double> previousCandidate = prior == null ? base : candidatePoses.get(prior.getKey());
            double previousClearance = prior == null ? handContact : requiredClearances.get(prior.getKey()).doubleValue();
            double previousPalmClearance = prior == null ? fingerPalmContact : requiredFingerPalmClearances.get(prior.getKey()).doubleValue();
            if (pose.tabletopContact && "return".equals(phase)) {
                target = turn.poseAt(frame, base, returnApproach);
                List<Object> returnKey = projectionKey(projectionMode, target, predecessor, previousCandidate, base,
                        motionPhase, previousClearance, clearance, previousPalmClearance, palmClearance);
                if (!checkedReturn.containsKey(returnKey)) {
                    checkTabletopReturn(turn, frame, predecessor, previousCandidate);
                    target = turn.poseAt(frame, base, returnApproach);
                }
            }
            // A late raw batch changes the candidate or predecessor key. Exact
            // repeated future holds can reuse a result without repeating FK.
            List<Object> key = projectionKey(projectionMode, Collections.unmodifiableMap(target), predecessor, previousCandidate, base,
                    Double.valueOf(motionPhase), Double.valueOf(previousClearance), Double.valueOf(clearance),
                    Double.valueOf(previousPalmClearance), Double.valueOf(palmClearance));
            HandCollision.Result result = checkedReturn.get(key);
            if (result == null) result = projectionMemo.get(key);
            if (result == null) {
                long started = System.nanoTime();
                if ("settling".equals(phase)) {
                    result = handModel.settleAtBase(base, predecessor, previousCandidate, previousClearance, clearance,
                            previousPalmClearance, palmClearance);
                } else if ("reclasp".equals(phase)) {
                    // The semantic return must reach the verified separated
                    // endpoint exactly before reversing the release. A nearby
                    // collision-free pose can lie on a different finger path.
                    boolean joined = frame != turn.reclaspStartFrame() || sameArmPose(predecessor, target);
                    HandCollision.Metrics measured = handModel.measure(target);
                    boolean safe = joined && measured.minimum() >= clearance - 1e-8
                            && measured.fingerPalm >= palmClearance - 1e-8
                            && measured.boundaryMinimum() >= -1e-8
                            && handModel.pathMarginBoundary(predecessor, target).minimumMargin >= -1e-8
                            && handModel.pathMargin(predecessor, target, previousClearance, clearance).minimumMargin >= -1e-8
                            && handModel.pathMarginFingerPalm(predecessor, target, previousPalmClearance, palmClearance).minimumMargin >= -1e-8;
                    result = new HandCollision.Result(target, measured, measured, safe, 0);
                } else {
                    result = handModel.correct(target, base, motionPhase, predecessor, previousCandidate, previousClearance, clearance,
                            previousPalmClearance, palmClearance);
                }
                safetyProjectionNanos += System.nanoTime() - started; safetySolves++;
            } else safetyMemoHits++;
            if (!result.cleared && "restored".equals(phase) && turn.extendDirectSettling(frame)) {
                // The nominal direct return reached its exact raw rest pose,
                // but the preceding safe correction still needs a bounded
                // one-degree release.  This tail cannot enter the normal
                // six-degree projection search or invent a new escape pose.
                phase = turn.phaseNameAt(frame);
                if (!"settling".equals(phase)) throw new IllegalStateException("Direct settling state did not open");
                weight = turn.clearanceWeightAt(frame);
                motionPhase = 0;
                projectionMode = "release-only";
                key = projectionKey(projectionMode, Collections.unmodifiableMap(target), predecessor, previousCandidate, base,
                        Double.valueOf(motionPhase), Double.valueOf(previousClearance), Double.valueOf(clearance),
                        Double.valueOf(previousPalmClearance), Double.valueOf(palmClearance));
                HandCollision.Result settled = checkedReturn.get(key);
                if (settled == null) settled = projectionMemo.get(key);
                if (settled == null) {
                    long started = System.nanoTime();
                    settled = handModel.settleAtBase(base, predecessor, previousCandidate, previousClearance, clearance,
                            previousPalmClearance, palmClearance);
                    safetyProjectionNanos += System.nanoTime() - started; safetySolves++;
                    projectionMemo.put(key, settled);
                } else safetyMemoHits++;
                result = settled;
                System.out.println("GMIT_HAND_SAFETY direct_settle_begin actor=" + actor + " event=" + turn.eventId
                        + " nominal_end_frame=" + turn.nominalDirectRestoreEndFrame() + " extension_frame=" + frame
                        + " extension_count=" + turn.directSettleFrames() + " max_extension_frames="
                        + MeaningGesture.MAX_DIRECT_SETTLE_FRAMES + " reason=terminal_release_bound");
            }
            if (!result.cleared && "restored".equals(phase)
                    && turn.nominalDirectRestoreEndFrame() >= 0
                    && turn.directSettleFrames() >= MeaningGesture.MAX_DIRECT_SETTLE_FRAMES)
                System.out.println("GMIT_HAND_SAFETY direct_settle_exhausted actor=" + actor + " event=" + turn.eventId
                        + " nominal_end_frame=" + turn.nominalDirectRestoreEndFrame() + " extension_frames="
                        + turn.directSettleFrames() + " reason=terminal_release_bound");
            projectionMemo.put(key, result);
            safetyTrace.put(Integer.valueOf(frame), frame + "," + turn.phaseNameAt(frame) + "," + weight + ","
                    + clearance + "," + result.before.minimum() + "," + result.after.minimum() + ","
                    + result.maxCorrectionDegrees + "," + result.cleared + "," + palmClearance + "," + result.before.fingerPalm + "," + result.after.fingerPalm
                    + "," + result.after.handsToTorso + "," + result.after.handsToUpperArms + "," + result.after.tabletop
                    + "," + result.after.upperArmsToTorso + "," + result.after.upperArmsToTable);
            if (!result.cleared) {
                writeSafetyTrace(turn);
                Files.write(new File(evidenceDirectory, "hand-failed-" + actor + "-" + frame + ".txt").toPath(),
                        ("candidate=" + target + "\nprevious=" + predecessor + "\nprojected=" + result.pose
                        + "\nbase=" + base + "\npreviousCandidate=" + previousCandidate
                        + "\nweight=" + weight + "\npreviousClearance=" + previousClearance + "\nclearance=" + clearance
                        + "\npreviousFingerPalmClearance=" + previousPalmClearance + "\nfingerPalmClearance=" + palmClearance + "\n").getBytes(StandardCharsets.UTF_8));
                throw new IllegalStateException("Hand clearance unresolved actor=" + actor + " event=" + turn.eventId
                        + " frame=" + frame + " phase=" + turn.phaseNameAt(frame) + " required=" + clearance
                        + " measured=" + result.after.minimum() + " palm_required=" + palmClearance + " palm_measured=" + result.after.fingerPalm
                        + " torso=" + result.after.handsToTorso + " upper_arm=" + result.after.handsToUpperArms + " table=" + result.after.tabletop
                        + " upper_arm_torso=" + result.after.upperArmsToTorso + " upper_arm_table=" + result.after.upperArmsToTable);
            }
            safePoses.put(Integer.valueOf(frame), result.pose);
            candidatePoses.put(Integer.valueOf(frame), Collections.unmodifiableMap(target));
            requiredClearances.put(Integer.valueOf(frame), Double.valueOf(clearance));
            requiredFingerPalmClearances.put(Integer.valueOf(frame), Double.valueOf(palmClearance));
            return result.pose;
        }

        /** Check the entire remaining approach before committing to its first frame. */
        private void checkTabletopReturn(MeaningGesture.Turn turn, int start,
                Map<String, Double> predecessor, Map<String, Double> previousCandidate) throws Exception {
            long started = System.nanoTime();
            try {
                for (MeaningGesture.TabletopApproach approach : MeaningGesture.TabletopApproach.values()) {
                    Map<List<Object>, HandCollision.Result> trial = new LinkedHashMap<List<Object>, HandCollision.Result>();
                    if (!checkReturnProfile(turn, start, approach, predecessor, previousCandidate, trial)) continue;
                    checkedReturn = trial;
                    returnApproach = approach;
                    System.out.println("GMIT_HAND_SAFETY return_plan actor=" + actor + " event=" + turn.eventId
                            + " start_frame=" + start + " approach=" + approach
                            + " plan_ms=" + (System.nanoTime() - started) / 1000000);
                    return;
                }
            } finally {
                safetyProjectionNanos += System.nanoTime() - started;
            }
            writeSafetyTrace(turn);
            throw new IllegalStateException("No safe tabletop return to exact rest actor=" + actor
                    + " event=" + turn.eventId + " frame=" + start);
        }

        private boolean checkReturnProfile(MeaningGesture.Turn turn, int start, MeaningGesture.TabletopApproach approach,
                Map<String, Double> predecessor, Map<String, Double> previousCandidate,
                Map<List<Object>, HandCollision.Result> trial) {
            // Separated direct rests have the same hand clearance throughout
            // return and at rest. The tabletop envelope is evaluated per pose.
            int end = turn.nominalDirectRestoreEndFrame();
            for (int frame = start; frame <= end + MeaningGesture.MAX_DIRECT_SETTLE_FRAMES; frame++) {
                boolean terminal = frame >= end;
                double phase = terminal ? 0 : 1;
                Map<String, Double> target = turn.poseAt(frame, handBase, approach);
                List<Object> key = projectionKey("normal", target, predecessor, previousCandidate, handBase,
                        phase, handContact, handContact, fingerPalmContact, fingerPalmContact);
                HandCollision.Result result = handModel.correct(target, handBase, phase, predecessor, previousCandidate,
                        handContact, handContact, fingerPalmContact, fingerPalmContact);
                safetySolves++;
                trial.put(key, result);
                if (terminal && result.cleared) return true;
                if (terminal) {
                    if (frame == end + MeaningGesture.MAX_DIRECT_SETTLE_FRAMES) return false;
                    key = projectionKey("release-only", handBase, predecessor, previousCandidate, handBase,
                            phase, handContact, handContact, fingerPalmContact, fingerPalmContact);
                    result = handModel.settleAtBase(handBase, predecessor, previousCandidate,
                            handContact, handContact, fingerPalmContact, fingerPalmContact);
                    safetySolves++;
                    trial.put(key, result);
                }
                if (!result.cleared) return false;
                predecessor = result.pose;
                previousCandidate = target;
            }
            return false;
        }

        private static boolean sameArmPose(Map<String, Double> left, Map<String, Double> right) {
            if (!left.keySet().equals(right.keySet())) return false;
            for (String joint : left.keySet())
                if (Math.abs(left.get(joint).doubleValue() - right.get(joint).doubleValue()) > 1e-6) return false;
            return true;
        }

        private static List<Object> projectionKey(String mode, Map<String, Double> target,
                Map<String, Double> predecessor, Map<String, Double> previousCandidate, Map<String, Double> base,
                Double motionPhase, Double previousClearance, Double clearance,
                Double previousPalmClearance, Double palmClearance) {
            return Arrays.<Object>asList(mode, target, predecessor, previousCandidate, base, motionPhase,
                    previousClearance, clearance, previousPalmClearance, palmClearance);
        }

        private void writeSafetyTrace(MeaningGesture.Turn turn) throws Exception {
            if (evidenceDirectory != null) Files.write(new File(evidenceDirectory,
                    "hand-raw-" + actor + "-" + turn.firstRawFrame() + ".txt").toPath(),
                    turn.rawTrace().getBytes(StandardCharsets.UTF_8));
            if (!HAND_SAFETY || evidenceDirectory == null) return;
            StringBuilder trace = new StringBuilder("frame,phase,weight,required,before,after,correction_degrees,cleared,finger_palm_required,finger_palm_before,finger_palm_after,torso,upper_arm,table,upper_arm_torso,upper_arm_table\n");
            for (String row : safetyTrace.values()) trace.append(row).append('\n');
            // Actor plus first frame avoids using transcript-controlled text as a path.
            Files.write(new File(evidenceDirectory, "hand-safety-" + actor + "-" + turn.firstRawFrame() + ".csv").toPath(),
                    trace.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder poses = new StringBuilder();
            for (Map.Entry<Integer, Map<String, Double>> entry : safePoses.entrySet()) poses.append(entry.getKey())
                    .append("\tcandidate=").append(candidatePoses.get(entry.getKey())).append("\tprojected=").append(entry.getValue()).append('\n');
            Files.write(new File(evidenceDirectory, "hand-poses-" + actor + "-" + turn.firstRawFrame() + ".txt").toPath(),
                    poses.toString().getBytes(StandardCharsets.UTF_8));
        }
        private void applyStaticArms(Object frame, Class<?> types) throws Exception {
            if (idleRest != null) {
                Map<String, Double> expected = restPoseAt(((Number)invoke(frame, "getFrameNumber")).intValue());
                for (String joint : ARM_JOINTS) setDegrees(frame, types, joint, expected.get(joint).doubleValue());
                return;
            }
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
            if (idleRest != null) {
                Map<String, Double> expected = restPoseAt(((Number)invoke(frame, "getFrameNumber")).intValue());
                for (String joint : ARM_JOINTS) {
                    assertMask(frame, types, joint);
                    assertDegrees(frame, types, joint, expected.get(joint).doubleValue());
                }
                return;
            }
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

        private Map<String, Double> restPoseAt(int frame) {
            if (restMovement.isEmpty()) return idleRest;
            int index = Math.max(0, frame - restMovementStart);
            return restMovement.get(Math.min(index, restMovement.size() - 1));
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
        private void replayCapturedArms(MeaningGesture.Turn turn, boolean refresh) throws Exception {
            long started = System.nanoTime();
            if (!turn.hasRawFrames()) return;
            int now = currentFrame();
            int from = turn.replayStartFrame(now);
            if (!refresh && !safePoses.isEmpty()) from = Math.max(from, safePoses.lastKey().intValue() + 1);
            int to = Math.min(turn.restoreEndFrame(), now + ARM_LOOKAHEAD_FRAMES);
            if (from < 0 || to < from) return;
            if (to - from > 520) throw new IllegalStateException("Meaning arm replay exceeds bounded frame range");
            if (HAND_SAFETY && safePoses.isEmpty()) {
                int opening = Math.max(0, turn.firstRawFrame() + MeaningGesture.BLEND_FRAMES - from);
                System.out.println("GMIT_HAND_SAFETY opening actor=" + actor + " event=" + turn.eventId + " visible_frames=" + opening);
                if (from > turn.firstRawFrame() + MeaningGesture.UNCLASP_FRAMES)
                    throw new IllegalStateException("Meaning arm frames arrived after the unclasp window for " + actor);
            }
            Class<?> frameClass = loader.loadClass("greta.core.animation.mpeg4.bap.BAPFrame");
            Constructor<?> constructor = frameClass.getConstructor(Integer.TYPE);
            Class<?> types = loader.loadClass("greta.core.animation.mpeg4.bap.BAPType");
            List<Object> output = new ArrayList<Object>();
            safePoses.tailMap(Integer.valueOf(from), true).clear();
            candidatePoses.tailMap(Integer.valueOf(from), true).clear();
            requiredClearances.tailMap(Integer.valueOf(from), true).clear();
            requiredFingerPalmClearances.tailMap(Integer.valueOf(from), true).clear();
            safetyTrace.tailMap(Integer.valueOf(from), true).clear();
            for (int number = from; number <= to; number++) {
                Object armFrame = constructor.newInstance(number);
                Map<String, Double> emitted = projectArms(turn, number);
                for (String joint : ARM_JOINTS) setDegrees(armFrame, types, joint, emitted.get(joint).doubleValue());
                turn.recordEmitted(number, emitted);
                output.add(armFrame);
            }
            invoke(avatar, "performBAPFrames", output, newID());
            safetyMaxBatchNanos = Math.max(safetyMaxBatchNanos, System.nanoTime() - started);
        }
        private int safeCurrentFrame() {
            try { return currentFrame(); }
            catch (Exception ignored) { return Integer.MAX_VALUE; }
        }
        private double seatedGestureGain() {
            // Standing-stock shoulder/elbow deltas need a conservative seated
            // retarget. Wrist/finger handshapes remain unscaled in Turn.
            String value = System.getProperty("gmit.meaningminer.seated_arm_gain", "0.24");
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
