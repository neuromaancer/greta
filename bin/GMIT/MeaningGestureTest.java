import java.util.LinkedHashMap;
import java.util.Map;

/** Headless checks for stock MeaningMiner BAP capture, timing, and restoration. */
public final class MeaningGestureTest {
    public static void main(String[] args) {
        MeaningGesture.Turn facilitator = new MeaningGesture.Turn("facilitator", "f-1", 1.0);
        MeaningGesture.Turn patient = new MeaningGesture.Turn("patient-a", "a-1", 1.0);
        facilitator.capture(150, arms(-10, 32));
        // A queued future frame must preserve, not erase, the earlier pose.
        facilitator.capture(190, arms(-28, 54));
        Map<String, Double> sameFrameRight = new LinkedHashMap<String, Double>();
        sameFrameRight.put("r_wrist_flexion", Double.valueOf(17));
        facilitator.capture(150, sameFrameRight);
        patient.capture(160, arms(-4, 19));
        patient.capture(180, arms(-14, 19));
        facilitator.bind(6000, 7200); // frames 150..180
        patient.bind(6400, 7200);
        require(facilitator.hasRawFrames() && facilitator.rawFrameCount() == 2, "captured full BAP arm frames");
        require(facilitator.valueAt("l_shoulder_flexion", 150, 5) == 5, "smooth onset starts from seated clasp");
        require(Math.abs(facilitator.valueAt("l_shoulder_flexion", 156, 5) - 5) < .001,
                "stock proximal neutral maps exactly to seated clasp");
        require(facilitator.valueAt("l_shoulder_flexion", 196, 5) < -8,
                "stock proximal delta is retained from seated base");
        require(facilitator.valueAt("r_index1_flexion", 156, 12) > 29, "right finger stock motion retained");
        require(facilitator.valueAt("r_wrist_flexion", 156, 0) > 16, "partial same-frame masks merge");
        require(patient.valueAt("l_shoulder_flexion", 186, 5) > -6 && patient.valueAt("l_shoulder_flexion", 186, 5) < 0,
                "actors retain independent captured motions");
        require(facilitator.valueAt("l_shoulder_flexion", facilitator.restoreEndFrame() + 1, 5) == 5,
                "finite arm-only return restores exact clasp");
        require(!facilitator.hasValueAt("spine", 160), "only supplied arm or finger channels are exposed");
        Map<String, Double> emitted = new LinkedHashMap<String, Double>();
        emitted.put("l_index2_flexion", Double.valueOf(-16.9));
        facilitator.recordEmitted(170, emitted);
        facilitator.capture(170, arms(-30, 60)); // late raw data must not rewrite queued output evidence.
        require(Math.abs(facilitator.emittedValueAt("l_index2_flexion", 170).doubleValue() + 16.9) < .001,
                "verification uses emitted frame snapshot after late partial capture");
        MeaningGesture.Turn seatedGain = new MeaningGesture.Turn("patient-b", "b-1", .35);
        seatedGain.capture(100, arms(-10, 32));
        seatedGain.capture(120, arms(-30, 54));
        seatedGain.bind(4000, 5200);
        require(Math.abs(seatedGain.valueAt("l_shoulder_flexion", 126, 5) + 2) < .01,
                "seated gain scales only the proximal stock delta");
        require(Math.abs(seatedGain.valueAt("r_index1_flexion", 126, 12) - 54) < .01,
                "seated gain does not scale stock finger handshape");
        MeaningGesture.Turn clearance = new MeaningGesture.Turn("facilitator", "f-clearance", 1.0);
        clearance.capture(200, proximal(0, 0, 0, 0));
        clearance.capture(220, proximal(-12, 12, 0, 0));
        clearance.bind(8000, 9600);
        require(Math.abs(clearance.valueAt("l_shoulder_abduct", 206, 4) - 4) < .01
                        && Math.abs(clearance.valueAt("r_shoulder_abduct", 206, -4) + 4) < .01,
                "shoulder clearance is zero at stock neutral");
        require(Math.abs(clearance.valueAt("l_shoulder_abduct", 226, 4) - 10) < .01
                        && Math.abs(clearance.valueAt("r_shoulder_abduct", 226, -4) + 10) < .01,
                "active stock motion clears left and right shoulders outward");
        require(Math.abs(clearance.valueAt("l_shoulder_abduct", clearance.restoreEndFrame(), 4) - 4) < .01,
                "shoulder clearance returns exactly to clasp");
        MeaningGesture.Turn tabletop = new MeaningGesture.Turn("facilitator", "f-table", 1.0);
        tabletop.capture(300, proximal(0, 0, 0, 0));
        tabletop.capture(320, proximal(14, 14, 0, 0));
        tabletop.bind(12000, 13600);
        require(Math.abs(tabletop.valueAt("l_shoulder_flexion", 326, -6) + 6) < .01
                        && Math.abs(tabletop.valueAt("r_shoulder_flexion", 326, -6) + 6) < .01,
                "downward stock shoulder motion is clipped at the tabletop clasp");
        System.out.println("PASS meaning_gesture_stock_bap_capture_both_arms_fingers_independent_return");
    }

    private static Map<String, Double> arms(double leftShoulder, double rightFinger) {
        Map<String, Double> values = new LinkedHashMap<String, Double>();
        values.put("l_shoulder_flexion", Double.valueOf(leftShoulder));
        values.put("r_index1_flexion", Double.valueOf(rightFinger));
        return values;
    }
    private static Map<String, Double> proximal(double leftFlexion, double rightFlexion, double leftAbduct, double rightAbduct) {
        Map<String, Double> values = new LinkedHashMap<String, Double>();
        values.put("l_shoulder_flexion", Double.valueOf(leftFlexion));
        values.put("r_shoulder_flexion", Double.valueOf(rightFlexion));
        values.put("l_shoulder_abduct", Double.valueOf(leftAbduct));
        values.put("r_shoulder_abduct", Double.valueOf(rightAbduct));
        return values;
    }
    private static void require(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
