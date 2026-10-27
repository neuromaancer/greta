import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** Bounded local probe for authored scripts or offline transcript replay; no model backend. */
public final class SmokeProbe {
    private static final Map<String, Object> objects = new LinkedHashMap<>();
    private static final Map<String, List<Object>> audio = new ConcurrentHashMap<>();
    private static File output;
    private static Object captureTarget;
    private static volatile Throwable failure;
    private static String activeProvenance = "Synthetic authored dialogue";
    private static final String[] ACTORS = {"facilitator", "patient-a", "patient-b"};

    private static final class Turn {
        final int sequence;
        final String eventId;
        final String actor;
        final String stage;
        final String act;
        final String text;
        final String sourceEventId;
        final String sourceTurn;
        final int chunkIndex;
        final int chunkCount;
        Turn(int sequence, String eventId, String actor, String stage, String act, String text) {
            this(sequence, eventId, actor, stage, act, text, null, null, 1, 1);
        }
        Turn(int sequence, String eventId, String actor, String stage, String act, String text, String sourceEventId, String sourceTurn, int chunkIndex, int chunkCount) {
            this.sequence = sequence;
            this.eventId = eventId;
            this.actor = actor;
            this.stage = stage;
            this.act = act;
            this.text = text;
            this.sourceEventId = sourceEventId; this.sourceTurn = sourceTurn;
            this.chunkIndex = chunkIndex; this.chunkCount = chunkCount;
        }
    }

    private static final class Scenario {
        final String sessionId;
        final String title;
        final String provenance;
        final String condition;
        final List<Turn> turns;
        Scenario(String sessionId, String title, String provenance, String condition, List<Turn> turns) {
            this.sessionId = sessionId; this.title = title; this.provenance = provenance; this.condition = condition; this.turns = turns;
        }
    }

