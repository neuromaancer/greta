import java.awt.Frame;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/** Bounded synthetic probe of the installed Modular objects; no model backend. */
public final class SmokeProbe {
    private static final Map<String, Object> objects = new LinkedHashMap<>();
    private static final Map<String, List<Object>> audio = new ConcurrentHashMap<>();
    private static File output;
    private static Object captureTarget;
    private static volatile Throwable failure;
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
    private static void sampleFaces(int sequence, String speaker) throws Exception {
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
    public static void main(String[] args) {
        int status = 1;
        try { run(args); status = 0; }
        catch (Throwable e) { log("FAILED " + e); e.printStackTrace(); }
        finally { System.exit(status); }
    }
    private static void run(String[] args) throws Exception {
        if (args.length != 2 && args.length != 5) throw new IllegalArgumentException("Usage: SmokeProbe config.xml output-directory [start-delay turn-gap end-hold]");
        int startDelay = args.length == 5 ? Integer.parseInt(args[2]) : 0;
        int turnGap = args.length == 5 ? Integer.parseInt(args[3]) : 1;
        int endHold = args.length == 5 ? Integer.parseInt(args[4]) : 0;
        if (startDelay<0 || startDelay>15 || turnGap<1 || turnGap>5 || endHold<0 || endHold>15) throw new IllegalArgumentException("Observation delays out of bounds");
        output = new File(args[1]);
        if (!output.isDirectory()) throw new IllegalArgumentException("Output directory must exist");
        Thread.setDefaultUncaughtExceptionHandler((t,e) -> { failure = e; log("thread_failed " + t.getName() + " " + e); });
        Class.forName("greta.application.modular.Modular").getMethod("main", String[].class).invoke(null, (Object)new String[]{args[0]});
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
        String[] actors = {"facilitator","patient-a","patient-b"};
        Set<Object> managers = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String actor : actors) {
            Object manager = objects.get("gmit-" + actor + "-manager");
            if (manager == null || !managers.add(manager)) throw new IllegalStateException("Missing or shared character manager");
            log("blinking actor=" + actor + " enabled=" + call(objects.get("gmit-"+actor+"-face"),"isBlinking"));
            observeAudio(actor);
        }
        Thread.sleep(2000); capture("00-scene");
        for (int seconds=startDelay; seconds>0; seconds--) {
            title("Synthetic F-A-B-F starts in " + seconds + " seconds"); Thread.sleep(1000);
        }
        String[] turns = {"facilitator-open","patient-a","patient-b","facilitator-close"};
        for (int i = 0; i < turns.length; i++) {
            final String turn = turns[i]; String actor = turn.startsWith("facilitator") ? "facilitator" : turn;
            Map<String,Integer> before = new HashMap<>(); for (String a : actors) before.put(a,audio.get(a).size());
            Object reader = objects.get("gmit-" + turn + "-reader");
            title("Turn " + (i+1) + "/4: " + actor);
            log("dispatch sequence=" + (i+1) + " actor=" + actor + " file=" + turn + ".xml");
            call(reader,"load",new Class<?>[]{String.class},"./Examples/GMIT/"+turn+".xml");
            long until = System.currentTimeMillis()+10000;
            while (audio.get(actor).size() == before.get(actor) && System.currentTimeMillis() < until) {
                if (failure != null) throw new IllegalStateException("Playback thread failed",failure);
                Thread.sleep(100);
            }
            if (audio.get(actor).size() != before.get(actor)+1) throw new IllegalStateException("Expected one synthesized audio for " + actor);
            Object a = audio.get(actor).get(before.get(actor));
            long duration = ((Number)call(a,"getDurationMillis")).longValue();
            if (duration <= 0 || duration > 15000) throw new IllegalStateException("Unexpected synthetic audio duration: " + duration);
            // End time is a schedule estimate, never a claim of sound reaching a speaker.
            long end = ((Number)call(a,"getEndMillis")).longValue();
            Method clock = a.getClass().getClassLoader().loadClass("greta.core.util.time.Timer").getMethod("getTimeMillis");
            long engineNow = ((Number)clock.invoke(null)).longValue();
            long remaining = end-engineNow;
            if (remaining < 0 || remaining > 15000) throw new IllegalStateException("Unexpected audio schedule");
            long stop = System.currentTimeMillis()+remaining+turnGap*1000L+2000;
            int frame = 0;
            while (((Number)clock.invoke(null)).longValue()<end+turnGap*1000L) {
                if (System.currentTimeMillis()>stop) throw new IllegalStateException("Greta audio clock stopped progressing");
                capture(String.format("%02d-%s-%03d",i+1,actor,frame++)); sampleFaces(i+1,actor); Thread.sleep(100);
            }
            for (String other : actors) if (!other.equals(actor) && audio.get(other).size()!=before.get(other)) throw new IllegalStateException("Audio routed to wrong actor: " + other);
            log("schedule_elapsed sequence="+(i+1)+" buffer_position="+call(a,"getPlayingBufferPos")+" physical_playback=unverified");
        }
        if (failure != null) throw new IllegalStateException("Runtime thread failed",failure);
        log("COMPLETE synthetic_sequence=F,A,B,F physical_audio_and_lips_require_observation");
        title("Synthetic F-A-B-F finished"); Thread.sleep(endHold*1000L);
    }
}
