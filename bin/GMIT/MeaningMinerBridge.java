import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
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
 * Optional semantic-marker bridge for facilitator text. It never sends FML,
 * BML, speech, or animation into Greta's behavior graph.
 */
public final class MeaningMinerBridge {
    static final String VERSION = "gmit-meaning-miner/1";
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
        Object manager = require(objects, "gmit-facilitator-manager");
        verifyEnglish(manager);
        receiptDirectory = verifyOutput(output);
        try {
            createExtractor(manager);
            installed = true;
            writeReceipt(receiptDirectory, "installed", Collections.<String>emptyList());
            System.out.println("GMIT_MEANING_MINER installed actor=facilitator output=semantic_markers_only");
        } catch (Throwable error) {
            failure = unwrap(error);
            restoreDebugAfterFailure();
            throw asException(failure);
        }
    }

    /**
     * Extracts only real image-schema types. Empty output is a valid result and
     * does not cause a gesture fallback.
     */
    public static List<String> analyze(String eventId, String text) throws Exception {
        if (!ENABLED) return Collections.emptyList();
        synchronized (LOCK) {
            checkHealthy();
            if (!installed) throw new IllegalStateException("Install MeaningMiner before analysis");
            validateInput(eventId, text);
            try {
                Object raw = callBounded(new Callable<Object>() {
                    @Override public Object call() throws Exception {
                        try {
                            return process.invoke(extractor, bml(eventId, text));
                        } catch (InvocationTargetException error) {
                            throw asException(unwrap(error));
                        }
                    }
                }, "MeaningMiner analysis");
                List<String> types = imageSchemaTypes(raw);
                restoreDebug();
                System.out.println("GMIT_MEANING_MINER markers event_id=" + eventId + " count=" + types.size() + " values=" + types);
                return types;
            } catch (Throwable error) {
                failure = unwrap(error);
                restoreDebugAfterFailure();
                throw asException(failure);
            }
        }
    }

    /** Runs a real local semantic extraction without opening Modular or a voice. */
    public static void preflight(File output) throws Exception {
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
                try { return process.invoke(extractor, bml(eventId, text)); }
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
        extractor = null;
        process = null;
        savedDebug = null;
        installed = false;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !"--preflight".equals(args[0])) {
            throw new IllegalArgumentException("Usage: MeaningMinerBridge --preflight <absolute-output-directory>");
        }
        File output = new File(args[1]);
        if (!output.isAbsolute()) throw new IllegalArgumentException("Preflight output must be absolute");
        preflight(output);
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
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<bml id=\"" + xml(eventId) + "\">\n"
                + "  <speech id=\"s1\" language=\"english\" start=\"0.0\" text=\"\">\n"
                + "    <tm id=\"tm1\"/>" + xml(text) + "\n"
                + "  </speech>\n</bml>\n";
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
}