    private static final class Caption {
        private final JFrame frame;
        private final JTextArea text;
        private volatile boolean cancelled;
        Caption(final String title) throws Exception {
            final JFrame[] holder = new JFrame[1];
            final JTextArea[] area = new JTextArea[1];
            SwingUtilities.invokeAndWait(() -> {
                JFrame value = new JFrame("GMIT | Group MI replay");
                value.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                value.setLayout(new BorderLayout(8, 8));
                JLabel label = new JLabel(title);
                label.setFont(label.getFont().deriveFont(Font.BOLD, 16f));
                label.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
                JTextArea body = new JTextArea(8, 46);
                body.setEditable(false);
                body.setLineWrap(true);
                body.setWrapStyleWord(true);
                body.setFont(body.getFont().deriveFont(20f));
                body.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
                JButton stop = new JButton("Stop run");
                stop.addActionListener(event -> { cancelled = true; value.dispose(); });
                value.add(label, BorderLayout.NORTH);
                value.add(new JScrollPane(body), BorderLayout.CENTER);
                value.add(stop, BorderLayout.SOUTH);
                value.pack();
                Frame player = (Frame)objects.get("gmit-player");
                Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
                Rectangle playerBounds = player == null ? screen : player.getBounds();
                int x = playerBounds.x + playerBounds.width + 12;
                if (x + value.getWidth() > screen.x + screen.width) x = playerBounds.x - value.getWidth() - 12;
                x = Math.max(screen.x, Math.min(x, screen.x + screen.width - value.getWidth()));
                int y = Math.max(screen.y, Math.min(playerBounds.y, screen.y + screen.height - value.getHeight()));
                value.setLocation(x, y);
                value.setVisible(true);
                holder[0] = value;
                area[0] = body;
            });
            frame = holder[0];
            text = area[0];
        }
        void show(final int index, final int total, final Turn turn, String provenance) throws Exception {
            final String speaker = speakerLabel(turn.actor);
            SwingUtilities.invokeAndWait(() -> text.setText(
                provenance + "\n"
                + "Turn " + index + "/" + total + " | " + turn.stage + " | " + turn.act + "\n"
                + speaker + "\n\n" + turn.text));
        }
        void close() throws Exception { SwingUtilities.invokeAndWait(() -> frame.dispose()); }
        void checkCancelled() {
            if (cancelled) throw new CancelledException("Group MI run stopped by user");
        }
    }
    private static final class CancelledException extends RuntimeException {
        CancelledException(String message) { super(message); }
    }
    private static Object call(Object o, String name, Class<?>[] types, Object... args) throws Exception {
        return o.getClass().getMethod(name, types).invoke(o, args);
    }
    private static Object call(Object o, String name) throws Exception {
        return call(o, name, new Class<?>[0]);
    }
    private static void log(String value) { System.out.println("GMIT_PROBE " + value); }
    private static void title(String value) throws Exception {
        final Frame player = (Frame)objects.get("gmit-player");
        SwingUtilities.invokeAndWait(() -> player.setTitle("GMIT | " + value));
    }
    private static String speakerLabel(String actor) {
        if ("facilitator".equals(actor)) return "Facilitator (center)";
        if ("patient-a".equals(actor)) return "Alex / patient A (left)";
        if ("patient-b".equals(actor)) return "Sam / patient B (right)";
        throw new IllegalArgumentException("Unsupported actor: " + actor);
    }
    private static String readerFor(String actor) {
        if ("facilitator".equals(actor)) return "gmit-facilitator-open-reader";
        if ("patient-a".equals(actor)) return "gmit-patient-a-reader";
        if ("patient-b".equals(actor)) return "gmit-patient-b-reader";
        throw new IllegalArgumentException("Unsupported actor: " + actor);
    }
    private static boolean isKnownActor(String actor) {
        for (String known : ACTORS) if (known.equals(actor)) return true;
        return false;
    }
    private static String requiredAttribute(Element element, String name) {
        String value = element.getAttribute(name);
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("Missing " + name + " on " + element.getTagName());
        return value;
    }
    private static Element oneDirectChild(Element parent, String name) {
        Element result = null;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            if (!name.equals(child.getNodeName())) throw new IllegalArgumentException("Unsupported child " + child.getNodeName() + " in " + parent.getTagName());
            if (result != null) throw new IllegalArgumentException("Duplicate " + name + " in " + parent.getTagName());
            result = (Element) child;
        }
        if (result == null) throw new IllegalArgumentException("Missing " + name + " in " + parent.getTagName());
        return result;
    }
    private static String plainText(Element textElement) {
        StringBuilder value = new StringBuilder();
        NodeList children = textElement.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) value.append(child.getNodeValue());
            else if (child.getNodeType() == Node.ELEMENT_NODE) throw new IllegalArgumentException("Nested markup is not allowed in text");
            else if (child.getNodeType() != Node.COMMENT_NODE) throw new IllegalArgumentException("Unsupported text node type");
        }
        return value.toString();
    }
    private static String optionalAttribute(Element element, String name) { String value = element.getAttribute(name); return value == null || value.trim().isEmpty() ? null : value; }
    private static int optionalPositive(Element element, String name, int fallback) {
        String value = optionalAttribute(element, name); if (value == null) return fallback;
        try { int parsed = Integer.parseInt(value); if (parsed < 1) throw new NumberFormatException(value); return parsed; }
        catch (NumberFormatException bad) { throw new IllegalArgumentException("Invalid " + name, bad); }
    }
    private static Scenario readScenario(File scenarioFile) throws Exception {
        if (!scenarioFile.isFile()) throw new IllegalArgumentException("Scenario does not exist: " + scenarioFile);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        try {
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (IllegalArgumentException unsupported) {
            throw new IllegalStateException("Secure XML parser attributes are unavailable", unsupported);
        }
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> { throw new SAXException("External XML resolution is disabled"); });
        Document document = builder.parse(scenarioFile);
        Element root = document.getDocumentElement();
        if (!"gmit-session".equals(root.getTagName())) throw new IllegalArgumentException("Expected gmit-session root");
        if (!"1".equals(requiredAttribute(root, "schema_version"))) throw new IllegalArgumentException("Unsupported schema_version");
        String provenance = requiredAttribute(root, "provenance");
        String floorMode = requiredAttribute(root, "floor_mode");
        boolean synthetic = "synthetic-authored".equals(provenance) && "scripted-f-a-b".equals(floorMode);
        boolean replay = ("gmit-generated".equals(provenance) || "transcript-import".equals(provenance)) && "recorded".equals(floorMode);
        if (!synthetic && !replay) throw new IllegalArgumentException("Unsupported provenance/floor_mode combination");
        String condition = optionalAttribute(root, "condition");
        if (condition != null && !"pure".equals(condition) && !"rl".equals(condition) && !"unknown".equals(condition)) throw new IllegalArgumentException("Unsupported condition");
        String sessionId = requiredAttribute(root, "session_id");
        String title = requiredAttribute(root, "title");
        List<Turn> turns = new ArrayList<>();
        Set<String> eventIds = new HashSet<>();
        int expectedSequence = 1;
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            if (!"turn".equals(child.getNodeName())) throw new IllegalArgumentException("Unsupported element in scenario: " + child.getNodeName());
            Element turn = (Element)child;
            int sequence;
            try { sequence = Integer.parseInt(requiredAttribute(turn, "sequence")); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid turn sequence", invalid); }
            if (sequence != expectedSequence++) throw new IllegalArgumentException("Turn sequences must be contiguous from 1");
            String eventId = requiredAttribute(turn, "event_id");
            if (!eventId.matches("[A-Za-z0-9][A-Za-z0-9._-]*") || !eventIds.add(eventId)) throw new IllegalArgumentException("Invalid or duplicate event_id: " + eventId);
            String actor = requiredAttribute(turn, "actor_id");
            if (!isKnownActor(actor)) throw new IllegalArgumentException("Unsupported actor_id: " + actor);
            String stage = requiredAttribute(turn, "stage");
            String act = requiredAttribute(turn, "act");
            String text = plainText(oneDirectChild(turn, "text"));
            if (text == null || text.trim().isEmpty() || text.length() > 300) throw new IllegalArgumentException("Turn text must contain 1..300 characters");
            String sourceEventId = optionalAttribute(turn, "source_event_id");
            String sourceTurn = optionalAttribute(turn, "source_turn");
            int chunkIndex = optionalPositive(turn, "chunk_index", 1), chunkCount = optionalPositive(turn, "chunk_count", 1);
            if (chunkIndex > chunkCount) throw new IllegalArgumentException("chunk_index exceeds chunk_count");
            turns.add(new Turn(sequence, eventId, actor, stage, act, text, sourceEventId, sourceTurn, chunkIndex, chunkCount));
        }
        if (synthetic) {
            if (turns.size() < 4 || turns.size() > 16) throw new IllegalArgumentException("Scenario must contain 4..16 turns");
            if (turns.size() % 3 != 1) throw new IllegalArgumentException("scripted-f-a-b requires F,A,B cycles followed by a final facilitator turn");
            for (int i = 0; i < turns.size(); i++) if (!ACTORS[i % ACTORS.length].equals(turns.get(i).actor)) throw new IllegalArgumentException("scripted-f-a-b actor order mismatch at turn " + (i + 1));
        } else if (turns.isEmpty() || turns.size() > 256) {
            throw new IllegalArgumentException("Recorded replay must contain 1..256 chunks");
        } else {
            for (int i = 0; i < turns.size(); i++) {
                Turn turn = turns.get(i);
                if (turn.chunkCount > 1) {
                    if (turn.sourceEventId == null) throw new IllegalArgumentException("Chunked replay turn requires source_event_id");
                    if (turn.chunkIndex == 1 && i + turn.chunkCount > turns.size()) throw new IllegalArgumentException("Incomplete chunk sequence");
                    if (turn.chunkIndex > 1) {
                        if (i == 0) throw new IllegalArgumentException("Replay cannot begin with a later chunk");
                        Turn previous = turns.get(i - 1);
                        if (!turn.actor.equals(previous.actor) || !turn.sourceEventId.equals(previous.sourceEventId) || turn.chunkIndex != previous.chunkIndex + 1 || turn.chunkCount != previous.chunkCount) throw new IllegalArgumentException("Replay chunks must be consecutive for one actor and source event");
                    }
                }
            }
        }
        return new Scenario(sessionId, title, provenance, condition, turns);
    }
    private static String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }
    private static File writeBml(Turn turn) throws Exception {
        File result = new File(output, String.format("scenario-%03d-%s.xml", turn.sequence, turn.actor));
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<bml>\n"
            + "  <speech id=\"scenario_" + turn.sequence + "\" start=\"0.0\" language=\"english\" voice=\"marytts\" type=\"SAPI4\" text=\"\">\n"
            + "    <description level=\"1\" type=\"gretabml\"><reference>tmp/from-fml-apml.pho</reference></description>\n"
            + "    <tm id=\"t1\"/>\n"
            + "    " + escapeXml(turn.text) + "\n"
            + "    <tm id=\"t2\"/>\n"
            + "  </speech>\n"
            + "</bml>\n";
        java.nio.file.Files.write(result.toPath(), xml.getBytes("UTF-8"));
        return result;
    }
    private static void assertAudioCounts(Map<String, Integer> expected) {
        for (String actor : ACTORS) {
            int actual = audio.get(actor).size();
            if (actual != expected.get(actor)) throw new IllegalStateException("Unexpected audio count for " + actor + ": " + actual + " expected " + expected.get(actor));
        }
    }
    private static void sampleFaces(int sequence, String speaker) throws Exception {
        SeatedScene.checkHealthy();
        SeatedScene.verifyPose();
        ListenerGaze.verify();
        for (String actor : audio.keySet()) {
            Object frame = call(objects.get("gmit-"+actor+"-avatar"),"getCurrentFAPFrame");
            int jaw = (Integer)call(frame,"getValue",new Class<?>[]{int.class},3);
            if (!actor.equals(speaker) && jaw != 0) throw new IllegalStateException("Inactive actor jaw moved: " + actor);
            log("fap sequence="+sequence+" actor="+actor
                +" jaw="+jaw
                +" eyelid_l="+call(frame,"getValue",new Class<?>[]{int.class},19)
                +" eyelid_r="+call(frame,"getValue",new Class<?>[]{int.class},20));
        }
    }
    private static void capture(String name) throws Exception {
        if (captureTarget == null) {
            Object player = objects.get("gmit-player");
            if (player == null) throw new IllegalStateException("Ogre player failed to load");
            final Frame playerFrame = (Frame)player;
            SwingUtilities.invokeAndWait(() -> { playerFrame.toFront(); playerFrame.repaint(); });
            long readyBy = System.currentTimeMillis()+30000;
            Object camera = call(player, "getCamera");
            while (camera == null && System.currentTimeMillis()<readyBy) {
                if (failure != null) throw new IllegalStateException("Renderer initialization failed",failure);
                Thread.sleep(250); camera = call(player,"getCamera");
            }
            if (camera == null) throw new IllegalStateException("Ogre camera not initialized");
            // Capture the existing scene and camera using Greta's own offscreen target.
            // Window back-buffer capture can return black when the window is occluded.
            Class<?> texture = player.getClass().getClassLoader().loadClass("greta.auxiliary.player.ogre.OgreRenderTexture");
            captureTarget = texture.getConstructor(camera.getClass()).newInstance(camera);
            call(captureTarget,"setSize",new Class<?>[]{int.class,int.class},920,576);
            call(captureTarget,"prepareCapture");
            Thread.sleep(300);
            log("capture=OgreRenderTexture same_player_camera=true desktop_visibility=unverified");
        }
        int w = (Integer) call(captureTarget, "getCaptureWidth");
        int h = (Integer) call(captureTarget, "getCaptureHeight");
        byte[] rgb = (byte[]) call(captureTarget, "getCaptureData");
        if (w <= 0 || h <= 0 || rgb.length != w * h * 3) throw new IllegalStateException("Invalid render buffer");
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0, i = 0; y < h; y++) for (int x = 0; x < w; x++, i += 3)
            img.setRGB(x, y, ((rgb[i] & 255) << 16) | ((rgb[i+1] & 255) << 8) | (rgb[i+2] & 255));
        ImageIO.write(img, "png", new File(output, name + ".png"));
    }
    private static void observeAudio(final String actor) throws Exception {
        audio.put(actor, new CopyOnWriteArrayList<>());
        Object performer = objects.get("gmit-" + actor + "-audio");
        Method add = null;
        for (Method m : performer.getClass().getMethods()) if (m.getName().equals("addAudioPerformer")) add = m;
        if (add == null) throw new IllegalStateException("Missing audio observer API");
        Class<?> iface = add.getParameterTypes()[0];
        Object listener = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, (p,m,args) -> {
            if (m.getName().equals("performAudios")) {
                for (Object a : (List<?>) args[0]) {
                    audio.get(actor).add(a);
                    log("audio_scheduled actor=" + actor + " duration_ms=" + call(a,"getDurationMillis"));
                    call(a, "save", new Class<?>[]{File.class}, new File(output, actor + "-" + audio.get(actor).size() + ".wav"));
                }
            } else if (m.getName().equals("hashCode")) return System.identityHashCode(p);
            else if (m.getName().equals("equals")) return p == args[0];
            else if (m.getName().equals("toString")) return "GMIT audio observer " + actor;
            return null;
        });
        add.invoke(performer, listener);
    }
    private static void playTurn(int ordinal, int total, String actor, String readerId, File bml,
                                 Map<String, Integer> expected, int turnGap, Caption caption, Turn scenarioTurn) throws Exception {
        assertAudioCounts(expected);
        if (caption != null) caption.checkCancelled();
        if (caption != null) caption.show(ordinal, total, scenarioTurn, activeProvenance);
        title("Turn " + ordinal + "/" + total + ": " + speakerLabel(actor));
        ListenerGaze.focus(actor);
        log("dispatch sequence=" + ordinal + " actor=" + actor + " reader=" + readerId + " file=" + bml.getName());
        if (scenarioTurn != null) log("scenario_event event_id=" + scenarioTurn.eventId + " source_sequence=" + scenarioTurn.sequence + " source_event_id=" + scenarioTurn.sourceEventId + " source_turn=" + scenarioTurn.sourceTurn + " chunk=" + scenarioTurn.chunkIndex + "/" + scenarioTurn.chunkCount + " stage=" + scenarioTurn.stage + " act=" + scenarioTurn.act);
        call(objects.get(readerId), "load", new Class<?>[]{String.class}, bml.getPath());
        long until = System.currentTimeMillis() + 10000;
        Map<String, Integer> scheduled = new HashMap<>(expected);
        scheduled.put(actor, expected.get(actor) + 1);
        while (audio.get(actor).size() == expected.get(actor) && System.currentTimeMillis() < until) {
            if (caption != null) caption.checkCancelled();
            if (failure != null) throw new IllegalStateException("Playback thread failed", failure);
            for (String other : ACTORS) if (!other.equals(actor) && audio.get(other).size() != expected.get(other))
                throw new IllegalStateException("Audio routed to wrong actor: " + other);
            if (audio.get(actor).size() > scheduled.get(actor)) throw new IllegalStateException("Duplicate audio for " + actor);
            Thread.sleep(100);
        }
        assertAudioCounts(scheduled);
        Object a = audio.get(actor).get(expected.get(actor));
        long duration = ((Number)call(a,"getDurationMillis")).longValue();
        if (duration <= 0 || duration > 15000) throw new IllegalStateException("Unexpected generated audio duration: " + duration);
        // End time is a schedule estimate, never a claim of sound reaching a speaker.
        long end = ((Number)call(a,"getEndMillis")).longValue();
        Method clock = a.getClass().getClassLoader().loadClass("greta.core.util.time.Timer").getMethod("getTimeMillis");
        long engineNow = ((Number)clock.invoke(null)).longValue();
        long remaining = end-engineNow;
        if (remaining < 0 || remaining > 15000) throw new IllegalStateException("Unexpected audio schedule");
        long stop = System.currentTimeMillis()+remaining+turnGap*1000L+2000;
        int frame = 0;
        while (((Number)clock.invoke(null)).longValue()<end+turnGap*1000L) {
            if (caption != null) caption.checkCancelled();
            if (System.currentTimeMillis()>stop) throw new IllegalStateException("Greta audio clock stopped progressing");
            if (failure != null) throw new IllegalStateException("Playback thread failed", failure);
            assertAudioCounts(scheduled);
            if ("Synthetic authored dialogue".equals(activeProvenance)) capture(String.format("%02d-%s-%03d", ordinal, actor, frame++));
            else if ((ordinal <= 3 || ordinal == total || ordinal % 25 == 0) && frame++ == 0) capture(String.format("%03d-%s", ordinal, actor));
            if (scenarioTurn != null && frame == 12) capture(String.format("gaze-%03d-%s", ordinal, actor));
            sampleFaces(ordinal,actor); Thread.sleep(100);
        }
        assertAudioCounts(scheduled);
        expected.put(actor, scheduled.get(actor));
        log("schedule_elapsed sequence="+ordinal+" buffer_position="+call(a,"getPlayingBufferPos")+" physical_playback=unverified");
    }
    private static void hideAutomaticReaderFrames() throws Exception {
        for (String actor : ACTORS) {
            Object reader = objects.get(readerFor(actor));
            if (reader instanceof Frame) {
                final Frame frame = (Frame)reader;
                SwingUtilities.invokeAndWait(() -> frame.setVisible(false));
                log("reader_frame hidden=" + readerFor(actor));
            } else {
                log("reader_frame unavailable=" + readerFor(actor));
            }
        }
    }
    public static void main(String[] args) {
        int status = 1;
        try { run(args); status = 0; }
        catch (CancelledException cancelled) { log("CANCELLED " + cancelled.getMessage()); }
        catch (Throwable e) { log("FAILED " + e); e.printStackTrace(); }
        finally {
            ListenerGaze.close();
            SeatedScene.close();
            try { SeatedScene.checkHealthy(); ListenerGaze.checkHealthy(); }
            catch (Throwable e) { log("FAILED seated_cleanup " + e); status = 1; }
            System.exit(status);
        }
    }
    private static void run(String[] args) throws Exception {
        if (args.length == 2 && "--validate".equals(args[0])) {
            Scenario scenario = readScenario(new File(args[1]));
            StringBuilder order = new StringBuilder();
            for (Turn turn : scenario.turns) {
                if (order.length() > 0) order.append(',');
                order.append(turn.actor);
            }
            log("VALID mode=" + ("synthetic-authored".equals(scenario.provenance) ? "synthetic_group_mi" : "recorded_replay") + " provenance=" + scenario.provenance + " session_id=" + scenario.sessionId + " count=" + scenario.turns.size() + " order=" + order);
            return;
        }
        if (args.length != 2 && args.length != 5 && args.length != 6) throw new IllegalArgumentException("Usage: SmokeProbe config.xml output-directory [start-delay turn-gap end-hold [scenario.xml]] or --validate scenario.xml");
        int startDelay = args.length == 5 ? Integer.parseInt(args[2]) : 0;
        int turnGap = args.length == 5 ? Integer.parseInt(args[3]) : 1;
        int endHold = args.length == 5 ? Integer.parseInt(args[4]) : 0;
        if (args.length == 6) {
            startDelay = Integer.parseInt(args[2]);
            turnGap = Integer.parseInt(args[3]);
            endHold = Integer.parseInt(args[4]);
        }
        if (startDelay<0 || startDelay>15 || turnGap<1 || turnGap>5 || endHold<0 || endHold>15) throw new IllegalArgumentException("Observation delays out of bounds");
        Scenario scenario = args.length == 6 ? readScenario(new File(args[5])) : null;
        if (scenario != null) activeProvenance = "synthetic-authored".equals(scenario.provenance) ? "Synthetic authored dialogue" : "Recorded offline replay — " + scenario.provenance + (scenario.condition == null ? "" : " / " + scenario.condition);
        output = new File(args[1]);
        if (!output.isDirectory()) throw new IllegalArgumentException("Output directory must exist");
        Thread.setDefaultUncaughtExceptionHandler((t,e) -> { failure = e; log("thread_failed " + t.getName() + " " + e); });
        Class<?> entry = Class.forName("greta.application.modular.Modular");
        log("entry class=" + entry.getName() + " from=" + entry.getProtectionDomain().getCodeSource().getLocation());
        entry.getMethod("main", String[].class).invoke(null, (Object)new String[]{args[0]});
        long deadline = System.currentTimeMillis() + 60000;
        Object graph = null;
        while (graph == null && System.currentTimeMillis() < deadline) {
            if (failure != null) throw new IllegalStateException("Startup failed", failure);
            for (Frame f : Frame.getFrames()) if (f.getClass().getName().equals("greta.application.modular.ModularWindow")) graph = call(f, "getGraph");
            Thread.sleep(200);
        }
        if (graph == null) throw new IllegalStateException("Modular window startup timeout");
        // Wait for the event queue to finish graph creation before inspecting it.
        SwingUtilities.invokeAndWait(() -> {});
        if (failure != null) throw new IllegalStateException("Graph startup failed", failure);
        Method modules = graph.getClass().getDeclaredMethod("getModules"); modules.setAccessible(true);
        for (Object m : (List<?>) modules.invoke(graph)) {
            String id = (String)call(m,"getId"); Object value = call(m,"getObject"); objects.put(id,value);
            log("loaded id=" + id + " class=" + value.getClass().getName() + " from=" + value.getClass().getProtectionDomain().getCodeSource().getLocation());
        }
        Set<Object> managers = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String actor : ACTORS) {
            Object manager = objects.get("gmit-" + actor + "-manager");
            if (manager == null || !managers.add(manager)) throw new IllegalStateException("Missing or shared character manager");
            boolean blinking = ((Boolean)call(objects.get("gmit-"+actor+"-face"),"isBlinking")).booleanValue();
            log("blinking actor=" + actor + " enabled=" + blinking);
            if (!blinking) throw new IllegalStateException("Blinking disabled for " + actor);
            observeAudio(actor);
        }
        hideAutomaticReaderFrames();
        Caption caption = scenario == null ? null : new Caption(scenario.title);
        if (scenario != null) { SeatedScene.install(objects); ListenerGaze.install(objects); }
        Thread.sleep(2000);
        SeatedScene.verifyPose();
        capture("00-scene");
        if (scenario != null && Boolean.getBoolean("gmit.preview")) {
            title("Seated group MI preview — no speech");
            Thread.sleep(3000);
            capture("01-seated-preview");
            for (String actor : ACTORS) {
                ListenerGaze.focus(actor);
                Thread.sleep(1800);
                SeatedScene.verifyPose();
                ListenerGaze.verify();
                capture("02-gaze-" + actor);
            }
            SeatedScene.checkHealthy();
            log("COMPLETE mode=seated_preview speech=false");
            return;
        }
        for (int seconds=startDelay; seconds>0; seconds--) {
            if (caption != null) caption.checkCancelled();
            String runLabel = scenario == null ? "Synthetic F-A-B-F" : activeProvenance;
            title(runLabel + " starts in " + seconds + " seconds"); Thread.sleep(1000);
        }
        Map<String,Integer> expected = new HashMap<>(); for (String actor : ACTORS) expected.put(actor, 0);
        String scenarioOrder = null;
        try {
            if (scenario == null) {
                String[] turns = {"facilitator-open","patient-a","patient-b","facilitator-close"};
                for (int i = 0; i < turns.length; i++) {
                    String turn = turns[i];
                    String actor = turn.startsWith("facilitator") ? "facilitator" : turn;
                    playTurn(i + 1, turns.length, actor, "gmit-" + turn + "-reader", new File("./Examples/GMIT/" + turn + ".xml"), expected, turnGap, null, null);
                }
                if (failure != null) throw new IllegalStateException("Runtime thread failed",failure);
                assertAudioCounts(expected);
                title("Synthetic F-A-B-F finished");
            } else {
                StringBuilder order = new StringBuilder();
                for (int i = 0; i < scenario.turns.size(); i++) {
                    Turn turn = scenario.turns.get(i);
                    if (order.length() > 0) order.append(',');
                    order.append(turn.actor);
                    playTurn(i + 1, scenario.turns.size(), turn.actor, readerFor(turn.actor), writeBml(turn), expected, turnGap, caption, turn);
                }
                scenarioOrder = order.toString();
                if (failure != null) throw new IllegalStateException("Runtime thread failed",failure);
                assertAudioCounts(expected);
                title(activeProvenance + " finished");
            }
            long holdUntil = System.currentTimeMillis() + endHold * 1000L;
            do {
                if (caption != null) caption.checkCancelled();
                if (failure != null) throw new IllegalStateException("Runtime thread failed", failure);
                SeatedScene.checkHealthy();
                assertAudioCounts(expected);
                long remaining = holdUntil - System.currentTimeMillis();
                if (remaining > 0) Thread.sleep(Math.min(100L, remaining));
            } while (System.currentTimeMillis() < holdUntil);
            if (scenario == null) log("COMPLETE synthetic_sequence=F,A,B,F physical_audio_and_lips_require_observation");
            else log("COMPLETE mode=" + ("synthetic-authored".equals(scenario.provenance) ? "synthetic_group_mi" : "recorded_replay") + " provenance=" + scenario.provenance + " session_id=" + scenario.sessionId + " count=" + scenario.turns.size() + " order=" + scenarioOrder + " physical_audio_and_lips_require_observation");
        } finally {
            if (caption != null) caption.close();
        }
    }
}
