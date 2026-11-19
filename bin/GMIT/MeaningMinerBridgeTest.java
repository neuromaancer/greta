import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
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
        System.out.println("PASS xml_escape event_id text_boundary image_schema_filter semantic_admission grounded_admission");
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
            System.setProperty(global, "ideational-grounded");
            IdeationalUnit groundedUnit = new IdeationalUnit("u3", "im-generic");
            GroundedSchema generic = new GroundedSchema("imageschema", "object", "im-generic", "tm0");
            GroundedSchema enabled = new GroundedSchema("imageschema", "enablement", "im-enabled", "tm1");
            List<Object> grounded = MeaningMinerBridge.admittedIntentions("patient-a",
                    Arrays.<Object>asList(speech, groundedUnit, generic, enabled), Arrays.asList("say", "can"), indexes("im-generic", 0, "im-enabled", 1));
            require(!grounded.contains(generic) && grounded.contains(enabled), "replace generic object only with a token-grounded specific schema");
            require("im-enabled".equals(((IdeationalUnit) grounded.get(1)).getMainIntentionId()), "remap copied ideational unit to admitted schema");
            require("im-generic".equals(groundedUnit.getMainIntentionId()), "retain raw ideational unit for audit");
            require(MeaningMinerBridge.admittedIntentions("patient-a", Arrays.<Object>asList(speech, groundedUnit, generic, enabled), Arrays.asList("say", "can")).contains(generic),
                    "missing extractor source evidence preserves the original main schema");
            List<Object> suppressed = MeaningMinerBridge.admittedIntentions("patient-a",
                    Arrays.<Object>asList(speech, new IdeationalUnit("u4", "im-say"), new GroundedSchema("imageschema", "object", "im-say", "tm0")), Arrays.asList("say"), indexes("im-say", 0));
            require(suppressed.size() == 1 && suppressed.contains(speech), "suppress ungrounded generic object and its orphaned unit without inventing gesture");
            GroundedSchema outsideUnit = new GroundedSchema("imageschema", "enablement", "im-outside", "tm2");
            List<Object> noCrossUnitPromotion = MeaningMinerBridge.admittedIntentions("patient-a",
                    Arrays.<Object>asList(speech, new IdeationalUnit("u4b", "im-say-2"), new GroundedSchema("imageschema", "object", "im-say-2", "tm0"), outsideUnit),
                    Arrays.asList("say", "then", "can"), indexes("im-say-2", 0, "im-outside", 2));
            require(noCrossUnitPromotion.size() == 1 && noCrossUnitPromotion.contains(speech), "do not promote a schema whose exact source token is outside the unit");

            IdeationalUnit endedUp = new IdeationalUnit("u5", new Marker("tm0"), new Marker("tm2"));
            endedUp.setMainIntentionId("im-up");
            GroundedSchema idiomaticUp = new GroundedSchema("imageschema", "up", "im-up", "tm1");
            GroundedSchema ordering = new GroundedSchema("imageschema", "enablement", "im-ordering", "tm2");
            List<Object> phrase = MeaningMinerBridge.admittedIntentions("patient-a", Arrays.<Object>asList(speech, endedUp, idiomaticUp, ordering),
                    Arrays.asList("ended", "up", "ordering"), indexes("im-up", 1, "im-ordering", 2));
            require(!phrase.contains(idiomaticUp) && phrase.contains(ordering), "remap idiomatic ended up to same-unit deliberative action");
            require("im-ordering".equals(((IdeationalUnit) phrase.get(1)).getMainIntentionId()), "retain selected source-grounded action as the unit main");
            IdeationalUnit literalUp = new IdeationalUnit("u6", new Marker("tm0"), new Marker("tm2"));
            literalUp.setMainIntentionId("im-literal-up");
            GroundedSchema literal = new GroundedSchema("imageschema", "up", "im-literal-up", "tm1");
            GroundedSchema otherAction = new GroundedSchema("imageschema", "enablement", "im-other-action", "tm2");
            List<Object> literalPhrase = MeaningMinerBridge.admittedIntentions("patient-a", Arrays.<Object>asList(speech, literalUp, literal, otherAction),
                    Arrays.asList("look", "up", "ordering"), indexes("im-literal-up", 1, "im-other-action", 2));
            require(literalPhrase.contains(literal) && !literalPhrase.contains(otherAction), "preserve literal directional up");
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
    private static Map<String, Integer> indexes(String first, int firstIndex, String second, int secondIndex) {
        Map<String, Integer> values = new LinkedHashMap<String, Integer>();
        values.put(first, Integer.valueOf(firstIndex)); values.put(second, Integer.valueOf(secondIndex)); return values;
    }
    private static Map<String, Integer> indexes(String id, int index) {
        return indexes(id, index, "unused", 0);
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
    public static class Intention {
        private final String name, type, id;
        public Intention(String name, String type) { this(name, type, name + "-id"); }
        public Intention(String name, String type, String id) { this.name = name; this.type = type; this.id = id; }
        public String getName() { return name; }
        public String getType() { return type; }
        public String getId() { return id; }
    }
    public static final class IdeationalUnit {
        private final String id;
        private String main;
        private final Marker start, end;
        public IdeationalUnit(String id, String main) { this(id, main, new Marker("tm0"), new Marker("tm1")); }
        public IdeationalUnit(String id, Object start, Object end) { this(id, "", (Marker) start, (Marker) end); }
        private IdeationalUnit(String id, String main, Marker start, Marker end) { this.id = id; this.main = main; this.start = start; this.end = end; }
        public String getName() { return "ideationalunit"; }
        public String getType() { return ""; }
        public String getId() { return id; }
        public String getMainIntentionId() { return main; }
        public void setMainIntentionId(String value) { main = value; }
        public Marker getStart() { return start; }
        public Marker getEnd() { return end; }
    }
    public static final class GroundedSchema extends Intention {
        private final Marker start;
        public GroundedSchema(String name, String type, String id, String marker) { super(name, type, id); this.start = new Marker(marker); }
        public Marker getStart() { return start; }
    }
    public static final class Marker {
        private final String target;
        public Marker(String target) { this.target = target; }
        public List<Reference> getReferences() { return Arrays.asList(new Reference(target)); }
    }
    public static final class Reference {
        private final String target;
        public Reference(String target) { this.target = target; }
        public String getTargetName() { return target; }
    }
    public static final class FakeChild {
        public static void main(String[] args) throws Exception {
            Files.write(new File(args[0]).toPath(), "child-write".getBytes(StandardCharsets.UTF_8));
            Files.write(new File(args[0] + ".ready").toPath(), "ready".getBytes(StandardCharsets.UTF_8));
            Thread.sleep(30000L);
        }
    }
}
