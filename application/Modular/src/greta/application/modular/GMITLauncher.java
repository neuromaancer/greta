package greta.application.modular;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.swing.JFileChooser;

/** Opt-in NetBeans entry for the synthetic group MI demo; uses no model backend. */
final class GMITLauncher {
    private static final String[] SCRATCH = {
        "ModularSave.xml", "fml_to_bml.xml", "Log/Ogre.log",
        "Player/Lib/External/Win64/Plugins_OpenGL.cfg"
    };

    private GMITLauncher() { }

    static int run() {
        try {
            launch(null);
            return 0;
        } catch (Exception error) {
            System.err.println("GMIT launch failed: " + error.getMessage());
            error.printStackTrace();
            return 1;
        }
    }

    static int runReplay(String replayArgument) {
        try {
            if (replayArgument == null) {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle("Select replay.xml");
                if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return 0;
                replayArgument = chooser.getSelectedFile().getAbsolutePath();
            }
            File replayFile = new File(replayArgument);
            if (!replayFile.isAbsolute()) throw new IllegalArgumentException("--gmit-replay requires an absolute replay XML file.");
            Path replay = replayFile.getCanonicalFile().toPath();
            if (!Files.isRegularFile(replay) || !replay.getFileName().toString().toLowerCase().endsWith(".xml")) {
                throw new IllegalArgumentException("--gmit-replay requires an existing absolute replay XML file.");
            }
            launch(replay);
            return 0;
        } catch (Exception error) {
            System.err.println("GMIT replay failed: " + error.getMessage());
            error.printStackTrace();
            return 1;
        }
    }

    private static void launch(Path replay) throws Exception {
        if (!"1.8".equals(System.getProperty("java.specification.version"))
                || !"64".equals(System.getProperty("sun.arch.data.model"))
                || !System.getProperty("os.name", "").startsWith("Windows")) {
            throw new IllegalStateException("Select Windows 64-bit JDK 8 as the Modular project's Java Platform.");
        }
        Path bin = new File(".").getCanonicalFile().toPath();
        Path helpers = bin.resolve("GMIT");
        Path scene = bin.resolve("Configurations/Greta/GMIT/GMIT - 3 characters - CereProc.xml");
        Path scenario = replay == null ? bin.resolve("Examples/GMIT/group-mi-red-meat.xml") : replay;
        for (Path required : Arrays.asList(bin.resolve("Modular.jar"), scene, scenario,
                helpers.resolve("SmokeProbe.java"), helpers.resolve("SeatedScene.java"),
                bin.resolve("Environments/GMIT/group-mi-room.xml"))) {
            if (!Files.isRegularFile(required)) {
                throw new IllegalStateException("Set Working Directory to the complete runtime bin and deploy the GMIT allowlist. Missing: " + required);
            }
        }
        Path javaHome = new File(System.getProperty("java.home")).toPath();
        Path java = javaHome.resolve("bin/java.exe");
        Path javac = javaHome.resolve("bin/javac.exe");
        if (!Files.isRegularFile(javac)) javac = javaHome.getParent().resolve("bin/javac.exe");
        if (!Files.isRegularFile(java) || !Files.isRegularFile(javac)) {
            throw new IllegalStateException("A complete Windows JDK 8 is required, not a JRE.");
        }
        Path output = helpers.resolve("local/netbeans-group-mi-" +
                new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date()));
        Files.createDirectories(output.getParent());
        Files.createDirectory(output);
        System.out.println((replay == null ? "Synthetic red-meat-reduction group MI" : "Offline recorded replay") + "; output: " + output);
        runOwned(Arrays.asList(javac.toString(), "-encoding", "UTF-8", "-source", "1.8", "-target", "1.8",
                "-classpath", System.getProperty("java.class.path"), "-d", output.toString(), helpers.resolve("SmokeProbe.java").toString(),
                helpers.resolve("SeatedScene.java").toString()), bin, output, "compile", 30);
        if (replay != null) {
            runOwned(Arrays.asList(java.toString(), "-cp", output + File.pathSeparator + System.getProperty("java.class.path"),
                    "SmokeProbe", "--validate", scenario.toString()), bin, output, "validate", 30);
        }

        Map<String, Boolean> originals = new LinkedHashMap<>();
        for (String name : SCRATCH) {
            Path file = bin.resolve(name);
            originals.put(name, Files.exists(file));
            if (Files.exists(file)) Files.copy(file, output.resolve(name.replace('/', '_') + ".before"));
        }
        StringBuilder state = new StringBuilder("{\"files\":[");
        for (Map.Entry<String, Boolean> entry : originals.entrySet()) {
            if (state.length() > 10) state.append(',');
            state.append("{\"path\":\"").append(entry.getKey()).append("\",\"existed\":")
                    .append(entry.getValue()).append('}');
        }
        Files.write(output.resolve("scratch-state.json"), state.append("]}\n").toString().getBytes("UTF-8"));
        try {
            List<String> command = new ArrayList<>();
            command.add(java.toString());
            command.add("-Djava.library.path=" + bin.resolve("Player/Lib/External/Win64"));
            // Keep the IDE's actual source-build classpath. Do not replace it with
            // runtime Modular.jar: source and installed builds are distinct.
            command.add("-cp");
            command.add(output + File.pathSeparator + System.getProperty("java.class.path"));
            command.add("SmokeProbe");
            command.add(scene.toString());
            command.add(output.toString());
            command.addAll(Arrays.asList(replay == null ? "10" : "0", replay == null ? "3" : "1", replay == null ? "10" : "0", scenario.toString()));
            int turnsUpperBound = replay == null ? 16 : 256;
            runOwned(command, bin, output, "playback", 120 + turnsUpperBound * (10 + 15 + 5 + 2));
        } finally {
            // The child has exited before restoration; native rendering threads
            // cannot overwrite these originals after they have been restored.
            for (Map.Entry<String, Boolean> entry : originals.entrySet()) {
                Path file = bin.resolve(entry.getKey());
                String base = entry.getKey().replace('/', '_');
                if (Files.exists(file)) Files.copy(file, output.resolve(base + ".after"), StandardCopyOption.REPLACE_EXISTING);
                if (entry.getValue()) Files.copy(output.resolve(base + ".before"), file, StandardCopyOption.REPLACE_EXISTING);
                else Files.deleteIfExists(file);
            }
            Files.write(output.resolve("scratch-restored.txt"), "Restored after child exit.\n".getBytes("UTF-8"));
        }
        System.out.println((replay == null ? "Synthetic group MI" : "Offline recorded replay") + " completed; runtime scratch files restored.");
    }

    private static void runOwned(List<String> command, Path bin, Path output, String stage, int seconds) throws Exception {
        Process process = new ProcessBuilder(command).directory(bin.toFile())
                .redirectOutput(output.resolve(stage + ".stdout.log").toFile())
                .redirectError(output.resolve(stage + ".stderr.log").toFile()).start();
        try {
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                throw new IllegalStateException(stage + " exceeded " + seconds + " seconds; see " + output);
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException(stage + " exited with " + process.exitValue() + "; see " + output);
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor();
            }
        }
    }
}
