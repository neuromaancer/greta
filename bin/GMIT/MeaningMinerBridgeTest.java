import java.util.Arrays;
import java.util.List;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Small headless boundary checks for the bridge's public-text conversion. */
public final class MeaningMinerBridgeTest {
    public static void main(String[] args) throws Exception {
        String input = "I <want> & need \"less\" red meat's impact.";
        String bml = MeaningMinerBridge.bmlFromOffsets("turn:1", input, Arrays.asList(
                new int[] {0, 1}, new int[] {2, 8}, new int[] {9, 10}, new int[] {11, 15},
                new int[] {16, 22}, new int[] {23, 26}, new int[] {27, 33}, new int[] {34, 40}, new int[] {40, 41}));
        require(bml.contains("&lt;want&gt;") && bml.contains("&amp;") && bml.contains("&quot;less&quot;") && bml.contains("meat&apos;s"), "escape original text");
        require(bml.contains("id=\"turn:1\""), "preserve event ID");
        require(bml.contains("tm0") && bml.contains("tm10"), "emit PTB marker boundaries and terminal sentinel");
        String visible = bml.replaceAll("<[^>]+>", "").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replace("&quot;", "\"").replace("&apos;", "'");
        require(visible.contains(input), "preserve unicode and punctuation text across marker insertion");
        expect(() -> MeaningMinerBridge.bml("bad id", "text"));
        expect(() -> MeaningMinerBridge.bml("ok", " "));
        expect(() -> MeaningMinerBridge.xml("bad\u0001text"));
        List<String> schemas = MeaningMinerBridge.imageSchemaTypes(Arrays.<Object>asList(
                new Intention("speech", "ignored"), new Intention("imageschema", "path"),
                new Intention("imageschema", "container"), new Intention("imageschema", "path"),
                new Intention("ideationalunit", "ignored"), new Intention("imageschema", " ")));
        require(schemas.equals(Arrays.asList("container", "path")), "only stable unique image-schema types");
        MeaningMinerBridge.validateEmissionKinds(Arrays.asList("SpeechSignal", "GestureSignal", "GazeSignal"), 1);
        expectIo(() -> MeaningMinerBridge.validateEmissionKinds(Arrays.asList("SpeechSignal", "SpeechSignal"), 1));
        expectIo(() -> MeaningMinerBridge.validateEmissionKinds(Arrays.asList("SpeechSignal", "UnknownSignal"), 1));
        expectIo(() -> MeaningMinerBridge.validateEmissionKinds(Arrays.asList("SpeechSignal"), 2));
        semanticAdmission();
        timeoutRestoresDiagnostic();
        System.out.println("PASS xml_escape event_id text_boundary image_schema_filter semantic_admission");
    }
    private static void semanticAdmission() throws Exception {
        String global = "gmit.meaningminer.gesture.admission";
        String actor = global + ".patient-a";
        String previousGlobal = System.getProperty(global), previousActor = System.getProperty(actor);
        try {
            Intention speech = new Intention("speech", "", "s1");
            IdeationalUnit unit = new IdeationalUnit("u1", "im-main");
            Intention main = new Intention("imageschema", "path", "im-main");
            Intention secondary = new Intention("imageschema", "container", "im-secondary");
            Intention emphasis = new Intention("emphasis", "", "e1");
            List<Object> values = Arrays.<Object>asList(speech, unit, main, secondary, emphasis);

            System.clearProperty(global); System.clearProperty(actor);
            require(MeaningMinerBridge.admittedIntentions("patient-a", values).equals(values), "default all admission");
            System.setProperty(global, "ideational-main");
            List<Object> admitted = MeaningMinerBridge.admittedIntentions("patient-a", values);
            require(admitted.equals(Arrays.<Object>asList(speech, unit, main, emphasis)), "keep only main image schema and preserve other intentions");
            require(main == admitted.get(2), "preserve admitted semantic object identity");
            require(!admitted.contains(secondary), "suppress secondary schema as a whole");

            System.setProperty(actor, "all");
            require(MeaningMinerBridge.admittedIntentions("patient-a", values).equals(values), "actor property overrides global policy");
            System.clearProperty(actor);
            List<Object> invalid = Arrays.<Object>asList(speech, new IdeationalUnit("u2", "missing"), main, secondary);
            require(MeaningMinerBridge.admittedIntentions("patient-a", invalid).equals(invalid), "invalid main anchor preserves complete raw turn");
            System.setProperty(global, "unsupported");
            expect(() -> MeaningMinerBridge.admittedIntentions("patient-a", values));
        } finally {
            restoreProperty(global, previousGlobal); restoreProperty(actor, previousActor);
        }
    }
    private static void restoreProperty(String name, String value) {
        if (value == null) System.clearProperty(name); else System.setProperty(name, value);
    }
    private static void timeoutRestoresDiagnostic() throws Exception {
        File directory = Files.createTempDirectory("meaning-miner-bridge-test").toFile();
        File existing = new File(directory, "diagnostic.xml"); Files.write(existing.toPath(), "original".getBytes(StandardCharsets.UTF_8));
        expectIo(() -> MeaningMinerBridge.runOwnedPreflight(fake(existing), existing, 1000L));
        require(new File(existing.getPath() + ".ready").isFile(), "child wrote before timeout");
        require("original".equals(new String(Files.readAllBytes(existing.toPath()), StandardCharsets.UTF_8)), "restore existing diagnostic after child timeout");
        File absent = new File(directory, "absent.xml");
        expectIo(() -> MeaningMinerBridge.runOwnedPreflight(fake(absent), absent, 1000L));
        require(!absent.exists(), "restore absent diagnostic after child timeout");
        new File(existing.getPath() + ".ready").delete(); new File(absent.getPath() + ".ready").delete(); existing.delete(); directory.delete();
    }
    private static ProcessBuilder fake(File diagnostic) {
        String java = new File(System.getProperty("java.home"), "bin" + File.separator + "java").getPath();
        return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), MeaningMinerBridgeTest.FakeChild.class.getName(), diagnostic.getAbsolutePath());
    }
    private interface Checked { void run() throws Exception; }
    private static void expect(Checked checked) throws Exception {
        try { checked.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected validation failure");
    }
    private static void expectIo(Checked checked) throws Exception {
        try { checked.run(); } catch (java.io.IOException expected) { return; }
        throw new AssertionError("Expected emission validation failure");
    }
    private static void require(boolean value, String detail) {
        if (!value) throw new AssertionError(detail);
    }
    public static final class Intention {
        private final String name, type, id;
        public Intention(String name, String type) { this(name, type, name + "-id"); }
        public Intention(String name, String type, String id) { this.name = name; this.type = type; this.id = id; }
        public String getName() { return name; }
        public String getType() { return type; }
        public String getId() { return id; }
    }
    public static final class IdeationalUnit {
        private final String id, main;
        public IdeationalUnit(String id, String main) { this.id = id; this.main = main; }
        public String getName() { return "ideationalunit"; }
        public String getType() { return ""; }
        public String getId() { return id; }
        public String getMainIntentionId() { return main; }
    }
    public static final class FakeChild {
        public static void main(String[] args) throws Exception {
            Files.write(new File(args[0]).toPath(), "child-write".getBytes(StandardCharsets.UTF_8));
            Files.write(new File(args[0] + ".ready").toPath(), "ready".getBytes(StandardCharsets.UTF_8));
            Thread.sleep(30000L);
        }
    }
}
