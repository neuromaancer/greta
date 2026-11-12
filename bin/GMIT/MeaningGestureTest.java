import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        require(facilitator.valueAt("l_shoulder_flexion", 190, 5) < -8,
                "stock proximal delta is retained from seated base");
        require(facilitator.valueAt("r_index1_flexion", 168, 12) > 29, "right finger stock motion retained");
        require(facilitator.valueAt("r_wrist_flexion", 168, 0) > 16, "partial same-frame masks merge");
        require(patient.valueAt("l_shoulder_flexion", 180, 5) > -6 && patient.valueAt("l_shoulder_flexion", 180, 5) < 0,
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
        String trace = facilitator.rawTrace();
        require(trace.startsWith("audio_start_frame=150\naudio_end_frame=180\n")
                        && trace.indexOf("150|l_shoulder_flexion=-10.0,r_index1_flexion=32.0,r_wrist_flexion=17.0") >= 0
                        && trace.indexOf("170|l_shoulder_flexion=-30.0,r_index1_flexion=60.0") >= 0,
                "raw trace retains sorted BAP frames and actual audio bounds without dialogue text");
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
        Map<String, Double> workspaceOffsets = new LinkedHashMap<String, Double>();
        workspaceOffsets.put("l_shoulder_flexion", Double.valueOf(-2));
        MeaningGesture.Turn fitted = new MeaningGesture.Turn("facilitator", "f-fit", 1.0, workspaceOffsets);
        Map<String, Double> fittedNeutral = new LinkedHashMap<String, Double>();
        fittedNeutral.put("l_shoulder_flexion", Double.valueOf(0));
        fittedNeutral.put("l_wrist_flexion", Double.valueOf(4));
        fittedNeutral.put("l_index1_flexion", Double.valueOf(35));
        fitted.capture(350, fittedNeutral);
        Map<String, Double> fittedStroke = new LinkedHashMap<String, Double>(fittedNeutral);
        fittedStroke.put("l_shoulder_flexion", Double.valueOf(-16));
        fittedStroke.put("l_wrist_flexion", Double.valueOf(37));
        fittedStroke.put("l_index1_flexion", Double.valueOf(61));
        fitted.capture(356, fittedStroke);
        workspaceOffsets.put("l_shoulder_flexion", Double.valueOf(-99));
        fitted.bind(14000, 15200);
        require(Math.abs(fitted.valueAt("l_shoulder_flexion", 368, -6) + 24) < .01,
                "semantic workspace offset applies once after the stock retarget");
        require(Math.abs(fitted.valueAt("l_wrist_flexion", 368, 18) - 37) < .01
                        && Math.abs(fitted.valueAt("l_index1_flexion", 368, 28) - 61) < .01,
                "workspace fitting preserves full captured wrist and finger values");
        require(fitted.valueAt("l_shoulder_flexion", 349, -6) == -6
                        && fitted.valueAt("l_shoulder_flexion", fitted.restoreEndFrame() + 1, -6) == -6,
                "workspace fitting leaves rest frames at the exact clasp");
        workspaceOffsets.put("l_shoulder_flexion", Double.valueOf(-2));
        MeaningGesture.Turn partial = new MeaningGesture.Turn("facilitator", "f-partial", 1.0, workspaceOffsets);
        Map<String, Double> fingerOnly = new LinkedHashMap<String, Double>();
        fingerOnly.put("l_index1_flexion", Double.valueOf(41));
        partial.capture(600, fingerOnly);
        Map<String, Double> futureShoulder = new LinkedHashMap<String, Double>();
        futureShoulder.put("l_shoulder_flexion", Double.valueOf(-10));
        partial.capture(620, futureShoulder);
        partial.bind(24000, 25200);
        require(Math.abs(partial.valueAt("l_shoulder_flexion", 618, -6) + 8) < .01,
                "a future shoulder neutral cannot create a phantom earlier stock delta");
        require(Math.abs(partial.valueAt("l_index1_flexion", 618, 28) - 41) < .01,
                "a partial frame retains its original finger target while another joint is absent");
        boolean fingerOffsetRejected = false;
        try {
            Map<String, Double> invalid = new LinkedHashMap<String, Double>();
            invalid.put("l_index1_flexion", Double.valueOf(1));
            new MeaningGesture.Turn("facilitator", "f-invalid-fit", 1.0, invalid);
        } catch (IllegalArgumentException expected) { fingerOffsetRejected = true; }
        require(fingerOffsetRejected, "workspace fitting rejects wrist and finger offsets");
        boolean excessiveOffsetRejected = false;
        try {
            Map<String, Double> invalid = new LinkedHashMap<String, Double>();
            invalid.put("l_elbow_flexion", Double.valueOf(36));
            new MeaningGesture.Turn("facilitator", "f-invalid-tabletop-fit", 1.0, invalid);
        } catch (IllegalArgumentException expected) { excessiveOffsetRejected = true; }
        require(excessiveOffsetRejected, "workspace fitting retains explicit finite angular bounds");
        MeaningGesture.Turn[] styled = new MeaningGesture.Turn[3];
        String[] styleNames = {"reserved", "balanced", "animated"};
        for (int i = 0; i < styled.length; i++) {
            styled[i] = new MeaningGesture.Turn("facilitator", "style-check-" + i, .35,
                    new LinkedHashMap<String, Double>(), MeaningGesture.Style.named(styleNames[i]));
            styled[i].capture(700, arms(0, 20));
            styled[i].capture(720, arms(-20, 54));
            styled[i].bind(28000, 30000);
            require(Math.abs(styled[i].valueAt("r_index1_flexion", 726, 12) - 54) < .001,
                    "every style preserves the semantic finger handshape");
            require(styled[i].firstRawFrame() == 700 && styled[i].lastRawFrame() == 720,
                    "style variation preserves captured gesture frame numbers");
        }
        require(styled[0].valueAt("l_shoulder_flexion", 726, 0) > styled[1].valueAt("l_shoulder_flexion", 726, 0)
                        && styled[1].valueAt("l_shoulder_flexion", 726, 0) > styled[2].valueAt("l_shoulder_flexion", 726, 0),
                "the same semantic trajectory has distinct per-character proximal amplitudes");
        require(styled[0].restoreEndFrame() == styled[2].restoreEndFrame(),
                "styles retain common speech completion and restoration ownership");
        Map<String, Double> earlyOffsets = new LinkedHashMap<String, Double>();
        earlyOffsets.put("l_shoulder_twisting", Double.valueOf(20));
        MeaningGesture.Turn early = new MeaningGesture.Turn("facilitator", "early-release", .35,
                earlyOffsets, MeaningGesture.Style.named("balanced"), .3);
        early.capture(800, arms(0, 20)); early.bind(32000, 34000);
        require(Math.abs(early.valueAt("l_shoulder_twisting", 800, 0)) < .001
                        && Math.abs(early.valueAt("l_shoulder_twisting", 803, 0) - 6) < .001
                        && Math.abs(early.valueAt("l_shoulder_twisting", 809, 0) - 20) < .001,
                "calibrated release lead joins continuously into the full shoulder workspace");
        require(early.clearanceWeightAt(803) == 0 && early.clearanceWeightAt(809) == 1,
                "early arm motion does not relax either hand-clearance phase threshold");
        Map<String, Double> clasp = new LinkedHashMap<String, Double>();
        clasp.put("l_shoulder_flexion", Double.valueOf(-6));
        clasp.put("r_shoulder_flexion", Double.valueOf(-6));
        clasp.put("l_wrist_flexion", Double.valueOf(18));
        clasp.put("r_wrist_flexion", Double.valueOf(-18));
        clasp.put("l_index1_flexion", Double.valueOf(28));
        clasp.put("r_index1_flexion", Double.valueOf(28));
        clasp.put("l_middle1_flexion", Double.valueOf(24));
        clasp.put("r_middle1_flexion", Double.valueOf(24));
        MeaningGesture.Turn bimanual = new MeaningGesture.Turn("facilitator", "f-bimanual", 1.0);
        Map<String, Double> bimanualNeutral = new LinkedHashMap<String, Double>();
        bimanualNeutral.put("l_shoulder_flexion", Double.valueOf(0));
        bimanualNeutral.put("r_shoulder_flexion", Double.valueOf(0));
        bimanualNeutral.put("l_wrist_flexion", Double.valueOf(4));
        bimanualNeutral.put("r_wrist_flexion", Double.valueOf(-4));
        bimanualNeutral.put("l_index1_flexion", Double.valueOf(35));
        bimanualNeutral.put("r_index1_flexion", Double.valueOf(35));
        bimanual.capture(400, bimanualNeutral);
        Map<String, Double> bimanualStroke = new LinkedHashMap<String, Double>(bimanualNeutral);
        bimanualStroke.put("l_shoulder_flexion", Double.valueOf(-16));
        bimanualStroke.put("r_shoulder_flexion", Double.valueOf(-16));
        bimanualStroke.put("l_wrist_flexion", Double.valueOf(37));
        bimanualStroke.put("r_wrist_flexion", Double.valueOf(-37));
        bimanualStroke.put("l_index1_flexion", Double.valueOf(61));
        bimanualStroke.put("r_index1_flexion", Double.valueOf(61));
        bimanual.capture(412, bimanualStroke);
        bimanual.bind(16000, 17200);
        Map<String, Double> unclasp = bimanual.poseAt(403, clasp);
        require("unclasp".equals(bimanual.phaseNameAt(403))
                        && unclasp.get("l_index1_flexion").doubleValue() < clasp.get("l_index1_flexion").doubleValue(),
                "fingers release before semantic hand targets move");
        require(unclasp.get("l_middle1_flexion").doubleValue() < clasp.get("l_middle1_flexion").doubleValue(),
                "unclasp releases fingers omitted by a partial semantic BAP mask");
        Map<String, Double> semantic = bimanual.poseAt(418, clasp);
        require("semantic".equals(bimanual.phaseNameAt(418))
                        && Math.abs(semantic.get("l_index1_flexion").doubleValue() - 61) < .01
                        && Math.abs(semantic.get("r_index1_flexion").doubleValue() - 61) < .01,
                "semantic phase retains the latest original captured finger targets after onset");
        Map<String, Double> stroke = bimanual.poseAt(418, clasp);
        require(Math.abs(stroke.get("l_index1_flexion").doubleValue() - 61) < .01
                        && Math.abs(stroke.get("r_index1_flexion").doubleValue() - 61) < .01,
                "later semantic stroke keeps both captured finger targets unchanged");
        require(bimanual.clearanceWeightAt(400) == 0.0 && bimanual.clearanceWeightAt(401) == 0.0
                        && bimanual.clearanceWeightAt(402) == 0.0 && bimanual.clearanceWeightAt(403) == 0.0,
                "accepted baseline contact remains through the first three finger-unclasp frames");
        require(bimanual.clearanceWeightAt(404) > 0.0 && bimanual.clearanceWeightAt(404) < 1.0
                        && bimanual.clearanceWeightAt(405) > bimanual.clearanceWeightAt(404)
                        && bimanual.clearanceWeightAt(409) == 1.0,
                "air-gap request rises only after fingers begin to release and reaches workspace strength at frame nine");
        int reclasp = bimanual.restoreEndFrame() - 1;
        int reclaspStart = bimanual.restoreEndFrame() - MeaningGesture.WORKSPACE_RETURN_FRAMES + MeaningGesture.WORKSPACE_RETURN_SEPARATED_FRAMES;
        require("reclasp".equals(bimanual.phaseNameAt(reclasp))
                        && bimanual.clearanceWeightAt(reclaspStart) == 1.0
                        && bimanual.clearanceWeightAt(reclaspStart + 1) < 1.0
                        && bimanual.clearanceWeightAt(reclasp - 2) == 0.0
                        && bimanual.clearanceWeightAt(reclasp - 1) == 0.0
                        && bimanual.clearanceWeightAt(reclasp) == 0.0
                        && bimanual.clearanceWeightAt(bimanual.restoreEndFrame()) == 0.0,
                "reclasp fades before its final three accepted-contact frames");
        Map<String, Double> restored = bimanual.poseAt(bimanual.restoreEndFrame(), clasp);
        require(restored.equals(clasp), "all arm and hand channels restore exact calibrated clasp");
        MeaningGesture.Turn returnPath = new MeaningGesture.Turn("patient-b", "b-return-path", 1.0);
        returnPath.capture(500, bimanualNeutral);
        returnPath.capture(506, bimanualStroke);
        List<Map<String, Double>> path = new ArrayList<Map<String, Double>>();
        List<Double> pathClearance = new ArrayList<Double>();
        for (int index = 0; index <= 6; index++) {
            Map<String, Double> pose = new LinkedHashMap<String, Double>(clasp);
            pose.put("l_shoulder_flexion", Double.valueOf(-6 - index));
            pose.put("r_shoulder_flexion", Double.valueOf(-6 - index));
            pose.put("l_wrist_flexion", Double.valueOf(18 + index));
            pose.put("r_wrist_flexion", Double.valueOf(-18 - index));
            pose.put("l_index1_flexion", Double.valueOf(28 - index));
            pose.put("r_index1_flexion", Double.valueOf(28 - index));
            pose.put("l_middle1_flexion", Double.valueOf(24 - index));
            pose.put("r_middle1_flexion", Double.valueOf(24 - index));
            path.add(pose); pathClearance.add(Double.valueOf(index / 6.0));
        }
        Map<String, Double> expectedJoin = new LinkedHashMap<String, Double>(path.get(6));
        Map<String, Double> expectedReverse = new LinkedHashMap<String, Double>(path.get(5));
        returnPath.setHandReturnPath(path, pathClearance);
        path.get(6).put("l_wrist_flexion", Double.valueOf(999)); pathClearance.set(6, Double.valueOf(0));
        returnPath.bind(20000, 21200);
        require(Math.abs(returnPath.valueAt("r_index1_flexion", 518, 28) - 61) < .01,
                "return path leaves the exact captured semantic finger frame unchanged");
        int returnStart = returnPath.returnStartFrame(), pathJoin = returnPath.reclaspStartFrame();
        require(pathJoin == returnStart + MeaningGesture.WORKSPACE_RETURN_SEPARATED_FRAMES,
                "return path exposes the deterministic separated transition boundary");
        require(returnPath.poseAt(pathJoin, clasp).equals(expectedJoin)
                        && Math.abs(returnPath.clearanceWeightAt(pathJoin) - 1.0) < .001,
                "return path reaches the exact verified two-hand join pose and clearance");
        require(returnPath.poseAt(pathJoin + 1, clasp).equals(expectedReverse)
                        && Math.abs(returnPath.clearanceWeightAt(pathJoin + 1) - 5.0 / 6.0) < .001,
                "reclasp replays the cached join path in reverse with matching clearance");
        require(returnPath.poseAt(pathJoin + 6, clasp).equals(clasp)
                        && returnPath.clearanceWeightAt(pathJoin + 6) == 0.0,
                "reverse join path reaches and holds the exact calibrated clasp");
        boolean afterBindRejected = false;
        try { returnPath.setHandReturnPath(path, pathClearance); } catch (IllegalStateException expected) { afterBindRejected = true; }
        require(afterBindRejected, "return path ownership closes before bind");
        boolean bodyRejected = false;
        try {
            List<Map<String, Double>> invalid = new ArrayList<Map<String, Double>>();
            Map<String, Double> body = new LinkedHashMap<String, Double>(); body.put("spine_tilt", Double.valueOf(1));
            invalid.add(body); invalid.add(body);
            new MeaningGesture.Turn("facilitator", "f-invalid-return", 1.0)
                    .setHandReturnPath(invalid, java.util.Arrays.asList(Double.valueOf(0), Double.valueOf(1)));
        } catch (IllegalArgumentException expected) { bodyRejected = true; }
        require(bodyRejected, "return path retains the arm-and-hand-only allowlist");
        boolean shapeRejected = false;
        try {
            List<Map<String, Double>> tooLong = new ArrayList<Map<String, Double>>();
            List<Double> tooLongWeights = new ArrayList<Double>();
            for (int index = 0; index < 13; index++) {
                tooLong.add(new LinkedHashMap<String, Double>(clasp)); tooLongWeights.add(Double.valueOf(0));
            }
            new MeaningGesture.Turn("facilitator", "f-too-long-return", 1.0).setHandReturnPath(tooLong, tooLongWeights);
        } catch (IllegalArgumentException expected) { shapeRejected = true; }
        require(shapeRejected, "return path cannot outlive the fixed reclasp duration");
        MeaningGesture.Turn direct = new MeaningGesture.Turn("facilitator", "f-direct-return", .24);
        direct.setDirectReturn(true);
        Map<String, Double> wrist = new LinkedHashMap<String, Double>();
        wrist.put("l_wrist_flexion", Double.valueOf(40));
        direct.capture(100, wrist); direct.capture(120, wrist); direct.bind(4000, 5600);
        int directStart = direct.returnStartFrame();
        require(direct.valueAt("l_wrist_flexion", directStart, 12) == 40,
                "direct return starts at the final semantic pose without a jump");
        double previous = 40;
        for (int offset = 1; offset < MeaningGesture.RETURN_FRAMES; offset++) {
            double value = direct.valueAt("l_wrist_flexion", directStart + offset, 12);
            require(value < previous && value > 12, "direct return progresses without an intermediate stop or overshoot");
            require("return".equals(direct.phaseNameAt(directStart + offset)), "direct return has one continuous phase");
            require(direct.clearanceWeightAt(directStart + offset) == 1.0, "direct return retains the full hand air gap");
            previous = value;
        }
        require(Math.abs(direct.valueAt("l_wrist_flexion", directStart + 30, 12) - 26) < .000001,
                "direct return midpoint lies halfway between semantic pose and rest");
        require(direct.valueAt("l_wrist_flexion", direct.restoreEndFrame(), 12) == 12,
                "direct return ends at the exact resting pose");
        require(direct.clearanceWeightAt(direct.restoreEndFrame()) == 0
                        && "restored".equals(direct.phaseNameAt(direct.restoreEndFrame())),
                "direct endpoint uses the geometry solver's checked exact-rest branch");
        int nominalDirectEnd = direct.restoreEndFrame();
        Map<String, Double> tabletopBase = new LinkedHashMap<String, Double>(wrist);
        tabletopBase.put("l_shoulder_flexion", -55.0);
        tabletopBase.put("r_shoulder_flexion", -55.0);
        tabletopBase.put("l_elbow_flexion", -38.0);
        tabletopBase.put("r_elbow_flexion", -38.0);
        for (MeaningGesture.TabletopApproach profile : MeaningGesture.TabletopApproach.values()) {
            require(direct.poseAt(directStart, tabletopBase, profile).equals(direct.poseAt(directStart, tabletopBase))
                            && direct.poseAt(nominalDirectEnd, tabletopBase, profile).equals(tabletopBase),
                    "tabletop approach preserves the final gesture and reaches the exact rest");
            for (int frame = directStart; frame <= nominalDirectEnd; frame++) {
                Map<String, Double> baseline = direct.poseAt(frame, tabletopBase);
                Map<String, Double> approach = direct.poseAt(frame, tabletopBase, profile);
                for (String joint : tabletopBase.keySet()) {
                    double lift = baseline.get(joint) - approach.get(joint);
                    require(lift >= 0 && lift <= profile.lift(joint),
                            "tabletop approach keeps the handshape and bounded joint targets");
                    if (frame == directStart + 30) require(lift == profile.lift(joint), "tabletop lift peaks mid-return");
                }
            }
        }
        require(direct.extendDirectSettling(nominalDirectEnd)
                        && direct.restoreEndFrame() == nominalDirectEnd + 1
                        && direct.directSettleFrames() == 1,
                "only an unresolved direct endpoint can open one bounded settling frame");
        require("settling".equals(direct.phaseNameAt(nominalDirectEnd))
                        && direct.clearanceWeightAt(nominalDirectEnd) == 0
                        && direct.valueAt("l_wrist_flexion", nominalDirectEnd, 12) == 12,
                "the settling tail holds the exact raw rest pose while collision correction releases");
        require(!direct.extendDirectSettling(nominalDirectEnd),
                "direct settling cannot open twice at the same completed tail frame");
        while (direct.directSettleFrames() < MeaningGesture.MAX_DIRECT_SETTLE_FRAMES)
            require(direct.extendDirectSettling(direct.restoreEndFrame()),
                    "each bounded direct-settling extension starts only at the current endpoint");
        require(direct.restoreEndFrame() == nominalDirectEnd + MeaningGesture.MAX_DIRECT_SETTLE_FRAMES
                        && !direct.extendDirectSettling(direct.restoreEndFrame()),
                "direct settling is capped at sixteen 25 fps frames before fail-closed handling");
        MeaningGesture.Turn lateDirect = new MeaningGesture.Turn("patient-a", "a-late-direct", .24);
        lateDirect.setDirectReturn(true); lateDirect.capture(100, wrist); lateDirect.bind(4000, 5600);
        int oldNominalEnd = lateDirect.restoreEndFrame();
        require(lateDirect.extendDirectSettling(oldNominalEnd), "late-capture fixture opens an old endpoint tail");
        lateDirect.capture(180, wrist);
        int movedNominalEnd = lateDirect.restoreEndFrame();
        require(movedNominalEnd > oldNominalEnd && lateDirect.directSettleFrames() == 0,
                "a new maximum raw frame resets only the obsolete direct-settling tail");
        require(lateDirect.extendDirectSettling(movedNominalEnd), "moved endpoint can independently open a tail");
        lateDirect.capture(180, wrist); lateDirect.capture(160, wrist);
        require(lateDirect.restoreEndFrame() == movedNominalEnd + 1 && lateDirect.directSettleFrames() == 1,
                "duplicate and non-endpoint raw captures preserve the active tail");
        require(MeaningGesture.MAX_DIRECT_RESTORE_AFTER_AUDIO_MILLIS == 8240,
                "shared playback wait bound covers capture tail, hold, return, and settling tail");
        boolean returnModeLocked = false;
        try { direct.setDirectReturn(false); } catch (IllegalStateException expected) { returnModeLocked = true; }
        require(returnModeLocked, "return mode cannot change after audio binding");
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
