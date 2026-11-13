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
                paths.put("tabletop", inwardTabletop(tabletop, new double[] {
                        -56.806941, 39.664494, -13.462372, -44.720994, -22.571566, 30.663032, 36.629634, -4.925643
                }, new double[] {
                        -66.289003, -61.145066, 20.972989, -41.728350, 12.238066, -27.498715, 38.885905, -10.295715
                }));
                // Adjacent low palms: visual wrist crossing without layering a hand in the air.
                paths.put("stacked", direct(tabletop, 'l', -61.673060, 36.061315, -10.237955,
                        -57.815249, -50.718109, 16.888745, 27.279045, -6.352591));
                break;
            case "patient-a":
                paths.put("tabletop", inwardTabletop(tabletop, new double[] {
                        -60.694491, 3.873186, -18.227492, -26.077770, 62.814573, -50.740385, 59.755979, -115.291381
                }, new double[] {
                        -53.712447, -5.642000, 21.818652, -41.612972, -56.423329, 54.390369, 68.667883, 94.695370
                }));
                paths.put("stacked", direct(tabletop, 'r', -63.381121, -22.582193, 29.781604,
                        -30.925226, 3.514444, 16.792397, 32.988420, 68.673722));
                paths.put("mixed", smoothMixed(tabletop, adjustLanding(route(tabletop, 'r', new int[] {9, 10, 11}, new double[][] {
                        {-49.6187282, -12.1329139, -0.2777627, -57.9277726, -35.9948679, 5.3784661, 78.7977736, 70.8996577},
                        {-11.2361225, -3.2232573, 19.3606181, -47.9433039, -26.8800773, -51.1341627, 32.3328952, 56.0079259},
                        {-33.7257310, -13.6795200, 33.0679770, -19.3730270, 24.9730230, -36.1780620, 10.7866300, 39.2281920}
                }), 'r', -2.8636558, 2.3308004, -12.2851595, -3.7959445,
                        5.2742091, 1.7103243, -8.6063156, 4.3960039)));
                break;
            case "patient-b":
                paths.put("tabletop", inwardTabletop(tabletop, new double[] {
                        -59.598770, 58.809952, -11.122575, -39.635926, -25.576263, 36.895844, 44.380539, 15.521024
                }, new double[] {
                        -54.860373, -61.153948, 2.908321, -65.825763, 43.201256, -44.764994, 59.457167, -23.960314
                }));
                paths.put("stacked", direct(tabletop, 'l', -100.870892, 97.884766, -17.213071,
                        -48.469842, 7.907049, -8.640289, 15.099704, -11.248771));
                paths.put("mixed", smoothMixed(tabletop, adjustLanding(route(tabletop, 'l', new int[] {10, 10, 10}, new double[][] {
                        {-67.5867736, 66.9773736, 0.5932812, -27.0095944, -1.2064837, 38.5310695, 18.1593169, -8.3310336},
                        {-24.4861334, 47.8322980, -14.4800510, -26.1054819, -38.1278149, 28.3441417, 37.8695137, -38.5463860},
                        {-38.9294470, 32.7138900, -32.1546260, -42.6403730, -34.8764460, 21.5771750, 39.4870440, -22.7759220}
                }), 'l', 7.5360993, -1.0967916, 10.2576788, -1.7116513,
                        8.1906018, 9.3044573, 6.7244227, 7.9009571)));
                break;
            default:
                throw new IllegalArgumentException("Unknown seated actor " + actor);
        }
        return paths;
    }

    private static List<Map<String, Double>> direct(Map<String, Double> tabletop, char side, double... angles) {
        return route(tabletop, side, new int[] {FRAMES}, new double[][] {angles});
    }

    private static List<Map<String, Double>> inwardTabletop(Map<String, Double> tabletop, double[] left, double[] right) {
        Map<String, Double> target = new LinkedHashMap<>();
        for (String side : new String[] {"l_", "r_"}) {
            double[] angles = "l_".equals(side) ? left : right;
            for (int i = 0; i < ARM.length; i++) target.put(side + ARM[i], angles[i]);
            for (String finger : new String[] {"index", "middle", "ring", "pinky"}) {
                for (int segment = 1; segment <= 3; segment++) {
                    String joint = side + finger + segment + "_flexion";
                    target.put(joint, tabletop.get(joint) - 4);
                }
            }
        }
        List<Map<String, Double>> poses = new ArrayList<>();
        poses.add(Collections.emptyMap());
        for (int frame = 1; frame <= FRAMES; frame++) {
            double t = (double)frame / FRAMES;
            double weight = t * t * (3 - 2 * t);
            Map<String, Double> pose = new LinkedHashMap<>();
            for (Map.Entry<String, Double> joint : target.entrySet()) {
                double from = tabletop.get(joint.getKey());
                pose.put(joint.getKey(), from + weight * (joint.getValue() - from));
            }
            poses.add(Collections.unmodifiableMap(pose));
        }
        return Collections.unmodifiableList(poses);
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

    /** Round the interior waypoint corners while keeping both calibrated endpoints. */
    private static List<Map<String, Double>> smoothMixed(Map<String, Double> tabletop, List<Map<String, Double>> route) {
        List<Map<String, Double>> poses = new ArrayList<>(route);
        for (int pass = 0; pass < 5; pass++) {
            List<Map<String, Double>> previous = poses;
            poses = new ArrayList<>(previous);
            for (int frame = 1; frame < FRAMES; frame++) {
                Map<String, Double> pose = new LinkedHashMap<>();
                Map<String, Double> before = previous.get(frame - 1);
                Map<String, Double> after = previous.get(frame + 1);
                for (Map.Entry<String, Double> joint : previous.get(frame).entrySet()) {
                    String name = joint.getKey();
                    pose.put(name, .5 * joint.getValue()
                            + .25 * before.getOrDefault(name, tabletop.get(name))
                            + .25 * after.get(name));
                }
                poses.set(frame, Collections.unmodifiableMap(pose));
            }
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
