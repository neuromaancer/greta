import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Actor-specific arm calibrations for the stock seated room. */
final class SeatedRestPoseLibrary {
    static final int FRAMES = 30;
    private static final String[] ARM = {
        "shoulder_flexion", "shoulder_abduct", "shoulder_twisting",
        "elbow_flexion", "elbow_twisting", "wrist_flexion", "wrist_pivot", "wrist_twisting"
    };

    private SeatedRestPoseLibrary() { }

    static Map<String, List<Map<String, Double>>> paths(String actor, Map<String, Double> tabletop) {
        Map<String, List<Map<String, Double>>> paths = new LinkedHashMap<>();
        switch (actor) {
            case "facilitator":
                paths.put("stacked", direct(tabletop, 'l', -88.523627, 53.924050, -16.154104,
                        -37.317524, -36.835702, -3.319034, 21.030806, -6.576036));
                break;
            case "patient-a":
                paths.put("stacked", direct(tabletop, 'r', -63.381121, -22.582193, 29.781604,
                        -30.925226, 3.514444, 16.792397, 32.988420, 68.673722));
                paths.put("mixed", adjustLanding(route(tabletop, 'r', new int[] {9, 10, 11}, new double[][] {
                        {-49.6187282, -12.1329139, -0.2777627, -57.9277726, -35.9948679, 5.3784661, 78.7977736, 70.8996577},
                        {-11.2361225, -3.2232573, 19.3606181, -47.9433039, -26.8800773, -51.1341627, 32.3328952, 56.0079259},
                        {-33.7257310, -13.6795200, 33.0679770, -19.3730270, 24.9730230, -36.1780620, 10.7866300, 39.2281920}
                }), 'r', -2.8636558, 2.3308004, -12.2851595, -3.7959445,
                        5.2742091, 1.7103243, -8.6063156, 4.3960039));
                break;
            case "patient-b":
                paths.put("stacked", direct(tabletop, 'l', -100.870892, 97.884766, -17.213071,
                        -48.469842, 7.907049, -8.640289, 15.099704, -11.248771));
                paths.put("mixed", adjustLanding(route(tabletop, 'l', new int[] {10, 10, 10}, new double[][] {
                        {-67.5867736, 66.9773736, 0.5932812, -27.0095944, -1.2064837, 38.5310695, 18.1593169, -8.3310336},
                        {-24.4861334, 47.8322980, -14.4800510, -26.1054819, -38.1278149, 28.3441417, 37.8695137, -38.5463860},
                        {-38.9294470, 32.7138900, -32.1546260, -42.6403730, -34.8764460, 21.5771750, 39.4870440, -22.7759220}
                }), 'l', 7.5360993, -1.0967916, 10.2576788, -1.7116513,
                        8.1906018, 9.3044573, 6.7244227, 7.9009571));
                break;
            default:
                throw new IllegalArgumentException("Unknown seated actor " + actor);
        }
        return paths;
    }

    private static List<Map<String, Double>> direct(Map<String, Double> tabletop, char side, double... angles) {
        return route(tabletop, side, new int[] {FRAMES}, new double[][] {angles});
    }

    private static List<Map<String, Double>> adjustLanding(List<Map<String, Double>> route, char side, double... delta) {
        List<Map<String, Double>> poses = new ArrayList<>(route);
        int start = 11;
        for (int frame = start + 1; frame <= FRAMES; frame++) {
            double t = (double)(frame - start) / (FRAMES - start);
            double weight = t * t * (3 - 2 * t);
            Map<String, Double> pose = new LinkedHashMap<>(route.get(frame));
            for (int joint = 0; joint < ARM.length; joint++) {
                String name = side + "_" + ARM[joint];
                pose.put(name, pose.get(name) + weight * delta[joint]);
            }
            poses.set(frame, Collections.unmodifiableMap(pose));
        }
        return Collections.unmodifiableList(poses);
    }

    private static List<Map<String, Double>> route(Map<String, Double> tabletop, char side, int[] durations, double[][] waypoints) {
        List<Map<String, Double>> poses = new ArrayList<>();
        poses.add(Collections.emptyMap());
        double[] tabletopAngles = new double[ARM.length];
        for (int i = 0; i < ARM.length; i++) tabletopAngles[i] = tabletop.get(side + "_" + ARM[i]);
        for (int segment = 0; segment < waypoints.length; segment++) {
            double[] start = segment == 0 ? tabletopAngles : waypoints[segment - 1];
            for (int frame = 1; frame <= durations[segment]; frame++) {
                double t = (double)frame / durations[segment];
                // Follow the checked lines between edge-clearing waypoints.
                // The direct path has room for a full easing envelope.
                double weight = waypoints.length != 1 ? t
                        : t < .2 ? t * t * (2 - t / .2) / .2
                        : t > .8 ? 1 - (1 - t) * (1 - t) * (2 - (1 - t) / .2) / .2 : t;
                Map<String, Double> pose = new LinkedHashMap<>();
                for (int i = 0; i < ARM.length; i++) {
                    String joint = side + "_" + ARM[i];
                    pose.put(joint, start[i] + weight * (waypoints[segment][i] - start[i]));
                }
                poses.add(Collections.unmodifiableMap(pose));
            }
        }
        return Collections.unmodifiableList(poses);
    }
}
