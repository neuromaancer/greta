import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Pattern;

/** Opt-in virtual-AU engineering bridge; not a calibrated human OpenFace source. */
public final class GazeModiffAdapter {
    static final String VERSION = "gmit-modiff-vq/1";
    static final int HISTORY = 144, PERIOD = 18, MAX_AGE = 75;
    static final int[] AUS = {1,2,4,5,6,7,9,10,12,14,15,17,20,23,25,26,45};
    static final String[] BROWS = {"raise_l_i_eyebrow", "raise_r_i_eyebrow",
        "raise_l_m_eyebrow", "raise_r_m_eyebrow", "raise_l_o_eyebrow",
        "raise_r_o_eyebrow", "squeeze_l_eyebrow", "squeeze_r_eyebrow"};
    private static final boolean ENABLED = !System.getProperty("gmit.modiff.dir", "").trim().isEmpty();
    private static final Map<String, History> histories = new LinkedHashMap<>();
    private static final Map<Object, Object> tees = new IdentityHashMap<>();
    private static final TreeMap<Integer, String> targets = new TreeMap<>();
    private static final TreeMap<Integer, double[]> brows = new TreeMap<>();
    private static volatile Throwable failure;
    private static volatile boolean installed, closed = true;
    private static File mailbox;
    private static Thread ticker;
    private static Object au, avatar, requestToken;
    private static Class<?> auFrameType, fapFrameType, fapType, auPerformerType;
    private static Method timer;
    private static int lastFrame = -1, installedFrame, lastRequest = -PERIOD;
    private static long epoch, serial, accepted, discarded, emitted, observed, targetChanges;
    private static String target;
    private static Request pending;

    private GazeModiffAdapter() { }

    public static synchronized void install(Map<String, Object> objects, File output) throws Exception {
        if (!ENABLED) return;
        if (installed) throw new IllegalStateException("MODIFF already installed");
        if (!Boolean.parseBoolean(System.getProperty("gmit.gaze.enabled", "true")))
            throw new IllegalArgumentException("MODIFF requires the existing gaze schedule");
        mailbox = new File(System.getProperty("gmit.modiff.dir"));
        requireReady(mailbox);
        for (String name : Arrays.asList("request.tsv", "response.tsv", "error.json", "stop"))
            if (new File(mailbox, name).exists()) throw new IOException("Use a fresh MODIFF mailbox: " + name);
        au = require(objects, "gmit-facilitator-au");
        avatar = require(objects, "gmit-facilitator-avatar");
        ClassLoader loader = avatar.getClass().getClassLoader();
        auFrameType = loader.loadClass("greta.core.repositories.AUAPFrame");
        fapFrameType = loader.loadClass("greta.core.animation.mpeg4.fap.FAPFrame");
        fapType = loader.loadClass("greta.core.animation.mpeg4.fap.FAPType");
        auPerformerType = loader.loadClass("greta.core.keyframes.face.AUPerformer");
        timer = loader.loadClass("greta.core.util.time.Timer").getMethod("getCurrentFrameNumber");
        requestToken = loader.loadClass("greta.core.util.id.IDProvider")
            .getMethod("createID", String.class).invoke(null, "gmit-modiff-facilitator-brows");
        histories.put("patient-a", new History());
        histories.put("patient-b", new History());
        installedFrame = ((Number)timer.invoke(null)).intValue();
        lastFrame = installedFrame - 1;
        lastRequest = installedFrame - PERIOD;
        closed = false;
        installed = true;
        try {
            for (String actor : histories.keySet()) attach(actor, require(objects, "gmit-" + actor + "-face"));
            ticker = new Thread(() -> {
                while (!closed) {
                    try { tick(); Thread.sleep(20); }
                    catch (InterruptedException stop) { return; }
                    catch (Throwable error) { failure = error; return; }
                }
            }, "gmit-modiff-virtual-au");
            ticker.setDaemon(true);
            ticker.start();
        } catch (Exception error) { failure = error; throw error; }
        System.out.println("GMIT_MODIFF installed condition=engineering_virtual_AU output=facilitator_brows_only");
    }

    static void requireReady(File directory) throws IOException {
        File file = new File(directory, "ready.json");
        if (!file.isFile() || file.length() > 16384) throw new IOException("MODIFF readiness file missing or oversized");
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        if (!Pattern.compile("\"status\"\\s*:\\s*\"ready\"").matcher(text).find()
                || !Pattern.compile("\"protocol\"\\s*:\\s*\"" + Pattern.quote(VERSION) + "\"").matcher(text).find())
            throw new IOException("MODIFF worker is not ready with protocol " + VERSION);
    }

