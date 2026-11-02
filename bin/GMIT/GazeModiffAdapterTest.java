import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Headless checks for timestamp isolation and the Java/Python reply contract. */
public final class GazeModiffAdapterTest {
    public static void main(String[] args) throws Exception {
        GazeModiffAdapter.History a = new GazeModiffAdapter.History(), b = new GazeModiffAdapter.History();
        double[][] early = update(), late = update();
        early[0][0] = 2; late[0][1] = 4;
        a.queue(8, late); a.queue(5, early);
        for (int frame = 0; frame < 144; frame++) { a.sample(frame); b.sample(frame); }
        List<double[]> window = a.window(143);
        require(window.size() == 144, "exact window");
        require(window.get(4)[0] == 0, "future values must not leak");
        require(window.get(5)[0] == 1 && window.get(8)[0] == 3, "partial left/right masks must merge when due");
        require(b.window(143).get(8)[0] == 0, "patient histories must be isolated");
        require(a.window(144).isEmpty(), "no padded missing input frames");

        GazeModiffAdapter.Request request = new GazeModiffAdapter.Request("17", "patient-a", 3, 144);
        List<String> reply = new ArrayList<>(); reply.add(request.header());
        for (int i = 0; i < 18; i++) reply.add("0.1\t0.2\t0.25");
        require(GazeModiffAdapter.parseResponse(reply, request)[0][0] == .1, "do not apply gain twice");
        for (GazeModiffAdapter.Request wrong : Arrays.asList(
            new GazeModiffAdapter.Request("18", "patient-a", 3, 144),
            new GazeModiffAdapter.Request("17", "patient-b", 3, 144),
            new GazeModiffAdapter.Request("17", "patient-a", 4, 144),
            new GazeModiffAdapter.Request("17", "patient-a", 3, 145))) {
            expectFailure(() -> GazeModiffAdapter.parseResponse(reply, wrong));
        }
        for (String invalid : Arrays.asList("NaN", "Infinity", "-0.1", "0.26")) {
            reply.set(1, invalid + "\t0\t0");
            expectFailure(() -> GazeModiffAdapter.parseResponse(reply, request));
        }
        File folder = Files.createTempDirectory("gmit-modiff-test").toFile();
        File ready = new File(folder, "ready.json");
        try {
            Files.write(ready.toPath(), ("{\"status\":\"stopped\",\"protocol\":\"" + GazeModiffAdapter.VERSION + "\"}").getBytes(StandardCharsets.UTF_8));
            expectFailure(() -> GazeModiffAdapter.requireReady(folder));
            Files.write(ready.toPath(), ("{\"status\":\"ready\",\"protocol\":\"" + GazeModiffAdapter.VERSION + "\"}").getBytes(StandardCharsets.UTF_8));
            GazeModiffAdapter.requireReady(folder);
        } finally { ready.delete(); folder.delete(); }
        System.out.println("PASS future_frame_isolation partial_masks separate_patients exact_window response_identity finite_bounds stale_worker");
    }
    private static double[][] update() {
        double[][] result = new double[17][2];
        for (double[] row : result) Arrays.fill(row, Double.NaN);
        return result;
    }
    private interface Checked { void run() throws Exception; }
    private static void expectFailure(Checked check) throws Exception {
        try { check.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Invalid reply or readiness accepted");
    }
    private static void require(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
}
