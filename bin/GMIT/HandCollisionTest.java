import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/** Headless checks for immutable Autodesk-snapshot hand/forearm safety math. */
public final class HandCollisionTest {
    public static void main(String[] args) {
        FakeBone root = bone("Root", null, 0, 0, 0);
        FakeBone spine = bone("Spine", root, 0, 0, 0);
        FakeBone spine1 = bone("Spine1", spine, 0, .2, 0);
        FakeBone spine2 = bone("Spine2", spine1, 0, .2, 0);
        FakeBone spine3 = bone("Spine3", spine2, 0, .2, 0);
        FakeBone spine4 = bone("Spine4", spine3, 0, .2, 0);
        FakeBone neck = bone("Neck", spine4, 0, .2, 0);
        FakeBone leftArm = bone("LeftArm", root, -1, 0, 0);
        FakeBone leftForearm = bone("LeftForeArm", leftArm, 0, 0, 1);
        FakeBone leftHand = bone("LeftHand", leftForearm, 0, 0, 1);
        FakeBone rightArm = bone("RightArm", root, 1, 0, 0);
        FakeBone rightForearm = bone("RightForeArm", rightArm, 0, 0, 1);
        FakeBone rightHand = bone("RightHand", rightForearm, 0, 0, 1);
        FakeBone[] leftFingers = fingers(leftHand, "Left"), rightFingers = fingers(rightHand, "Right");
        FakeAgent agent = new FakeAgent(all(new FakeBone[] {root, spine, spine1, spine2, spine3, spine4, neck, leftArm, leftForearm, leftHand, rightArm, rightForearm, rightHand}, leftFingers, rightFingers));
        add(agent, leftArm, "l_shoulder_abduct"); add(agent, rightArm, "r_shoulder_abduct");
        add(agent, leftForearm, "l_elbow_flexion"); add(agent, rightForearm, "r_elbow_flexion");
        add(agent, leftHand, "l_wrist_flexion"); add(agent, rightHand, "r_wrist_flexion");
        HandCollision.Model model = HandCollision.capture(agent);
        Map<String, Double> base = pose(0, 0);
        Map<String, Double> crossed = pose(15, -15);
        double clearance = model.recommendedClearance(base);
        HandCollision.Metrics before = model.measure(crossed);
        require(before.minimum() == Math.min(Math.min(Math.min(before.hands, before.forearms), Math.min(before.leftHandToRightForearm, before.rightHandToLeftForearm)), before.fingers), "finger-to-palm proxy is an independent baseline category");
        require(before.minimum() < clearance, "fixture has a palm or forearm proxy contact");
        HandCollision.Result first = model.correct(crossed, base, 1, null, clearance);
        require(first.cleared, "paired correction clears captured proxy geometry");
        require(first.after.minimum() >= clearance, "clearance is measured as capsule surface separation");
        HandCollision.Result smoothed = model.correct(crossed, base, 1, first.pose, clearance);
        require(smoothed.cleared, "previous projection remains collision-free");
        HandCollision.Result bounded = model.correct(crossed, base, 1, first.pose, crossed, clearance);
        require(bounded.cleared, "rate-limited correction remains safe from a prior safe projection");
        requireCorrectionStep(bounded.pose, crossed, first.pose, crossed, 1.0,
                "unsafe raw frame releases retained correction gradually when geometry permits");
        require(correctionMagnitude(bounded.pose, crossed) < correctionMagnitude(first.pose, crossed) - 1e-9,
                "unsafe raw frame actually releases correction rather than merely retaining it");
        HandCollision.Result gentleReturn = model.correct(base, base, .5, first.pose, crossed, clearance);
        require(gentleReturn.cleared, "safe return retains a prior corrective offset when needed");
        requireCorrectionStep(gentleReturn.pose, base, first.pose, crossed, 1.0,
                "safe return releases correction gradually instead of a six-degree jump");
        Map<String, Double> sparsePrior = new LinkedHashMap<String, Double>(crossed);
        sparsePrior.put("l_shoulder_flexion", Double.valueOf(10));
        HandCollision.Result seededRateBound = model.correct(crossed, base, 1, sparsePrior, crossed, clearance);
        require(seededRateBound.cleared, "sparse seed remains able to clear the captured proxy geometry");
        requireRateBound(seededRateBound.pose, crossed, sparsePrior, crossed, "sparse seed preserves all inherited correction rate bounds");
        Map<String, Double> coordinated = pose(15, -15);
        coordinated.put("l_shoulder_flexion", Double.valueOf(15)); coordinated.put("r_shoulder_flexion", Double.valueOf(-15));
        coordinated.put("l_elbow_flexion", Double.valueOf(12)); coordinated.put("r_elbow_flexion", Double.valueOf(-12));
        coordinated.put("l_index1_flexion", Double.valueOf(23));
        coordinated.put("r_wrist_twisting", Double.valueOf(-11));
        Map<String, Double> originalCoordinated = new LinkedHashMap<String, Double>(coordinated);
        HandCollision.Result coordinatedResult = model.correct(coordinated, base, 1, null, clearance);
        require(coordinatedResult.cleared && coordinatedResult.after.minimum() >= clearance,
                "coordinated semantic arm target is projected outside the capsule clearance");
        require(coordinatedResult.maxCorrectionDegrees <= 16, "coordinated projection respects the global correction bound");
        require(coordinated.equals(originalCoordinated), "coordinated search leaves the input pose unchanged");
        require(coordinatedResult.pose.get("l_index1_flexion").equals(coordinated.get("l_index1_flexion"))
                && coordinatedResult.pose.get("r_wrist_twisting").equals(coordinated.get("r_wrist_twisting")),
                "coordinated search preserves wrist and finger targets");
        HandCollision.Result coordinatedNext = model.correct(coordinated, base, 1, coordinatedResult.pose, coordinated, clearance);
        require(coordinatedNext.cleared && model.pathMargin(coordinatedResult.pose, coordinatedNext.pose, clearance, clearance).safe(),
                "coordinated projection maintains safe sampled transitions");
        requireRateBound(coordinatedNext.pose, coordinated, coordinatedResult.pose, coordinated,
                "coordinated transition respects correction rate bounds");
        HandCollision.Result unsafeJump = model.correct(pose(30, -30), base, 1, base, base, clearance);
        require(!unsafeJump.cleared, "an unsafe first-frame correction fails closed instead of blending through contact");
        boolean rejectedInvalidHistory = false;
        try { model.correct(base, base, 1, pose(25, 0), base, clearance); }
        catch (IllegalArgumentException expected) { rejectedInvalidHistory = true; }
        require(rejectedInvalidHistory, "an invalid prior correction cannot escape the global bounds through rate limiting");
        require(model.maxWorldResidual(knownZero(), base) < 1e-10, "pure FK agrees with hand-calculated zero-pose coordinates");
        Map<String, Double> leftTurn = pose(90, 0);
        require(model.maxWorldResidual(knownLeftTurn(), leftTurn) < 1e-10, "pure FK applies the known 90-degree shoulder rotation");
        HandCollision.Metrics baseMetrics = model.measure(base);
        HandCollision.Boundaries contactTable = HandCollision.Boundaries.seated(-10, 10, -10, 10, -.18, 0, 0, 0);
        HandCollision.Model ordinaryContactTable = model.withBoundaries(contactTable);
        require(ordinaryContactTable.measure(base).tabletop < -.007,
                "ordinary table padding keeps calibrated palms visibly above the physical surface");
        HandCollision.Model contactRest = ordinaryContactTable.withTableContactRest(base, .03);
        require(Math.abs(contactRest.measure(base).tabletop) < 1e-9,
                "contact rest removes only the extra table padding at the calibrated palm surface");
        HandCollision.Model penetratingContactRest = model.withBoundaries(
                HandCollision.Boundaries.seated(-10, 10, -10, 10, -.16, 0, 0, 0)).withTableContactRest(base, .03);
        require(penetratingContactRest.measure(base).tabletop < -.019,
                "contact rest still rejects physical penetration below the table surface");
        Map<String, Double> translatedPalm = new LinkedHashMap<String, Double>(base);
        translatedPalm.put("l_shoulder_abduct", Double.valueOf(20));
        require(contactRest.measure(translatedPalm).tabletop < -.007,
                "translated palm leaving the envelope regains ordinary table padding");
        Map<String, Double> rotatedPalm = new LinkedHashMap<String, Double>(base);
        rotatedPalm.put("l_wrist_flexion", Double.valueOf(30));
        require(contactRest.measure(rotatedPalm).tabletop < -.007,
                "rotated palm leaving the envelope regains ordinary table padding");
        HandCollision.Model forearmGuard = model.withBoundaries(
                HandCollision.Boundaries.seated(-1.05, -.95, 1.2, 1.8, .10, 0, 0, 0));
        HandCollision.Model forearmContactGuard = forearmGuard.withTableContactRest(base, .03);
        require(Math.abs(forearmGuard.measure(base).tabletop - forearmContactGuard.measure(base).tabletop) < 1e-10
                        && forearmContactGuard.measure(base).tabletop < 0,
                "forearm tabletop padding remains unchanged by hand contact allowance");
        HandCollision.Model copiedContactRest = model.withTableContactRest(base, .03).withFixedPose(base).withBoundaries(contactTable);
        require(Math.abs(copiedContactRest.measure(new LinkedHashMap<String, Double>()).tabletop) < 1e-9,
                "fixed-pose and boundary builders preserve contact calibration");
        Map<String, Double> mutableContactRest = new LinkedHashMap<String, Double>(base);
        HandCollision.Model copiedInputContactRest = ordinaryContactTable.withTableContactRest(mutableContactRest, .03);
        mutableContactRest.put("l_shoulder_abduct", Double.valueOf(20));
        require(Math.abs(copiedInputContactRest.measure(base).tabletop) < 1e-9,
                "contact calibration deep-copies caller-owned rest values");
        require(Math.abs(roundTrip(contactRest).measure(base).tabletop) < 1e-9,
                "serialized contact calibration preserves physical tabletop contact");
        boolean rejectedContactRest = false;
        try { contactRest.withTableContactRest(base, 0); } catch (IllegalArgumentException expected) { rejectedContactRest = true; }
        require(rejectedContactRest, "zero tabletop contact release distance is rejected");
        rejectedContactRest = false;
        try { contactRest.withTableContactRest(base, Double.NaN); } catch (IllegalArgumentException expected) { rejectedContactRest = true; }
        require(rejectedContactRest, "non-finite tabletop contact release distance is rejected");
        Map<String, Double> nonFiniteContactRest = new LinkedHashMap<String, Double>(base);
        nonFiniteContactRest.put("l_wrist_flexion", Double.valueOf(Double.NaN));
        rejectedContactRest = false;
        try { contactRest.withTableContactRest(nonFiniteContactRest, .03); } catch (IllegalArgumentException expected) { rejectedContactRest = true; }
        require(rejectedContactRest, "non-finite tabletop contact rest is rejected");
        HandCollision.Model boundedModel = model.withBoundaries(HandCollision.Boundaries.seated(-3, 3, 1.5, 3, .1, 0, 0, 0));
        HandCollision.Metrics boundaryMetrics = boundedModel.measure(base);
        require(boundaryMetrics.minimum() == baseMetrics.minimum(), "body/table guards do not change the calibrated clasp baseline category");
        require(Double.isFinite(boundaryMetrics.handsToTorso) && Double.isFinite(boundaryMetrics.handsToUpperArms), "captured torso and upper-arm proxies are measured independently");
        require(Double.isFinite(boundaryMetrics.upperArmsToTorso), "upper-arm torso proxy is measured independently");
        require(boundaryMetrics.tabletop < 0, "finite tabletop proxy detects a hand below its declared surface");
        require(!boundedModel.correct(base, base, 1, null, clearance).cleared, "tabletop collision is a hard safety guard");
        require(!boundedModel.pathMarginBoundary(base, base).safe(), "swept boundary path reports the same tabletop violation");
        require(roundTrip(boundedModel).measure(base).tabletop < 0, "boundary-enabled immutable snapshot serializes without native handles");
        HandCollision.Model bodyOnlyModel = model.withBoundaries(HandCollision.Boundaries.seated(-3, 3, 1.5, 3, -100, 0, 0, 0));
        require(bodyOnlyModel.measure(base).handsToUpperArms >= 0, "normal elbow adjacency is not treated as a hand-to-own-upper-arm collision");
        require(bodyOnlyModel.measure(pose(90, 0)).handsToUpperArms < 0, "a folded hand touching an upper arm remains a body collision");
        HandCollision.Model bodyContactModel = bodyOnlyModel.withTableContactRest(base, .03);
        require(Math.abs(bodyContactModel.measure(pose(90, 0)).handsToUpperArms
                        - bodyOnlyModel.measure(pose(90, 0)).handsToUpperArms) < 1e-10,
                "hand contact allowance does not weaken body collision guards");
        HandCollision.Model palmOnlyTable = model.withBoundaries(HandCollision.Boundaries.seated(-1.01, -0.99, 2.08, 2.12, -.05, 0, 0, 0));
        require(palmOnlyTable.measure(base).tabletop < 0, "palm-radius middle capsule catches tabletop contact beyond narrow finger rays");
        HandCollision.Model upperArmTable = model.withBoundaries(HandCollision.Boundaries.seated(-1.05, -.95, .2, .8, -.05, 0, 0, 0));
        require(upperArmTable.measure(base).upperArmsToTable < 0, "upper-arm capsule participates in tabletop safety");
        HandCollision.Result dualGuard = model.correct(base, base, 0, base, base,
                baseMetrics.minimum() - .001, baseMetrics.minimum() - .001,
                baseMetrics.fingerPalm + .001, baseMetrics.fingerPalm + .001);
        require(!dualGuard.cleared, "finger-to-opposite-palm guard rejects an otherwise passing old category");
        HandCollision.Result exactRest = model.correct(base, base, 0, base, base,
                clearance, clearance, clearance, clearance);
        require(exactRest.cleared && exactRest.pose.equals(base),
                "the final return frame emits the exact safe rest with the normal air gap");
        Map<String, Double> terminalOneDegree = new LinkedHashMap<String, Double>(base);
        terminalOneDegree.put("l_shoulder_flexion", Double.valueOf(1));
        HandCollision.Result smoothTerminalRest = model.correct(base, base, 0, terminalOneDegree, base,
                clearance, clearance, clearance, clearance);
        require(smoothTerminalRest.cleared,
                "the final exact rest accepts only the last one-degree correction release");
        Map<String, Double> lingeringCorrection = new LinkedHashMap<String, Double>(base);
        lingeringCorrection.put("l_shoulder_flexion", Double.valueOf(1.5));
        HandCollision.Result unfinishedReturn = model.correct(base, base, 0, lingeringCorrection, base,
                clearance, clearance, clearance, clearance);
        require(!unfinishedReturn.cleared,
                "exact-rest endpoint fails closed if a previous correction cannot decay within the frame rate bound");
        HandCollision.Result directSettle = model.settleAtBase(base, lingeringCorrection, base,
                clearance, clearance, clearance, clearance);
        require(directSettle.cleared && correctionMagnitude(directSettle.pose, base) <= .500001,
                "direct settling releases an unresolved terminal correction by at most one degree without a new search");
        HandCollision.Result settledTerminal = model.correct(base, base, 0, directSettle.pose, base,
                clearance, clearance, clearance, clearance);
        require(settledTerminal.cleared && settledTerminal.pose.equals(base),
                "one bounded direct-settling frame permits the subsequent exact rest endpoint");
        Map<String, Double> twoTailCorrection = new LinkedHashMap<String, Double>(base);
        twoTailCorrection.put("l_shoulder_flexion", Double.valueOf(2.5));
        HandCollision.Result firstTail = model.settleAtBase(base, twoTailCorrection, base,
                clearance, clearance, clearance, clearance);
        require(firstTail.cleared && Math.abs(firstTail.pose.get("l_shoulder_flexion").doubleValue() - 1.5) < 1e-9,
                "a higher residual releases exactly one degree during the first direct-settling frame");
        HandCollision.Result secondTail = model.settleAtBase(base, firstTail.pose, base,
                clearance, clearance, clearance, clearance);
        require(secondTail.cleared && Math.abs(secondTail.pose.get("l_shoulder_flexion").doubleValue() - .5) < 1e-9,
                "each required direct-settling frame retains the one-degree release cap");
        HandCollision.Result twoTailTerminal = model.correct(base, base, 0, secondTail.pose, base,
                clearance, clearance, clearance, clearance);
        require(twoTailTerminal.cleared && twoTailTerminal.pose.equals(base),
                "the exact endpoint follows only after every bounded settling release is complete");
        require(roundTrip(model).measure(base).minimum() == model.measure(base).minimum(), "numeric snapshot serializes without native handles");
        HandCollision.PathMargin margin = model.pathMargin(first.pose, bounded.pose, clearance, clearance);
        require(margin.safe(), "reported intermediate path margin is safe");
        require(model.debugSnapshot().contains("LeftHand"), "diagnostic snapshot contains primitive geometry only");
        System.out.println("PASS hand_collision_snapshot_fk_capsules_bounded_correction");
    }