    public static synchronized void scheduleTarget(String viewer, String peer, int frame) {
        if (!ENABLED || !"facilitator".equals(viewer)) return;
        if (!installed) throw new IllegalStateException("Schedule MODIFF after installation");
        if (peer != null && !histories.containsKey(peer)) throw new IllegalArgumentException("Invalid MODIFF target: " + peer);
        if (frame < 0) throw new IllegalArgumentException("Negative MODIFF target frame");
        targets.put(frame, peer);
    }

    private static void attach(final String actor, Object face) throws Exception {
        Object tee = Proxy.newProxyInstance(auPerformerType.getClassLoader(), new Class<?>[]{auPerformerType}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "equals": return proxy == args[0];
                case "hashCode": return System.identityHashCode(proxy);
                case "toString": return "GMIT virtual AU source " + actor;
                default:
                    try {
                        if ("performAUAPFrame".equals(method.getName())) capture(actor, args[0]);
                        if ("performAUAPFrames".equals(method.getName()))
                            for (Object frame : (List<?>)args[0]) capture(actor, frame);
                    } catch (Throwable error) { failure = error; }
                    return null;
            }
        });
        invoke(face, "addAUPerformer", tee);
        tees.put(face, tee);
    }

    /** Keep partial left/right masks until their scheduled Greta frame is due. */
    private static synchronized void capture(String actor, Object frame) throws Exception {
        if (!installed || closed) return;
        int number = ((Number)invoke(frame, "getFrameNumber")).intValue();
        double[][] updates = new double[AUS.length][2];
        for (int i = 0; i < AUS.length; i++) {
            Arrays.fill(updates[i], Double.NaN); // Internal absent-mask sentinel only.
            int sourceAU = AUS[i] == 45 ? 43 : AUS[i];
            for (int side = 0; side < 2; side++) {
                String suffix = side == 0 ? "right" : "left";
                if ((Boolean)invoke(frame, "getAUAP" + suffix + "Mask", sourceAU)) {
                    double value = ((Number)invoke(frame, "getAUAP" + suffix + "Value", sourceAU)).doubleValue() / 1000.0;
                    updates[i][side] = Math.max(0, Math.min(1, value)) * 5;
                }
            }
        }
        histories.get(actor).queue(number, updates);
    }

    private static synchronized void tick() throws Exception {
        if (!installed || closed) return;
        int current = ((Number)timer.invoke(null)).intValue();
        if (current < lastFrame) throw new IllegalStateException("Greta Timer moved backwards");
        if (new File(mailbox, "error.json").exists()) throw new IOException("MODIFF worker reported error.json");
        if (current == lastFrame) return;
        if (current - lastFrame > 250) throw new IOException("MODIFF sampler stalled for more than 10 seconds");
        for (int frame = lastFrame + 1; frame <= current; frame++) {
            while (!targets.isEmpty() && targets.firstKey() <= frame) {
                String next = targets.pollFirstEntry().getValue();
                if (!Objects.equals(target, next)) {
                    target = next; epoch++; targetChanges++;
                    brows.clear();
                    System.out.println("GMIT_MODIFF target source=" + target + " epoch=" + epoch + " frame=" + frame);
                }
            }
            for (History history : histories.values()) history.sample(frame);
        }
        lastFrame = current;
        readResponse(current);
        // Future frames also cover speech/face frames queued before inference completed.
        publishBrows(current + 1);
        publishBrows(current + 2);
        Object actual = invoke(avatar, "getCurrentFAPFrame");
        for (String name : BROWS) if (((Number)invoke(actual, "getValue", fap(name))).intValue() != 0) { observed++; break; }
        if (target != null && pending == null && current - lastRequest >= PERIOD) request(current);
    }

    private static void request(int current) throws Exception {
        List<double[]> samples = histories.get(target).window(current);
        if (samples.size() != HISTORY) return;
        if (new File(mailbox, "request.tsv").exists() || new File(mailbox, "response.tsv").exists())
            throw new IOException("Unconsumed MODIFF mailbox data");
        Request request = new Request(Long.toString(++serial), target, epoch, current);
        StringBuilder text = new StringBuilder(request.header()).append('\n');
        for (double[] sample : samples) {
            for (int i = 0; i < sample.length; i++) {
                if (i > 0) text.append('\t');
                text.append(String.format(Locale.ROOT, "%.5f", sample[i]));
            }
            text.append('\n');
        }
        File temporary = new File(mailbox, "request.tsv.tmp");
        Files.write(temporary.toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(temporary.toPath(), new File(mailbox, "request.tsv").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        pending = request;
        lastRequest = current;
        System.out.println("GMIT_MODIFF request id=" + request.id + " source=" + target + " epoch=" + epoch + " input_end_frame=" + current);
    }

    private static void readResponse(int current) throws Exception {
        if (pending == null) return;
        if (current - pending.end > MAX_AGE) throw new IOException("MODIFF response exceeded 3 seconds");
        File response = new File(mailbox, "response.tsv");
        if (!response.exists()) return;
        if (response.length() > 16384) throw new IOException("Oversized MODIFF response");
        double[][] values = parseResponse(Files.readAllLines(response.toPath(), StandardCharsets.UTF_8), pending);
        Files.delete(response.toPath());
        if (new File(mailbox, "request.tsv").exists()) throw new IOException("Worker published before consuming its request");
        if (pending.epoch == epoch && Objects.equals(pending.actor, target)) {
            double[] from = browValues(current);
            for (int i = 0; i < PERIOD; i++) {
                double[] value = new double[3];
                double fraction = (i + 1.0) / PERIOD;
                for (int j = 0; j < 3; j++) value[j] = from[j] + (values[i][j] - from[j]) * fraction;
                brows.put(current + i + 1, value);
            }
            while (brows.size() > 100) brows.pollFirstEntry();
            accepted++;
            System.out.println("GMIT_MODIFF response id=" + pending.id + " source=" + target + " epoch=" + epoch + " latency_frames=" + (current - pending.end));
        } else {
            discarded++;
            System.out.println("GMIT_MODIFF discarded id=" + pending.id + " stale_epoch=" + pending.epoch + " current_epoch=" + epoch);
        }
        pending = null;
    }

    static double[][] parseResponse(List<String> lines, Request request) throws IOException {
        if (lines.size() != PERIOD + 1 || !request.header().equals(lines.get(0)))
            throw new IOException("MODIFF response identity or shape mismatch");
        double[][] values = new double[PERIOD][3];
        for (int i = 0; i < PERIOD; i++) {
            String[] cells = lines.get(i + 1).split("\t", -1);
            if (cells.length != 3) throw new IOException("MODIFF response must contain only AU1, AU2, AU4");
            for (int j = 0; j < 3; j++) {
                try { values[i][j] = Double.parseDouble(cells[j]); }
                catch (NumberFormatException error) { throw new IOException("Invalid MODIFF number", error); }
                if (!Double.isFinite(values[i][j]) || values[i][j] < 0 || values[i][j] > .25)
                    throw new IOException("MODIFF brow value outside finite [0,0.25]");
            }
        }
        return values;
    }

    private static double[] browValues(int frame) {
        Map.Entry<Integer, double[]> entry = brows.floorEntry(frame);
        return target == null || entry == null || frame - entry.getKey() > PERIOD ? new double[3] : entry.getValue();
    }

    private static Object browFrame(int frame) throws Exception {
        Object input = auFrameType.getConstructor(int.class).newInstance(frame);
        double[] values = browValues(frame);
        for (int i = 0; i < 3; i++) invoke(input, "setAUAPboth", i == 0 ? 1 : i == 1 ? 2 : 4, values[i]);
        Object translated = invoke(au, "toFAPFrame", input);
        Object output = fapFrameType.getConstructor(int.class).newInstance(frame);
        // Explicit zeros remove the previous target's brow state.
        for (String name : BROWS) invoke(output, "setMaskAndValue", fap(name), true,
            ((Number)invoke(translated, "getValue", fap(name))).intValue());
        for (Object channel : fapType.getEnumConstants())
            if ((Boolean)invoke(output, "getMask", channel) && !Arrays.asList(BROWS).contains(((Enum<?>)channel).name()))
                throw new IllegalStateException("MODIFF output escaped brow channel mask");
        return output;
    }

    /** Preserve every non-brow value/mask in the existing lip/gaze frame. */
    public static synchronized void applyFace(String actor, Object frame) throws Exception {
        if (!installed || closed || !"facilitator".equals(actor)) return;
        checkHealthy();
        int number = ((Number)invoke(frame, "getFrameNumber")).intValue();
        Object overlay = browFrame(number);
        for (String name : BROWS) invoke(frame, "setMaskAndValue", fap(name), true,
            ((Number)invoke(overlay, "getValue", fap(name))).intValue());
    }

    private static void publishBrows(int frame) throws Exception {
        invoke(avatar, "performFAPFrame", browFrame(frame), requestToken);
        emitted++;
    }

    public static synchronized void checkHealthy() {
        if (failure != null) throw new IllegalStateException("MODIFF bridge failed", failure);
    }

    public static synchronized void verify() {
        checkHealthy();
        if (!installed) return;
        if (lastFrame - installedFrame > HISTORY + MAX_AGE && targetChanges > 0 && (accepted == 0 || observed == 0))
            throw new IllegalStateException("No observed facilitator brow response after warmup");
        System.out.println("GMIT_MODIFF verified requests=" + serial + " accepted=" + accepted + " discarded=" + discarded
            + " emitted_brow_frames=" + emitted + " observed_nonzero_brow_frames=" + observed + " target_changes=" + targetChanges
            + " patient_a_au_frames=" + histories.get("patient-a").received + " patient_b_au_frames=" + histories.get("patient-b").received);
    }

    public static void close() {
        Thread owned;
        synchronized (GazeModiffAdapter.class) { closed = true; owned = ticker; ticker = null; }
        if (owned != null) {
            owned.interrupt();
            try { owned.join(1500); if (owned.isAlive()) failure = new IOException("MODIFF ticker did not stop"); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); failure = error; }
        }
        synchronized (GazeModiffAdapter.class) {
            try {
                if (installed) {
                    brows.clear(); target = null;
                    publishBrows(((Number)timer.invoke(null)).intValue() + 1);
                }
                installed = false;
                for (Map.Entry<Object, Object> tee : tees.entrySet()) invoke(tee.getKey(), "removeAUPerformer", tee.getValue());
            } catch (Throwable error) { failure = error; }
            tees.clear(); histories.clear(); targets.clear(); brows.clear(); pending = null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object fap(String name) { return Enum.valueOf((Class)fapType, name); }
    private static Object require(Map<String, Object> objects, String id) {
        Object result = objects.get(id);
        if (result == null) throw new IllegalStateException("Missing GMIT module " + id);
        return result;
    }
    private static Object invoke(Object object, String name, Object... args) throws Exception {
        for (Method method : object.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterTypes().length != args.length) continue;
            Class<?>[] types = method.getParameterTypes(); boolean match = true;
            for (int i = 0; i < types.length; i++) {
                Class<?> type = types[i];
                if (type == int.class) type = Integer.class;
                if (type == double.class) type = Double.class;
                if (type == boolean.class) type = Boolean.class;
                if (args[i] != null && !type.isInstance(args[i])) { match = false; break; }
            }
            if (match) return method.invoke(object, args);
        }
        throw new NoSuchMethodException(object.getClass().getName() + "." + name);
    }

    static final class Request {
        final String id, actor;
        final long epoch;
        final int end;
        Request(String id, String actor, long epoch, int end) { this.id = id; this.actor = actor; this.epoch = epoch; this.end = end; }
        String header() { return VERSION + "\t" + id + "\t" + actor + "\t" + epoch + "\t" + end; }
    }

    static final class History {
        final TreeMap<Integer, double[][]> queued = new TreeMap<>();
        final TreeMap<Integer, double[]> frames = new TreeMap<>();
        final double[][] state = new double[AUS.length][2];
        long received;
        void queue(int frame, double[][] update) {
            double[][] combined = queued.get(frame);
            if (combined == null) {
                combined = new double[AUS.length][2];
                for (double[] side : combined) Arrays.fill(side, Double.NaN);
                queued.put(frame, combined);
            }
            for (int i = 0; i < AUS.length; i++) for (int side = 0; side < 2; side++)
                if (!Double.isNaN(update[i][side])) combined[i][side] = update[i][side];
            if (queued.size() > 1000) throw new IllegalStateException("Unbounded patient AU queue");
            received++;
        }
        void sample(int frame) {
            while (!queued.isEmpty() && queued.firstKey() <= frame) {
                double[][] update = queued.pollFirstEntry().getValue();
                for (int i = 0; i < AUS.length; i++) for (int side = 0; side < 2; side++)
                    if (!Double.isNaN(update[i][side])) state[i][side] = update[i][side];
            }
            double[] values = new double[AUS.length];
            for (int i = 0; i < AUS.length; i++) values[i] = (state[i][0] + state[i][1]) / 2;
            frames.put(frame, values);
            while (frames.size() > 200) frames.pollFirstEntry();
        }
        List<double[]> window(int end) {
            List<double[]> result = new ArrayList<>();
            for (int frame = end - HISTORY + 1; frame <= end; frame++) {
                double[] value = frames.get(frame);
                if (value == null) return Collections.emptyList();
                result.add(value);
            }
            return result;
        }
    }
}
