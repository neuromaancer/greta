import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ThreadFactory;

/**
 * Optional MeaningMiner bridge.  MeaningMiner turns deliberately use the
 * semantic intention -> Planner -> Realizer route once; normal turns keep the
 * existing direct BML reader route.  Mixing the two routes duplicates speech.
 */
public final class MeaningMinerBridge {
    static final String VERSION = "gmit-meaning-miner/2";
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("gmit.meaningminer.enabled", "false"));
    private static final long TIMEOUT_SECONDS = 30L;
    private static final Object LOCK = new Object();
    private static final String DEBUG_NAME = "fml_output_mm.xml";
    private static final String[] REQUIRED = {
        "./Common/Data/wordnet30/dict/data.noun",
        "./Common/Data/stanfordparser/englishPCFG.ser.gz",
        "./Common/Data/opennlp/en-chunker.bin",
        "./Common/Data/MeaningMiner/imageschemaSynset.xml",
        "./Common/Data/MeaningMiner/imageschemaGesture.xml"
    };

    private static volatile Throwable failure;
    private static volatile boolean installed;
    private static final String[] ACTORS = { "facilitator", "patient-a", "patient-b" };
    private static final Map<String, ActorState> ACTOR_STATES = new LinkedHashMap<String, ActorState>();
    private static final Map<String, PreparedTurn> PREPARED = new HashMap<String, PreparedTurn>();
    private static Object extractor;
    private static Method process;
    private static File debugFile;
    private static SavedFile savedDebug;
    private static File receiptDirectory;
    private static volatile boolean timedOut;

    private MeaningMinerBridge() { }

    public static synchronized void install(Map<String, Object> objects, File output) throws Exception {
        if (!ENABLED) return;
        if (installed) throw new IllegalStateException("MeaningMiner bridge is already installed");
        failure = null;
        timedOut = false;
        receiptDirectory = verifyOutput(output);
        try {
            debugFile = new File(System.getProperty("user.dir") + "\\" + DEBUG_NAME);
            savedDebug = SavedFile.capture(debugFile);
            for (String actor : ACTORS) installActor(objects, actor);
            installed = true;
            writeReceipt(receiptDirectory, "installed", Collections.<String>emptyList());
            System.out.println("GMIT_MEANING_MINER installed actors=facilitator,patient-a,patient-b route=planner-realizer-once");
        } catch (Throwable error) {
            failure = unwrap(error);
            restoreActorRoutes();
            restoreDebugAfterFailure();
            throw asException(failure);
        }
    }

    /** Prepares genuine MeaningMiner intentions, but does not synthesize or dispatch them. */
    public static List<String> prepare(String actor, String eventId, String text) throws Exception {
        if (!ENABLED) return Collections.emptyList();
        synchronized (LOCK) {
            checkHealthy();
            ActorState state = actor(actor);
            validateInput(eventId, text);
            String key = key(actor, eventId);
            if (PREPARED.containsKey(key)) throw new IllegalStateException("MeaningMiner event is already prepared: " + key);
            try {
                final String markup = bml(state.loader, eventId, text);
                Object raw = callBounded(new Callable<Object>() {
                    @Override public Object call() throws Exception {
                        try { return state.process.invoke(state.extractor, markup); }
                        catch (InvocationTargetException error) { throw asException(unwrap(error)); }
                    }
                }, "MeaningMiner analysis");
                List<Object> intentions = intentions(raw);
                if (countSpeech(intentions) != 1) throw new IOException("MeaningMiner must return exactly one speech intention for " + key);
                validateMarkers(intentions, markerCount(markup));
                String actualSpeech = speechText(intentions);
                String expectedHash = textHash(text), actualHash = textHash(actualSpeech);
                if (!expectedHash.equals(actualHash)) throw new IOException("MeaningMiner changed speech text for " + key + " expected_sha256=" + expectedHash + " actual_sha256=" + actualHash);
                auditIntentions(actor, eventId, intentions, expectedHash);
                Admission admission = admitIntentions(actor, intentions);
                auditAdmission(actor, eventId, admission);
                List<String> types = imageSchemaTypes(intentions);
                PREPARED.put(key, new PreparedTurn(actor, eventId, text, admission, types, expectedHash));
                restoreDebug();
                System.out.println("GMIT_MEANING_MINER prepared actor=" + actor + " event_id=" + eventId + " schemas=" + types);
                return types;
            } catch (Throwable error) {
                failure = unwrap(error); restoreDebugAfterFailure(); throw asException(failure);
            }
        }
    }

    /** Dispatches a prepared turn exactly once through its actor's Planner. */
    public static void dispatch(String actor, String eventId) throws Exception {
        if (!ENABLED) return;
        synchronized (LOCK) {
            checkHealthy();
            ActorState state = actor(actor);
            PreparedTurn turn = PREPARED.get(key(actor, eventId));
            if (turn == null) throw new IllegalStateException("MeaningMiner event was not prepared: " + key(actor, eventId));
            if (turn.dispatched) throw new IllegalStateException("MeaningMiner event was already dispatched: " + key(actor, eventId));
            state.active = turn;
            try {
                Object id = Class.forName("greta.core.util.id.IDProvider", true, state.loader)
                        .getMethod("createID", String.class).invoke(null, "GMITMeaningMiner-" + eventId);
                Class<?> modeType = Class.forName("greta.core.util.Mode", true, state.loader);
                Class<?> composition = Class.forName("greta.core.util.enums.CompositionType", true, state.loader);
                Object blend = composition.getField("blend").get(null);
                Object mode = modeType.getConstructor(composition).newInstance(blend);
                state.performIntentions.invoke(state.planner, turn.admittedIntentions, id, mode);
                if (!turn.forwarded || turn.speechSignals != 1 || turn.realizerCalls != 1)
                    throw new IOException("MeaningMiner did not produce exactly one routed speech signal for " + key(actor, eventId));
                turn.dispatched = true;
            } catch (Throwable error) {
                failure = unwrap(error); throw asException(failure);
            } finally { state.active = null; }
        }
    }

    public static boolean hasPrepared(String actor, String eventId) { return PREPARED.containsKey(key(actor, eventId)); }
    public static List<String> getSchemaTypes(String actor, String eventId) {
        PreparedTurn turn = PREPARED.get(key(actor, eventId));
        return turn == null ? Collections.<String>emptyList() : turn.schemaTypes;
    }

    /**
     * Extracts only real image-schema types. Empty output is a valid result and
     * does not cause a gesture fallback.
     */
    public static List<String> analyze(String eventId, String text) throws Exception {
        return prepare("facilitator", eventId, text);
    }

    /** Runs extraction in an owned child JVM so a timed-out parser cannot later
     * overwrite the legacy diagnostic after the caller has returned. */
    public static void preflight(File output) throws Exception {
        File directory = verifyOutput(output);
        File diagnostic = new File(System.getProperty("user.dir") + "\\" + DEBUG_NAME);
        File bin = new File(System.getProperty("java.home"), "bin");
        File windowsJava = new File(bin, "java.exe");
        String java = (windowsJava.isFile() ? windowsJava : new File(bin, "java")).getPath();
        runOwnedPreflight(new ProcessBuilder(java, "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"), MeaningMinerBridge.class.getName(),
                "--internal-preflight-child", directory.getAbsolutePath()).inheritIO(), diagnostic, 75000L);
    }

    static void runOwnedPreflight(ProcessBuilder command, File diagnostic, long timeoutMillis) throws Exception {
        SavedFile backup = SavedFile.capture(diagnostic); Process child = null;
        try {
            child = command.start();
            if (!child.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                terminateOwnedChild(child);
                throw new IOException("MeaningMiner preflight child exceeded " + timeoutMillis + " ms and was terminated");
            }
            if (child.exitValue() != 0) throw new IOException("MeaningMiner preflight child failed with exit code " + child.exitValue());
        } finally {
            if (child != null && child.isAlive()) terminateOwnedChild(child);
            if (child == null || !child.isAlive()) backup.restore(diagnostic);
            else throw new IOException("MeaningMiner preflight child could not be terminated; diagnostic was not restored");
        }
    }

    private static void terminateOwnedChild(Process child) throws Exception {
        child.destroy(); if (!child.waitFor(1000L, TimeUnit.MILLISECONDS)) { child.destroyForcibly(); if (!child.waitFor(5000L, TimeUnit.MILLISECONDS)) throw new IOException("Owned MeaningMiner preflight child remained alive after forced termination"); }
    }

    /** Child-only body; its process lifetime bounds parser threads and writes. */
    private static void preflightChild(File output) throws Exception {
        File directory = verifyOutput(output);
        Object manager = staticEnglishManager();
        verifyEnglish(manager);
        synchronized (LOCK) {
            try {
                failure = null;
                timedOut = false;
                receiptDirectory = directory;
                createExtractor(manager);
                List<String> markers = extractForPreflight("meaning-miner-preflight", "I am ready to move toward reducing red meat.");
                writeReceipt(directory, "ready", markers);
                System.out.println("GMIT_MEANING_MINER preflight markers=" + markers);
            } catch (Throwable error) {
                Throwable actual = unwrap(error);
                try { writeReceipt(directory, "failed", Collections.<String>emptyList()); } catch (Exception ignored) { }
                throw asException(actual);
            } finally {
                restoreDebugAfterFailure();
                extractor = null;
                process = null;
                savedDebug = null;
            }
        }
    }

    private static List<String> extractForPreflight(String eventId, String text) throws Exception {
        validateInput(eventId, text);
        Object raw = callBounded(new Callable<Object>() {
            @Override public Object call() throws Exception {
                try { return process.invoke(extractor, bml(extractor.getClass().getClassLoader(), eventId, text)); }
                catch (InvocationTargetException error) { throw asException(unwrap(error)); }
            }
        }, "MeaningMiner preflight");
        List<String> types = imageSchemaTypes(raw);
        restoreDebug();
        return types;
    }

    public static synchronized void checkHealthy() {
        if (failure != null) throw new IllegalStateException("GMIT MeaningMiner bridge failed", failure);
    }

    public static synchronized void close() {
        try {
            if (!timedOut) restoreDebug();
        } catch (Throwable error) {
            if (failure == null) failure = unwrap(error);
        }
        restoreActorRoutes();
        extractor = null;
        process = null;
        PREPARED.clear();
        ACTOR_STATES.clear();
        savedDebug = null;
        installed = false;
    }

    private static void restoreActorRoutes() {
        for (ActorState state : ACTOR_STATES.values()) {
            try { state.remove.invoke(state.planner, state.proxy); state.add.invoke(state.planner, state.realizer); }
            catch (Throwable ignored) { }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || (!"--preflight".equals(args[0]) && !"--internal-preflight-child".equals(args[0]))) {
            throw new IllegalArgumentException("Usage: MeaningMinerBridge --preflight <absolute-output-directory>");
        }
        File output = new File(args[1]);
        if (!output.isAbsolute()) throw new IllegalArgumentException("Preflight output must be absolute");
        if ("--preflight".equals(args[0])) preflight(output); else preflightChild(output);
    }

    private static void createExtractor(final Object manager) throws Exception {
        checkRequiredResources();
        ClassLoader loader = manager.getClass().getClassLoader();
        final Class<?> type = Class.forName("greta.auxiliary.MeaningMiner.ImageSchemaExtractor", true, loader);
        Constructor<?> constructor = null;
        for (Constructor<?> candidate : type.getConstructors()) {
            if (candidate.getParameterTypes().length == 1 && candidate.getParameterTypes()[0].isInstance(manager)) {
                constructor = candidate;
                break;
            }
        }
        if (constructor == null) throw new IllegalStateException("MeaningMiner constructor is incompatible with facilitator CharacterManager");
        final Constructor<?> selected = constructor;
        extractor = callBounded(new Callable<Object>() {
            @Override public Object call() throws Exception {
                try { return selected.newInstance(manager); }
                catch (InvocationTargetException error) { throw asException(unwrap(error)); }
            }
        }, "MeaningMiner initialization");
        process = type.getMethod("processText_2", String.class);
        // Keep exactly the historical MeaningMiner path expression. On Windows
        // this is the runtime bin file; using File(parent, child) would not
        // preserve it on non-Windows diagnostic hosts.
        debugFile = new File(System.getProperty("user.dir") + "\\" + DEBUG_NAME);
        savedDebug = SavedFile.capture(debugFile);
    }

    private static void installActor(Map<String, Object> objects, final String actor) throws Exception {
        Object manager = require(objects, "gmit-" + actor + "-manager");
        Object planner = require(objects, "gmit-" + actor + "-planner");
        final Object realizer = require(objects, "gmit-" + actor + "-realizer");
        verifyEnglish(manager);
        checkRequiredResources();
        final ClassLoader loader = manager.getClass().getClassLoader();
        Class<?> extractorType = Class.forName("greta.auxiliary.MeaningMiner.ImageSchemaExtractor", true, loader);
        Constructor<?> constructor = null;
        for (Constructor<?> candidate : extractorType.getConstructors()) {
            if (candidate.getParameterTypes().length == 1 && candidate.getParameterTypes()[0].isInstance(manager)) { constructor = candidate; break; }
        }
        if (constructor == null) throw new IllegalStateException("MeaningMiner constructor is incompatible with " + actor + " CharacterManager");
        final Constructor<?> selected = constructor;
        Object localExtractor = callBounded(new Callable<Object>() {
            @Override public Object call() throws Exception {
                try { return selected.newInstance(require(objects, "gmit-" + actor + "-manager")); }
                catch (InvocationTargetException error) { throw asException(unwrap(error)); }
            }
        }, "MeaningMiner initialization");
        final ActorState state = new ActorState(actor, loader, planner, realizer, localExtractor,
                extractorType.getMethod("processText_2", String.class));
        Class<?> performer = Class.forName("greta.core.signals.SignalPerformer", true, loader);
        Method remove = planner.getClass().getMethod("removeSignalPerformer", performer);
        Method add = planner.getClass().getMethod("addSignalPerformer", performer);
        remove.invoke(planner, realizer);
        Object proxy = Proxy.newProxyInstance(loader, new Class<?>[] { performer }, new InvocationHandler() {
            @Override public Object invoke(Object ignored, Method method, Object[] args) throws Throwable {
                if (method.getDeclaringClass() == Object.class) {
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(ignored);
                    if ("equals".equals(method.getName())) return ignored == args[0];
                    if ("toString".equals(method.getName())) return "GMITMeaningMinerProxy(" + actor + ")";
                }
                if (!"performSignals".equals(method.getName())) throw new UnsupportedOperationException(method.toString());
                if (state.active == null) throw new IllegalStateException("MeaningMiner planner emitted outside its active turn: " + actor);
                if (state.active.forwarded) throw new IOException("MeaningMiner planner attempted a second Realizer callback for " + key(actor, state.active.eventId));
                @SuppressWarnings("unchecked") List<Object> signals = (List<Object>) args[0];
                List<Object> allowed = filterSignals(state, signals);
                if (state.active.speechSignals != 1) throw new IOException("MeaningMiner planner emitted " + state.active.speechSignals + " speech signals for " + key(actor, state.active.eventId));
                if (!state.active.gestureSignals.isEmpty()) {
                    Class.forName("SeatedScene").getMethod("beginMeaningTurn", String.class, String.class)
                            .invoke(null, actor, state.active.eventId);
                }
                method.invoke(realizer, allowed, args[1], args[2]);
                state.active.realizerCalls++;
                state.active.forwarded = true;
                auditRealized(state, allowed);
                return null;
            }
        });
        state.proxy = proxy; state.add = add; state.remove = remove;
        add.invoke(planner, proxy);
        state.performIntentions = planner.getClass().getMethod("performIntentions", List.class,
                Class.forName("greta.core.util.id.ID", true, loader), Class.forName("greta.core.util.Mode", true, loader));
        ACTOR_STATES.put(actor, state);
        if ("facilitator".equals(actor)) { extractor = localExtractor; process = state.process; }
    }

    private static List<Object> filterSignals(ActorState state, List<Object> signals) throws Exception {
        List<Object> allowed = new ArrayList<Object>();
        for (Object signal : signals) {
            String kind = signal.getClass().getSimpleName();
            if ("SpeechSignal".equals(kind)) { state.active.speechSignals++; allowed.add(signal); }
            else if (kind.contains("Gesture") || kind.contains("Pointing")) {
                Class.forName("SeatedScene").getMethod("applyMeaningStyle", String.class, Object.class)
                        .invoke(null, state.actor, signal);
                state.active.gestureSignals.add(kind); allowed.add(signal);
                System.out.println("GMIT_MEANING_MINER selected actor=" + state.actor + " event_id=" + state.active.eventId + " " + signalAudit(signal));
            }
            else if (kind.contains("Head") || kind.contains("Gaze") || kind.contains("Face") || kind.contains("Torso"))
                System.out.println("GMIT_MEANING_MINER filtered actor=" + state.actor + " event_id=" + state.active.eventId + " signal=" + kind);
            else throw new IOException("MeaningMiner emitted unsupported signal modality: " + kind);
        }
        return allowed;
    }

    static void validateEmissionKinds(List<String> kinds, int callbacks) throws IOException {
        int speeches = 0;
        for (String kind : kinds) {
            if ("SpeechSignal".equals(kind)) speeches++;
            else if (kind.contains("Gesture") || kind.contains("Pointing") || kind.contains("Head") || kind.contains("Gaze") || kind.contains("Face") || kind.contains("Torso")) { }
            else throw new IOException("MeaningMiner emitted unsupported signal modality: " + kind);
        }
        if (speeches != 1 || callbacks != 1) throw new IOException("MeaningMiner requires one speech signal and one Realizer callback");
    }

    private static void auditIntentions(String actor, String eventId, List<Object> values, String speechHash) throws Exception {
        int concrete = 0, total = 0;
        for (Object value : values) {
            String name = stringMethod(value, "getName"), type = stringMethod(value, "getType");
            if ("imageschema".equalsIgnoreCase(name) || "ideationalunit".equalsIgnoreCase(name))
                System.out.println("GMIT_MEANING_MINER raw actor=" + actor + " event_id=" + eventId + " id=" + stringMethod(value, "getId") + " name=" + name + " type=" + type + " start=" + markerAudit(value, "getStart") + " end=" + markerAudit(value, "getEnd"));
            for (String marker : new String[] { "getStart", "getEnd" }) {
                try { Object time = value.getClass().getMethod(marker).invoke(value); total++; if ((Boolean) time.getClass().getMethod("isConcretized").invoke(time)) concrete++; } catch (Exception ignored) { }
            }
        }
        System.out.println("GMIT_MEANING_MINER speech actor=" + actor + " event_id=" + eventId + " normalized_sha256=" + speechHash + " markers_concrete=" + concrete + "/" + total);
    }

    private static String markerAudit(Object value, String method) {
        try {
            Object marker = value.getClass().getMethod(method).invoke(value);
            @SuppressWarnings("unchecked") List<Object> refs = (List<Object>) marker.getClass().getMethod("getReferences").invoke(marker);
            List<String> targets = new ArrayList<String>(); for (Object ref : refs) targets.add(String.valueOf(ref.getClass().getMethod("getTargetName").invoke(ref)));
            return targets.toString();
        } catch (Exception ignored) { return "[]"; }
    }
    private static String signalAudit(Object signal) {
        return "signal=" + signal.getClass().getSimpleName() + " id=" + stringMethod(signal, "getId")
                + " category=" + stringMethod(signal, "getCategory") + " ref=" + stringMethod(signal, "getReference")
                + " phases=" + gestureHands(signal) + " ready=" + signalMarkerAudit(signal, "ready")
                + " stroke-start=" + signalMarkerAudit(signal, "stroke-start") + " stroke=" + signalMarkerAudit(signal, "stroke")
                + " stroke-end=" + signalMarkerAudit(signal, "stroke-end") + " end=" + signalMarkerAudit(signal, "end");
    }
    /** This is only the state visible when Realizer.performSignals returns; later
     * asynchronous frame generation may populate additional gesture details. */
    private static void auditRealized(ActorState state, List<Object> signals) {
        for (Object signal : signals) {
            String kind = signal.getClass().getSimpleName();
            if (kind.contains("Gesture") || kind.contains("Pointing"))
                System.out.println("GMIT_MEANING_MINER realized_snapshot actor=" + state.actor
                        + " event_id=" + state.active.eventId + " " + signalAudit(signal));
        }
    }
    private static String signalMarkerAudit(Object signal, String name) {
        try { return timeAudit(signal.getClass().getMethod("getTimeMarker", String.class).invoke(signal, name)); }
        catch (Exception ignored) { return "unavailable"; }
    }
    private static String timeAudit(Object marker) {
        if (marker == null) return "none";
        try { return "concrete=" + marker.getClass().getMethod("isConcretized").invoke(marker)
                + ",value=" + marker.getClass().getMethod("getValue").invoke(marker)
                + ",refs=" + markerAuditValue(marker); } catch (Exception ignored) { return "unavailable"; }
    }
    private static String markerAuditValue(Object marker) throws Exception {
        @SuppressWarnings("unchecked") List<Object> refs = (List<Object>) marker.getClass().getMethod("getReferences").invoke(marker);
        List<String> targets = new ArrayList<String>(); for (Object ref : refs) targets.add(String.valueOf(ref.getClass().getMethod("getTargetName").invoke(ref)));
        return targets.toString();
    }
    private static String gestureHands(Object signal) {
        try {
            @SuppressWarnings("unchecked") List<Object> phases = (List<Object>) signal.getClass().getMethod("getPhases").invoke(signal);
            boolean left = false, right = false;
            for (Object phase : phases) { left |= phase.getClass().getMethod("getLeftHand").invoke(phase) != null; right |= phase.getClass().getMethod("getRightHand").invoke(phase) != null; }
            return "left=" + left + ",right=" + right + ",count=" + phases.size();
        } catch (Exception ignored) { return "unavailable"; }
    }
    private static String stringMethod(Object value, String method) {
        try { Object result = value.getClass().getMethod(method).invoke(value); return String.valueOf(result); } catch (Exception ignored) { return ""; }
    }
    private static String requiredStringMethod(Object value, String method) throws Exception {
        Object result = value.getClass().getMethod(method).invoke(value);
        return result == null ? "" : String.valueOf(result).trim();
    }
    private static String speechText(List<Object> intentions) throws Exception {
        for (Object value : intentions) if (value.getClass().getName().endsWith("PseudoIntentionSpeech")) {
            StringBuilder out = new StringBuilder(); flatten(value.getClass().getMethod("getSpeechElements").invoke(value), out); return out.toString();
        }
        throw new IOException("MeaningMiner speech intention is missing");
    }
    @SuppressWarnings("unchecked") private static void flatten(Object value, StringBuilder out) {
        if (value instanceof Iterable<?>) { for (Object child : (Iterable<Object>) value) flatten(child, out); }
        else if (value != null && !value.getClass().getName().endsWith("TimeMarker")) out.append(value);
    }
    private static String textHash(String text) throws Exception { return hashBytes(normalize(text).getBytes(StandardCharsets.UTF_8)); }
    private static String normalize(String text) { return text.trim().replaceAll("\\s+", " "); }

    private static ActorState actor(String actor) {
        if (!installed) throw new IllegalStateException("Install MeaningMiner before use");
        ActorState state = ACTOR_STATES.get(actor);
        if (state == null) throw new IllegalArgumentException("Unknown MeaningMiner actor: " + actor);
        return state;
    }

    private static String key(String actor, String eventId) { return actor + "\u0000" + eventId; }
    private static List<Object> intentions(Object raw) throws IOException {
        if (!(raw instanceof Iterable<?>)) throw new IOException("MeaningMiner did not return an intention list");
        List<Object> result = new ArrayList<Object>(); for (Object value : (Iterable<?>) raw) result.add(value); return result;
    }
    /**
     * Applies an optional semantic admission policy before Planner mutates its
     * input list.  The extracted list itself is retained unchanged for audit.
     */
    static List<Object> admittedIntentions(String actor, List<Object> values) throws Exception {
        return admitIntentions(actor, values).admittedIntentions;
    }
    private static Admission admitIntentions(String actor, List<Object> values) throws Exception {
        String mode = admissionMode(actor);
        List<Object> raw = Collections.unmodifiableList(new ArrayList<Object>(values));
        if ("all".equals(mode)) return new Admission(mode, raw, new ArrayList<Object>(raw), Collections.<String>emptyList(), Collections.<String>emptyList(), "disabled");

        Set<String> schemaIds = new LinkedHashSet<String>();
        Set<String> anchors = new LinkedHashSet<String>();
        boolean hasIdeationalUnit = false;
        boolean invalid = false;
        for (Object value : raw) {
            String name = requiredStringMethod(value, "getName");
            if ("imageschema".equalsIgnoreCase(name)) {
                String id = requiredStringMethod(value, "getId");
                if (id.isEmpty()) invalid = true;
                else schemaIds.add(id);
            } else if ("ideationalunit".equalsIgnoreCase(name)) {
                hasIdeationalUnit = true;
                String main;
                try { main = requiredStringMethod(value, "getMainIntentionId"); }
                catch (NoSuchMethodException error) { invalid = true; continue; }
                if (main.isEmpty()) invalid = true;
                else anchors.add(main);
            }
        }
        for (String anchor : anchors) if (!schemaIds.contains(anchor)) invalid = true;
        if (!schemaIds.isEmpty() && (!hasIdeationalUnit || anchors.isEmpty())) invalid = true;
        if (invalid) return new Admission(mode, raw, new ArrayList<Object>(raw), new ArrayList<String>(anchors), new ArrayList<String>(schemaIds), "invalid-main-anchor");

        List<Object> admitted = new ArrayList<Object>();
        List<String> admittedIds = new ArrayList<String>();
        for (Object value : raw) {
            String name = requiredStringMethod(value, "getName");
            if (!"imageschema".equalsIgnoreCase(name)) { admitted.add(value); continue; }
            String id = requiredStringMethod(value, "getId");
            if (anchors.contains(id)) { admitted.add(value); admittedIds.add(id); }
        }
        return new Admission(mode, raw, admitted, new ArrayList<String>(anchors), admittedIds, "main-anchors");
    }
    private static String admissionMode(String actor) throws Exception {
        String actorValue = System.getProperty("gmit.meaningminer.gesture.admission." + actor);
        if (actorValue != null) return parseAdmissionMode(actorValue, "system property for " + actor);
        String globalValue = System.getProperty("gmit.meaningminer.gesture.admission");
        if (globalValue != null) return parseAdmissionMode(globalValue, "system property");
        try {
            Class<?> scene = Class.forName("SeatedScene");
            Object configured = scene.getMethod("meaningGestureAdmission", String.class).invoke(null, actor);
            return parseAdmissionMode(configured == null ? "" : String.valueOf(configured), "SeatedScene for " + actor);
        } catch (ClassNotFoundException absent) {
            return "all";
        } catch (InvocationTargetException error) {
            throw asException(unwrap(error));
        }
    }
    private static String parseAdmissionMode(String value, String source) {
        String mode = value.trim().toLowerCase(Locale.ROOT);
        if ("all".equals(mode) || "ideational-main".equals(mode)) return mode;
        throw new IllegalArgumentException("Unsupported MeaningMiner gesture admission mode from " + source + ": " + value);
    }
    private static void auditAdmission(String actor, String eventId, Admission admission) throws Exception {
        List<String> raw = new ArrayList<String>();
        for (Object value : admission.rawIntentions) {
            if ("imageschema".equalsIgnoreCase(requiredStringMethod(value, "getName"))) raw.add(requiredStringMethod(value, "getId"));
        }
        System.out.println("GMIT_MEANING_MINER admission actor=" + actor + " event_id=" + eventId
                + " mode=" + admission.mode + " reason=" + admission.reason
                + " raw_schema_ids=" + raw + " main_anchors=" + admission.mainAnchors
                + " admitted_schema_ids=" + admission.admittedSchemaIds
                + " raw_intentions=" + admission.rawIntentions.size() + " admitted_intentions=" + admission.admittedIntentions.size());
    }
    private static int countSpeech(List<Object> intentions) { int count = 0; for (Object value : intentions) if (value.getClass().getName().endsWith("PseudoIntentionSpeech")) count++; return count; }
    private static int markerCount(String markup) { int count = 0, offset = 0; while ((offset = markup.indexOf("<tm id=", offset)) >= 0) { count++; offset += 7; } return count; }
    private static void validateMarkers(List<Object> intentions, int markerCount) throws Exception {
        for (Object value : intentions) {
            for (String marker : new String[] { "getStart", "getEnd" }) {
                Method method; try { method = value.getClass().getMethod(marker); } catch (NoSuchMethodException ignored) { continue; }
                Object time = method.invoke(value);
                @SuppressWarnings("unchecked") List<Object> references = (List<Object>) time.getClass().getMethod("getReferences").invoke(time);
                for (Object reference : references) {
                    String target = String.valueOf(reference.getClass().getMethod("getTargetName").invoke(reference));
                    java.util.regex.Matcher found = java.util.regex.Pattern.compile("tm(\\d+)").matcher(target);
                    while (found.find()) if (Integer.parseInt(found.group(1)) >= markerCount) throw new IOException("MeaningMiner referenced an unanchored time marker: " + found.group());
                }
            }
        }
    }

    private static Object staticEnglishManager() throws Exception {
        Class<?> manager = Class.forName("greta.core.util.CharacterManager");
        Object value = manager.getMethod("getStaticInstance").invoke(null);
        manager.getMethod("setLanguage", String.class).invoke(value, "EN");
        return value;
    }

    private static void verifyEnglish(Object manager) throws Exception {
        Object language = manager.getClass().getMethod("getLanguage").invoke(manager);
        if (!"EN".equals(String.valueOf(language))) {
            throw new IllegalArgumentException("MeaningMiner bridge supports the existing English parser only; facilitator language is " + language);
        }
    }

    private static void checkRequiredResources() throws IOException {
        for (String name : REQUIRED) {
            File file = new File(name);
            if (!file.isFile() || file.length() <= 0) throw new IOException("MeaningMiner resource is missing or empty: " + name);
        }
    }

    private static File verifyOutput(File output) throws IOException {
        if (output == null || !output.isAbsolute()) throw new IllegalArgumentException("MeaningMiner output directory must be absolute");
        Path path = output.toPath().toRealPath();
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path) || !Files.isWritable(path)) {
            throw new IOException("MeaningMiner output directory must be a writable non-symlink directory: " + output);
        }
        return path.toFile();
    }

    static List<String> imageSchemaTypes(Object raw) throws Exception {
        if (!(raw instanceof Iterable<?>)) throw new IOException("MeaningMiner did not return an intention list");
        Set<String> values = new LinkedHashSet<String>();
        for (Object intention : (Iterable<?>)raw) {
            String name = String.valueOf(intention.getClass().getMethod("getName").invoke(intention));
            Object rawType = intention.getClass().getMethod("getType").invoke(intention);
            if (!"imageschema".equalsIgnoreCase(name) || rawType == null) continue;
            String type = String.valueOf(rawType).trim().toLowerCase(Locale.ROOT);
            if (!type.isEmpty()) values.add(type);
        }
        List<String> result = new ArrayList<String>(values);
        Collections.sort(result);
        return Collections.unmodifiableList(result);
    }

    static String bml(String eventId, String text) {
        validateInput(eventId, text);
        return bmlFromOffsets(eventId, text, fallbackOffsets(text));
    }

    private static String bml(ClassLoader loader, String eventId, String text) throws Exception {
        Class<?> ptb = Class.forName("edu.stanford.nlp.process.PTBTokenizer", true, loader);
        // The bundled stanford-parser.jar exposes this legacy CoreLabel factory,
        // rather than the newer newCoreLabelTokenizerFactory API.
        Object tokenizer = ptb.getMethod("newPTBTokenizer", java.io.Reader.class, Boolean.TYPE, Boolean.TYPE)
                .invoke(null, new StringReader(text), Boolean.FALSE, Boolean.FALSE);
        @SuppressWarnings("unchecked") List<Object> labels = (List<Object>) tokenizer.getClass().getMethod("tokenize").invoke(tokenizer);
        List<int[]> offsets = new ArrayList<int[]>();
        for (Object label : labels) offsets.add(new int[] { ((Number) label.getClass().getMethod("beginPosition").invoke(label)).intValue(), ((Number) label.getClass().getMethod("endPosition").invoke(label)).intValue() });
        return bmlFromOffsets(eventId, text, offsets);
    }

    static String bmlFromOffsets(String eventId, String text, List<int[]> offsets) {
        validateInput(eventId, text);
        StringBuilder speech = new StringBuilder("<tm id=\"tm0\"/>"); int cursor = 0;
        for (int i = 0; i < offsets.size(); i++) {
            int[] offset = offsets.get(i);
            if (offset == null || offset.length != 2 || offset[0] < cursor || offset[1] < offset[0] || offset[1] > text.length()) throw new IllegalArgumentException("Invalid PTB token offsets");
            speech.append(xml(text.substring(cursor, offset[1]))); cursor = offset[1];
            speech.append("<tm id=\"tm").append(i + 1).append("\"/>");
        }
        speech.append(xml(text.substring(cursor))).append("<tm id=\"tm").append(offsets.size() + 1).append("\"/>");
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<bml id=\"" + xml(eventId) + "\">\n"
                + "  <speech id=\"s1\" language=\"english\" start=\"0.0\" text=\"\">\n"
                + "    " + speech + "\n"
                + "  </speech>\n</bml>\n";
    }

    private static List<int[]> fallbackOffsets(String text) {
        List<int[]> result = new ArrayList<int[]>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)?|[^\\s]").matcher(text);
        while (matcher.find()) result.add(new int[] { matcher.start(), matcher.end() });
        return result;
    }

    private static void validateInput(String eventId, String text) {
        if (eventId == null || !eventId.matches("[A-Za-z0-9._:-]{1,160}")) throw new IllegalArgumentException("Invalid MeaningMiner event ID");
        if (text == null || text.trim().isEmpty() || text.length() > 8000) throw new IllegalArgumentException("MeaningMiner text must contain 1 to 8000 characters");
        if (text.indexOf('\u0000') >= 0) throw new IllegalArgumentException("MeaningMiner text contains a NUL character");
    }

    static String xml(String value) {
        StringBuilder result = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '&') result.append("&amp;");
            else if (c == '<') result.append("&lt;");
            else if (c == '>') result.append("&gt;");
            else if (c == '\"') result.append("&quot;");
            else if (c == '\'') result.append("&apos;");
            else if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') throw new IllegalArgumentException("MeaningMiner text contains an XML control character");
            else result.append(c);
        }
        return result.toString();
    }

    private static <T> T callBounded(Callable<T> action, String label) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "gmit-meaning-miner");
                thread.setDaemon(true);
                return thread;
            }
        });
        try {
            Future<T> future = executor.submit(action);
            try { return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS); }
            catch (TimeoutException error) {
                timedOut = true;
                future.cancel(true);
                throw new IOException(label + " exceeded " + TIMEOUT_SECONDS + " seconds; stop the owned runtime process before restoring the legacy diagnostic file", error);
            }
            catch (ExecutionException error) { throw asException(unwrap(error)); }
        } finally {
            executor.shutdownNow();
        }
    }

    private static Object require(Map<String, Object> objects, String id) {
        Object value = objects.get(id);
        if (value == null) throw new IllegalStateException("Missing Modular object: " + id);
        return value;
    }

    private static Throwable unwrap(Throwable error) {
        while (error instanceof InvocationTargetException || error instanceof ExecutionException) {
            Throwable next = error.getCause();
            if (next == null) break;
            error = next;
        }
        return error;
    }

    private static Exception asException(Throwable error) {
        if (error instanceof Exception) return (Exception)error;
        return new IllegalStateException(error);
    }

    private static void restoreDebug() throws IOException {
        if (savedDebug != null) savedDebug.restore(debugFile);
    }

    private static void restoreDebugQuietly() {
        try { restoreDebug(); } catch (Exception ignored) { }
    }

    /** A legacy parser may ignore interruption and write after a timed-out call. */
    private static void restoreDebugAfterFailure() {
        if (!timedOut) restoreDebugQuietly();
    }

    private static void writeReceipt(File output, String status, List<String> markers) throws Exception {
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"schema_version\": \"1\",\n  \"bridge\": \"").append(VERSION)
                .append("\",\n  \"status\": \"").append(status).append("\",\n  \"module_code_source\": \"")
                .append(xmlJson(codeSource())).append("\",\n  \"resources\": [");
        for (int i = 0; i < REQUIRED.length; i++) {
            File file = new File(REQUIRED[i]);
            if (i > 0) json.append(',');
            json.append("\n    {\"path\": \"").append(xmlJson(REQUIRED[i])).append("\", \"sha256\": \"")
                    .append(hash(file)).append("\"}");
        }
        json.append("\n  ],\n  \"markers\": [");
        for (int i = 0; i < markers.size(); i++) {
            if (i > 0) json.append(',');
            json.append("\"").append(xmlJson(markers.get(i))).append("\"");
        }
        json.append("]\n}\n");
        Path target = new File(output, "meaning-miner-preflight.json").toPath();
        Path temporary = new File(output, "meaning-miner-preflight.json.tmp").toPath();
        Files.write(temporary, json.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String codeSource() throws Exception {
        if (extractor == null) return "unavailable";
        URL url = extractor.getClass().getProtectionDomain().getCodeSource().getLocation();
        return url == null ? "unknown" : url.toString();
    }

    private static String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = Files.readAllBytes(file.toPath());
        return hashBytes(bytes);
    }

    private static String hashBytes(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest(bytes)) jsonHexAppend(result, value);
        return result.toString();
    }

    private static void jsonHexAppend(StringBuilder output, byte value) {
        output.append(Character.forDigit((value >>> 4) & 15, 16));
        output.append(Character.forDigit(value & 15, 16));
    }

    private static String xmlJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n");
    }

    private static final class SavedFile {
        private final boolean existed;
        private final byte[] bytes;
        private SavedFile(boolean existed, byte[] bytes) { this.existed = existed; this.bytes = bytes; }
        static SavedFile capture(File file) throws IOException {
            if (!file.exists()) return new SavedFile(false, null);
            if (!file.isFile() || Files.isSymbolicLink(file.toPath())) throw new IOException("MeaningMiner diagnostic path is not a regular file: " + file);
            return new SavedFile(true, Files.readAllBytes(file.toPath()));
        }
        void restore(File file) throws IOException {
            if (existed) Files.write(file.toPath(), bytes);
            else Files.deleteIfExists(file.toPath());
        }
    }

    private static final class ActorState {
        final String actor; final ClassLoader loader; final Object planner, realizer, extractor; final Method process;
        Method performIntentions, add, remove; Object proxy; PreparedTurn active;
        ActorState(String actor, ClassLoader loader, Object planner, Object realizer, Object extractor, Method process) {
            this.actor = actor; this.loader = loader; this.planner = planner; this.realizer = realizer; this.extractor = extractor; this.process = process;
        }
    }
    private static final class Admission {
        final String mode, reason;
        final List<Object> rawIntentions, admittedIntentions;
        final List<String> mainAnchors, admittedSchemaIds;
        Admission(String mode, List<Object> rawIntentions, List<Object> admittedIntentions,
                List<String> mainAnchors, List<String> admittedSchemaIds, String reason) {
            this.mode = mode; this.reason = reason;
            this.rawIntentions = rawIntentions;
            this.admittedIntentions = admittedIntentions;
            this.mainAnchors = Collections.unmodifiableList(new ArrayList<String>(mainAnchors));
            this.admittedSchemaIds = Collections.unmodifiableList(new ArrayList<String>(admittedSchemaIds));
        }
    }
    private static final class PreparedTurn {
        final String actor, eventId, text; final List<Object> rawIntentions, admittedIntentions; final List<String> schemaTypes; final List<String> gestureSignals = new ArrayList<String>();
        boolean dispatched, forwarded; int speechSignals, realizerCalls;
        PreparedTurn(String actor, String eventId, String text, Admission admission, List<String> schemaTypes, String ignoredSpeechHash) {
            this.actor = actor; this.eventId = eventId; this.text = text;
            this.rawIntentions = admission.rawIntentions;
            this.admittedIntentions = admission.admittedIntentions;
            this.schemaTypes = Collections.unmodifiableList(new ArrayList<String>(schemaTypes));
        }
    }
}