    private static Map<String, Double> pose(double left, double right) {
        Map<String, Double> result = new LinkedHashMap<String, Double>();
        result.put("l_shoulder_abduct", Double.valueOf(left)); result.put("r_shoulder_abduct", Double.valueOf(right));
        result.put("l_shoulder_flexion", Double.valueOf(0)); result.put("r_shoulder_flexion", Double.valueOf(0));
        result.put("l_elbow_flexion", Double.valueOf(0)); result.put("r_elbow_flexion", Double.valueOf(0));
        return result;
    }
    private static void requireRateBound(Map<String, Double> projected, Map<String, Double> candidate, Map<String, Double> previousProjected, Map<String, Double> previousCandidate, String message) {
        for (String joint : new String[] {"l_shoulder_abduct", "r_shoulder_abduct", "l_shoulder_flexion", "r_shoulder_flexion", "l_elbow_flexion", "r_elbow_flexion"}) {
            double delta = (projected.get(joint).doubleValue() - candidate.get(joint).doubleValue()) - (previousProjected.get(joint).doubleValue() - previousCandidate.get(joint).doubleValue());
            require(Math.abs(delta) <= 6.0 + 1e-9, message + ": " + joint + " delta=" + delta);
        }
    }
    private static void requireCorrectionStep(Map<String, Double> projected, Map<String, Double> candidate,
            Map<String, Double> previousProjected, Map<String, Double> previousCandidate, double maximum, String message) {
        for (String joint : new String[] {"l_shoulder_abduct", "r_shoulder_abduct", "l_shoulder_flexion", "r_shoulder_flexion", "l_elbow_flexion", "r_elbow_flexion"}) {
            double prior = previousProjected.get(joint).doubleValue() - previousCandidate.get(joint).doubleValue();
            double current = projected.get(joint).doubleValue() - candidate.get(joint).doubleValue();
            require(Math.abs(current - prior) <= maximum + 1e-9, message + ": " + joint + " delta=" + (current - prior));
        }
    }
    private static double correctionMagnitude(Map<String, Double> projected, Map<String, Double> candidate) {
        double total = 0;
        for (String joint : new String[] {"l_shoulder_abduct", "r_shoulder_abduct", "l_shoulder_flexion", "r_shoulder_flexion", "l_elbow_flexion", "r_elbow_flexion"})
            total += Math.abs(projected.get(joint).doubleValue() - candidate.get(joint).doubleValue());
        return total;
    }
    private static Map<String, double[]> knownZero() { Map<String, double[]> result = new LinkedHashMap<String, double[]>(); result.put("LeftArm", point(-1,0,0)); result.put("LeftForeArm", point(-1,0,1)); result.put("LeftHand", point(-1,0,2)); result.put("RightArm", point(1,0,0)); result.put("RightForeArm", point(1,0,1)); result.put("RightHand", point(1,0,2)); return result; }
    private static Map<String, double[]> knownLeftTurn() { Map<String, double[]> result = knownZero(); result.put("LeftForeArm", point(0,0,0)); result.put("LeftHand", point(1,0,0)); return result; }
    private static double[] point(double x, double y, double z) { return new double[] {x, y, z}; }
    private static HandCollision.Model roundTrip(HandCollision.Model model) {
        try { ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ObjectOutputStream output = new ObjectOutputStream(bytes); output.writeObject(model); output.close(); ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray())); return (HandCollision.Model)input.readObject(); }
        catch (Exception failure) { throw new AssertionError("snapshot serialization", failure); }
    }
    private static void add(FakeAgent agent, FakeBone bone, String type) { agent.bapMappers.add(new ThreeDOF(bone, type)); }
    private static FakeBone bone(String name, FakeBone parent, double x, double y, double z) { return new FakeBone(name, parent, new Vec(x, y, z)); }
    private static FakeBone[] fingers(FakeBone hand, String side) {
        List<FakeBone> result = new ArrayList<FakeBone>();
        double[] offsets = new double[] {-.24, -.12, 0, .12, .24}; String[] names = new String[] {"Thumb", "Index", "Middle", "Ring", "Pinky"};
        for (int finger = 0; finger < names.length; finger++) { FakeBone parent = hand; for (int joint = 1; joint <= 4; joint++) { parent = bone(side + "Hand" + names[finger] + joint, parent, offsets[finger] / (joint == 1 ? 1 : 4), 0, .12); result.add(parent); } }
        return result.toArray(new FakeBone[result.size()]);
    }
    private static FakeBone[] all(FakeBone[] first, FakeBone[] second, FakeBone[] third) { FakeBone[] result = new FakeBone[first.length + second.length + third.length]; System.arraycopy(first, 0, result, 0, first.length); System.arraycopy(second, 0, result, first.length, second.length); System.arraycopy(third, 0, result, first.length + second.length, third.length); return result; }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }

    public static final class FakeAgent {
        public final List<Object> bapMappers = new ArrayList<Object>(); private final FakeEntity entity;
        FakeAgent(FakeBone[] bones) { entity = new FakeEntity(bones); } public FakeEntity getMainEntityWithSkeleton() { return entity; }
    }
    public static final class FakeEntity { private final FakeSkeleton skeleton; FakeEntity(FakeBone[] bones){skeleton=new FakeSkeleton(bones);} public FakeSkeleton getSkeleton(){return skeleton;} }
    public static final class FakeSkeleton { private final FakeBone[] bones; FakeSkeleton(FakeBone[] bones){this.bones=bones;} public int getNumBones(){return bones.length;} public FakeBone getBone(int index){return bones[index];} }
    public static final class ThreeDOF {
        public final FakeBone bone; public final Quaternion preRotation = new Quaternion(), postRotation = new Quaternion();
        public final String type1; public final Vec axis1 = new Vec(0, 1, 0); public final double lastVal1 = 0;
        public final String type2 = "unused_2"; public final Vec axis2 = new Vec(1, 0, 0); public final double lastVal2 = 0;
        public final String type3 = "unused_3"; public final Vec axis3 = new Vec(0, 0, 1); public final double lastVal3 = 0;
        ThreeDOF(FakeBone bone, String type) { this.bone = bone; type1 = type; }
    }
    public static final class FakeBone {
        private static long nextPointer = 1; final String name; final FakeBone parent; final Vec position; final Quaternion orientation = new Quaternion(); final long pointer = nextPointer++;
        FakeBone(String name, FakeBone parent, Vec position) { this.name=name; this.parent=parent; this.position=position; }
        public String getName() { return name; } public FakeBone getParent() { return parent; }
        public Vec getPosition() { return position; } public Vec _getDerivedPosition() { return position; } public Quaternion getOrientation() { return orientation; } public long getNativePointer(){return pointer;}
    }
    public static final class Vec { final double x,y,z; Vec(double x,double y,double z){this.x=x;this.y=y;this.z=z;} public double x(){return x;} public double y(){return y;} public double z(){return z;} }
    public static final class Quaternion { public double x(){return 0;} public double y(){return 0;} public double z(){return 0;} public double w(){return 1;} }
}
