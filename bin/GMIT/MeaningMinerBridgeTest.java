import java.util.Arrays;
import java.util.List;

/** Small headless boundary checks for the bridge's public-text conversion. */
public final class MeaningMinerBridgeTest {
    public static void main(String[] args) throws Exception {
        String input = "I <want> & need \"less\" red meat's impact.";
        String bml = MeaningMinerBridge.bml("turn:1", input);
        require(bml.contains("&lt;want&gt; &amp; need &quot;less&quot; red meat&apos;s impact."), "escape original text");
        require(bml.contains("id=\"turn:1\""), "preserve event ID");
        expect(() -> MeaningMinerBridge.bml("bad id", "text"));
        expect(() -> MeaningMinerBridge.bml("ok", " "));
        expect(() -> MeaningMinerBridge.xml("bad\u0001text"));
        List<String> schemas = MeaningMinerBridge.imageSchemaTypes(Arrays.<Object>asList(
                new Intention("speech", "ignored"), new Intention("imageschema", "path"),
                new Intention("imageschema", "container"), new Intention("imageschema", "path"),
                new Intention("ideationalunit", "ignored"), new Intention("imageschema", " ")));
        require(schemas.equals(Arrays.asList("container", "path")), "only stable unique image-schema types");
        System.out.println("PASS xml_escape event_id text_boundary image_schema_filter");
    }
    private interface Checked { void run() throws Exception; }
    private static void expect(Checked checked) throws Exception {
        try { checked.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected validation failure");
    }
    private static void require(boolean value, String detail) {
        if (!value) throw new AssertionError(detail);
    }
    public static final class Intention {
        private final String name, type;
        public Intention(String name, String type) { this.name = name; this.type = type; }
        public String getName() { return name; }
        public String getType() { return type; }
    }
}
