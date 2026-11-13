import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

/**
 * Immutable, renderer-independent arm kinematics captured from an Autodesk
 * agent.  Capture is deliberately reflective: this GMIT extension must not
 * retain native Ogre objects or mutate a live skeleton while evaluating a
 * future BAP candidate.
 */
public final class HandCollision {
    private static final double EPSILON = 1e-9;
    private static final double MAX_CORRECTION_STEP = 6.0;
    private static final double RELAX_CORRECTION_STEP = 1.0;
    private static final int CONTINUITY_REFINEMENT_STEPS = 12;
    private HandCollision() { }

    /** Capture only primitive snapshot values while the caller is on Ogre's thread. */
    public static Model capture(Object nativeAutodeskAgent) {
        if (nativeAutodeskAgent == null) throw new IllegalArgumentException("Autodesk agent is required");
        try {
            List<?> rawMappers = asList(field(nativeAutodeskAgent, "bapMappers"));
            Map<String, Bone> bones = new LinkedHashMap<String, Bone>();
            Map<String, Mapper> mappers = new LinkedHashMap<String, Mapper>();
            Object entity = call(nativeAutodeskAgent, "getMainEntityWithSkeleton");
            Object skeleton = call(entity, "getSkeleton");
            int count = ((Number)call(skeleton, "getNumBones")).intValue();
            Map<Long, String> namesByPointer = new LinkedHashMap<Long, String>();
            Map<String, Object> rawBones = new LinkedHashMap<String, Object>();
            for (int index = 0; index < count; index++) {
                Object rawBone = call(skeleton, "getBone", Integer.TYPE, Integer.valueOf(index));
                String name = String.valueOf(call(rawBone, "getName"));
                namesByPointer.put(Long.valueOf(nativePointer(rawBone)), name);
                rawBones.put(name, rawBone);
            }
            for (Map.Entry<String, Object> entry : rawBones.entrySet()) {
                Object parent = call(entry.getValue(), "getParent");
                String parentName = parent == null ? null : namesByPointer.get(Long.valueOf(nativePointer(parent)));
                bones.put(entry.getKey(), new Bone(entry.getKey(), parentName, vector(call(entry.getValue(), "getPosition")), quaternion(call(entry.getValue(), "getOrientation")), vector(call(entry.getValue(), "_getDerivedPosition"))));
            }
            for (Object raw : rawMappers) {
                Object rawBone = field(raw, "bone");
                Bone bone = bones.get(String.valueOf(call(rawBone, "getName")));
                if (bone == null) continue;
                Mapper mapper = Mapper.capture(raw, bone.name);
                if (mapper != null) mappers.put(bone.name, mapper);
            }
            requireArm(bones, "LeftArm"); requireArm(bones, "LeftForeArm"); requireArm(bones, "LeftHand");
            requireArm(bones, "RightArm"); requireArm(bones, "RightForeArm"); requireArm(bones, "RightHand");
            return new Model(bones, mappers);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot capture a read-only Autodesk arm snapshot", failure);
        }
    }

    /** A pure-Java snapshot.  All distances use the exact avatar's Ogre units. */
    public static final class Model implements Serializable {
        private static final long serialVersionUID = 1L;
        private final Map<String, Bone> bones;
        private final Map<String, Mapper> mappers;
        /* Null is intentional for version-1 local diagnostic snapshots. */
        private final Map<String, Double> fixedPose;
        private final Boundaries boundaries;
        /* Optional calibrated rest that permits physical palm/table contact only. */
        private final TableContactRest tableContactRest;
        private final Map<String, Vec> tableContactLandmarks;

        Model(Map<String, Bone> bones, Map<String, Mapper> mappers) {
            this(bones, mappers, Collections.<String, Double>emptyMap(), Boundaries.none(), null);
        }
        private Model(Map<String, Bone> bones, Map<String, Mapper> mappers, Map<String, Double> fixedPose, Boundaries boundaries, TableContactRest tableContactRest) {
            this.bones = Collections.unmodifiableMap(new LinkedHashMap<String, Bone>(bones));
            this.mappers = Collections.unmodifiableMap(new LinkedHashMap<String, Mapper>(mappers));
            this.fixedPose = fixedPose == null ? Collections.<String, Double>emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<String, Double>(fixedPose));
            this.boundaries = boundaries == null ? Boundaries.none() : boundaries;
            this.tableContactRest = tableContactRest;
            requireArm(bones, "LeftHandIndex1"); requireArm(bones, "LeftHandMiddle1"); requireArm(bones, "LeftHandPinky1");
            requireArm(bones, "RightHandIndex1"); requireArm(bones, "RightHandMiddle1"); requireArm(bones, "RightHandPinky1");
            for (String side : new String[] {"Left", "Right"}) for (String finger : new String[] {"Thumb", "Index", "Middle", "Ring", "Pinky"}) for (int joint = 1; joint <= 4; joint++) requireArm(bones, side + "Hand" + finger + joint);
            this.tableContactLandmarks = tableContactRest == null ? Collections.<String, Vec>emptyMap()
                    : contactLandmarks(tableContactRest.pose);
        }

        /** Captures a fixed non-arm context; explicit candidate values still win. */
        public Model withFixedPose(Map<String, Double> context) {
            if (context == null) throw new IllegalArgumentException("Fixed pose is required");
            return new Model(bones, mappers, context, boundaries, tableContactRest);
        }

        /** Adds immutable seated-workspace boundaries without mutating this snapshot. */
        public Model withBoundaries(Boundaries next) {
            if (next == null) throw new IllegalArgumentException("Boundaries are required");
            if (next.bodyEnabled) requireTorso(bones);
            return new Model(bones, mappers, fixedPose, next, tableContactRest);
        }

        /** Opt in to physical tabletop contact at one calibrated rest pose. */
        public Model withTableContactRest(Map<String, Double> calibratedRest, double releaseDistance) {
            return new Model(bones, mappers, fixedPose, boundaries,
                    new TableContactRest(calibratedRest, releaseDistance));
        }

        /** Use the calibrated round table's finite top and four leg boxes for rest motion only. */
        Model withRoundTableRestBoundary() {
            if (boundaries == null || boundaries.table == null)
                throw new IllegalStateException("A seated table boundary is required");
            return new Model(bones, mappers, fixedPose, boundaries.withRoundTable(), tableContactRest);
        }

        /** Evaluates a degree-valued BAP pose without accessing native Ogre state. */
        public Metrics measure(Map<String, Double> degrees) {
            degrees = resolvedPose(degrees);
            Map<String, Transform> world = new LinkedHashMap<String, Transform>();
            Vec leftArm = position("LeftArm", degrees, world);
            Vec leftForearm = position("LeftForeArm", degrees, world);
            Vec leftHand = position("LeftHand", degrees, world);
            Vec leftIndex = position("LeftHandIndex1", degrees, world);
            Vec leftMiddle = position("LeftHandMiddle1", degrees, world);
            Vec leftPinky = position("LeftHandPinky1", degrees, world);
            Vec rightArm = position("RightArm", degrees, world);
            Vec rightForearm = position("RightForeArm", degrees, world);
            Vec rightHand = position("RightHand", degrees, world);
            Vec rightIndex = position("RightHandIndex1", degrees, world);
            Vec rightMiddle = position("RightHandMiddle1", degrees, world);
            Vec rightPinky = position("RightHandPinky1", degrees, world);
            double leftPalm = palmRadius(leftIndex, leftMiddle, leftPinky);
            double rightPalm = palmRadius(rightIndex, rightMiddle, rightPinky);
            double leftForearmRadius = leftPalm * .5, rightForearmRadius = rightPalm * .5;
            double hands = segmentDistance(leftHand, leftMiddle, rightHand, rightMiddle) - leftPalm - rightPalm;
            double forearms = segmentDistance(leftForearm, leftHand, rightForearm, rightHand) - leftForearmRadius - rightForearmRadius;
            double leftHandToRightForearm = segmentDistance(leftHand, leftMiddle, rightForearm, rightHand) - leftPalm - rightForearmRadius;
            double rightHandToLeftForearm = segmentDistance(rightHand, rightMiddle, leftForearm, leftHand) - rightPalm - leftForearmRadius;
            double fingers = fingerClearance("Left", "Right", pose(degrees, world), leftPalm * .22, rightPalm * .22, leftForearm, leftHand, rightForearm, rightHand, leftForearmRadius, rightForearmRadius);
            double fingerPalm = fingerPalmClearance("Left", "Right", pose(degrees, world), leftPalm * .22, rightPalm * .22);
            BoundaryMetrics boundary = boundaryMetrics(degrees, world, leftPalm, rightPalm, leftForearmRadius, rightForearmRadius,
                    leftArm, leftForearm, leftHand, rightArm, rightForearm, rightHand);
            return new Metrics(hands, forearms, leftHandToRightForearm, rightHandToLeftForearm, fingers, fingerPalm,
                    boundary.handsToTorso, boundary.handsToUpperArms, boundary.tabletop, boundary.upperArmsToTorso, boundary.upperArmsToTable,
                    leftArm, leftForearm, leftHand, rightArm, rightForearm, rightHand);
        }

        private Map<String, Double> resolvedPose(Map<String, Double> degrees) {
            if (degrees == null) throw new IllegalArgumentException("BAP pose is required");
            if (fixedPose == null || fixedPose.isEmpty()) return degrees;
            Map<String, Double> resolved = new LinkedHashMap<String, Double>(fixedPose);
            resolved.putAll(degrees);
            return resolved;
        }

        private BoundaryMetrics boundaryMetrics(Map<String, Double> degrees, Map<String, Transform> world,
                double leftPalm, double rightPalm, double leftForearmRadius, double rightForearmRadius,
                Vec leftArm, Vec leftForearm, Vec leftHand, Vec rightArm, Vec rightForearm, Vec rightHand) {
            Boundaries active = boundaries == null ? Boundaries.none() : boundaries;
            if (!active.enabled()) return BoundaryMetrics.NONE;
            Map<String, Vec> points = pose(degrees, world);
            List<BoundarySegment> moving = new ArrayList<BoundarySegment>();
            BoundarySegment leftForearmSegment = new BoundarySegment(new Segment(leftForearm, leftHand), leftForearmRadius, "Left", false);
            BoundarySegment rightForearmSegment = new BoundarySegment(new Segment(rightForearm, rightHand), rightForearmRadius, "Right", false);
            moving.add(leftForearmSegment); moving.add(rightForearmSegment);
            // The finger rays do not cover the palm's solid centre.  Retain a
            // dedicated middle-ray capsule at the captured palm radius.
            moving.add(new BoundarySegment(new Segment(leftHand, points.get("LeftHandMiddle1")), leftPalm, "Left", true));
            moving.add(new BoundarySegment(new Segment(rightHand, points.get("RightHandMiddle1")), rightPalm, "Right", true));
            for (Segment segment : fingerSegments("Left", points)) moving.add(new BoundarySegment(segment, leftPalm * .22, "Left", true));
            for (Segment segment : fingerSegments("Right", points)) moving.add(new BoundarySegment(segment, rightPalm * .22, "Right", true));
            double torso = Double.POSITIVE_INFINITY, upper = Double.POSITIVE_INFINITY, table = Double.POSITIVE_INFINITY;
            double upperTorso = Double.POSITIVE_INFINITY, upperTable = Double.POSITIVE_INFINITY;
            if (active.bodyEnabled) {
                double shoulderWidth = leftArm.distance(rightArm);
                double torsoRadius = shoulderWidth * active.torsoRadiusScale + active.torsoMargin;
                double upperRadius = Math.max(Math.max(leftForearmRadius, rightForearmRadius), shoulderWidth * active.upperArmRadiusScale) + active.upperArmMargin;
                for (BoundarySegment movingSegment : moving) {
                    for (Segment torsoSegment : torsoSegments(degrees, world)) torso = Math.min(torso,
                            segmentDistance(movingSegment.segment.start, movingSegment.segment.end, torsoSegment.start, torsoSegment.end) - movingSegment.radius - torsoRadius);
                    // A forearm is adjacent to its own upper arm at the elbow;
                    // only the opposite upper arm is a collision pair for it.
                    if (movingSegment != leftForearmSegment) upper = Math.min(upper, segmentDistance(movingSegment.segment.start, movingSegment.segment.end, leftArm, leftForearm) - movingSegment.radius - upperRadius);
                    if (movingSegment != rightForearmSegment) upper = Math.min(upper, segmentDistance(movingSegment.segment.start, movingSegment.segment.end, rightArm, rightForearm) - movingSegment.radius - upperRadius);
                }
                if (active.table != null && active.table.roundRest) {
                    for (String side : new String[] {"Left", "Right"}) {
                        Vec hip = position(side + "UpLeg", degrees, world);
                        Vec knee = position(side + "Leg", degrees, world);
                        Vec axis = knee.sub(hip);
                        // Taper the thigh proxy toward the knee. The two
                        // capsules cover the cloth where a hand can settle.
                        torso = Math.min(torso, thighClearance(moving, hip.add(axis.scale(.10)),
                                hip.add(axis.scale(.58)), .075));
                        torso = Math.min(torso, thighClearance(moving, hip.add(axis.scale(.58)),
                                hip.add(axis.scale(.92)), .060));
                    }
                }
                // The shoulder attachment is adjacent to the torso by design;
                // trim only that proximal eighth before checking the remaining
                // upper-arm volume against the torso capsule chain.
                Segment leftUpper = trimmedUpperArm(leftArm, leftForearm);
                Segment rightUpper = trimmedUpperArm(rightArm, rightForearm);
                for (Segment torsoSegment : torsoSegments(degrees, world)) {
                    upperTorso = Math.min(upperTorso, segmentDistance(leftUpper.start, leftUpper.end, torsoSegment.start, torsoSegment.end) - upperRadius - torsoRadius);
                    upperTorso = Math.min(upperTorso, segmentDistance(rightUpper.start, rightUpper.end, torsoSegment.start, torsoSegment.end) - upperRadius - torsoRadius);
                }
            }
            if (active.table != null) {
                double shoulderWidth = leftArm.distance(rightArm);
                double upperRadius = Math.max(Math.max(leftForearmRadius, rightForearmRadius), shoulderWidth * active.upperArmRadiusScale);
                double leftTableMargin = tableMargin(active, points, "Left");
                double rightTableMargin = tableMargin(active, points, "Right");
                for (BoundarySegment movingSegment : moving)
                    table = Math.min(table, active.table.clearance(active.toWorld(movingSegment.segment.start), active.toWorld(movingSegment.segment.end),
                            movingSegment.radius + (movingSegment.handSurface
                                    ? (movingSegment.side.equals("Left") ? leftTableMargin : rightTableMargin)
                                    : active.tableMargin)));
                upperTable = Math.min(upperTable, active.table.clearance(active.toWorld(leftArm), active.toWorld(leftForearm), upperRadius + active.tableMargin));
                upperTable = Math.min(upperTable, active.table.clearance(active.toWorld(rightArm), active.toWorld(rightForearm), upperRadius + active.tableMargin));
            }
            return new BoundaryMetrics(torso, upper, table, upperTorso, upperTable);
        }

        private static double thighClearance(List<BoundarySegment> arm, Vec start, Vec end, double radius) {
            double clearance = Double.POSITIVE_INFINITY;
            for (BoundarySegment segment : arm) {
                clearance = Math.min(clearance, segmentDistance(segment.segment.start, segment.segment.end,
                        start, end) - segment.radius - radius);
            }
            return clearance;
        }

        private double tableMargin(Boundaries active, Map<String, Vec> points, String side) {
            if (tableContactRest == null) return active.tableMargin;
            double displacement = 0;
            for (String name : contactPoints(side))
                displacement = Math.max(displacement, points.get(name).distance(tableContactLandmarks.get(name)));
            double t = clamp(displacement / tableContactRest.releaseDistance, 0, 1);
            double smooth = t * t * (3 - 2 * t);
            return active.tableMargin * smooth;
        }

        private static String[] contactPoints(String side) {
            return new String[] {side + "Hand", side + "HandMiddle1", side + "HandIndex1", side + "HandPinky1"};
        }

        private Map<String, Vec> contactLandmarks(Map<String, Double> rest) {
            Map<String, Transform> restWorld = new LinkedHashMap<String, Transform>();
            Map<String, Double> restPose = resolvedPose(rest);
            Map<String, Vec> result = new LinkedHashMap<String, Vec>();
            for (String side : new String[] {"Left", "Right"}) for (String name : contactPoints(side))
                result.put(name, position(name, restPose, restWorld));
            return Collections.unmodifiableMap(result);
        }

        private static Segment trimmedUpperArm(Vec shoulder, Vec elbow) {
            return new Segment(shoulder.add(elbow.sub(shoulder).scale(.125)), elbow);
        }

        private List<Segment> torsoSegments(Map<String, Double> degrees, Map<String, Transform> world) {
            String[] chain = new String[] {"Spine", "Spine1", "Spine2", "Spine3", "Spine4", "Neck"};
            List<Segment> result = new ArrayList<Segment>();
            for (int index = 1; index < chain.length; index++) result.add(new Segment(position(chain[index - 1], degrees, world), position(chain[index], degrees, world)));
            return result;
        }

        private Map<String, Vec> pose(Map<String, Double> degrees, Map<String, Transform> world) {
            Map<String, Vec> points = new LinkedHashMap<String, Vec>();
            for (String side : new String[] {"Left", "Right"}) {
                points.put(side + "Hand", position(side + "Hand", degrees, world));
                for (String finger : new String[] {"Thumb", "Index", "Middle", "Ring", "Pinky"}) for (int joint = 1; joint <= 4; joint++) points.put(side + "Hand" + finger + joint, position(side + "Hand" + finger + joint, degrees, world));
            }
            return points;
        }

        private double fingerClearance(String left, String right, Map<String, Vec> points, double leftRadius, double rightRadius, Vec leftForearm, Vec leftHand, Vec rightForearm, Vec rightHand, double leftForearmRadius, double rightForearmRadius) {
            List<Segment> leftSegments = fingerSegments(left, points), rightSegments = fingerSegments(right, points); double minimum = Double.POSITIVE_INFINITY;
            for (Segment a : leftSegments) for (Segment b : rightSegments) minimum = Math.min(minimum, segmentDistance(a.start, a.end, b.start, b.end) - leftRadius - rightRadius);
            for (Segment a : leftSegments) minimum = Math.min(minimum, segmentDistance(a.start, a.end, rightForearm, rightHand) - leftRadius - rightForearmRadius);
            for (Segment b : rightSegments) minimum = Math.min(minimum, segmentDistance(b.start, b.end, leftForearm, leftHand) - rightRadius - leftForearmRadius);
            return minimum;
        }
        private List<Segment> fingerSegments(String side, Map<String, Vec> points) {
            List<Segment> segments = new ArrayList<Segment>();
            for (String finger : new String[] {"Thumb", "Index", "Middle", "Ring", "Pinky"}) {
                Vec prior = points.get(side + "Hand");
                for (int joint = 1; joint <= 4; joint++) { Vec next = points.get(side + "Hand" + finger + joint); segments.add(new Segment(prior, next)); prior = next; }
            }
            return segments;
        }
        private double fingerPalmClearance(String left, String right, Map<String, Vec> points, double leftFingerRadius, double rightFingerRadius) {
            List<Segment> leftFingers = fingerSegments(left, points), rightFingers = fingerSegments(right, points);
            List<Segment> leftPalm = palmSegments(left, points), rightPalm = palmSegments(right, points); double minimum = Double.POSITIVE_INFINITY;
            for (Segment finger : leftFingers) for (Segment palm : rightPalm) minimum = Math.min(minimum, segmentDistance(finger.start, finger.end, palm.start, palm.end) - leftFingerRadius - rightFingerRadius);
            for (Segment finger : rightFingers) for (Segment palm : leftPalm) minimum = Math.min(minimum, segmentDistance(finger.start, finger.end, palm.start, palm.end) - rightFingerRadius - leftFingerRadius);
            return minimum;
        }
        private List<Segment> palmSegments(String side, Map<String, Vec> points) {
            List<Segment> result = new ArrayList<Segment>(); Vec hand = points.get(side + "Hand");
            for (String finger : new String[] {"Thumb", "Index", "Middle", "Ring", "Pinky"}) result.add(new Segment(hand, points.get(side + "Hand" + finger + "1")));
            return result;
        }

        /** Desired air gap derived from this avatar's captured palm span, in Ogre units. */
        public double recommendedClearance(Map<String, Double> degrees) {
            Map<String, Transform> world = new LinkedHashMap<String, Transform>();
            double left = palmRadius(position("LeftHandIndex1", degrees, world), position("LeftHandMiddle1", degrees, world), position("LeftHandPinky1", degrees, world));
            double right = palmRadius(position("RightHandIndex1", degrees, world), position("RightHandMiddle1", degrees, world), position("RightHandPinky1", degrees, world));
            return Math.min(left, right) * .15;
        }

        /** Maximum point residual against native derived positions supplied by the caller. */
        public double maxWorldResidual(Map<String, double[]> observed, Map<String, Double> degrees) {
            if (observed == null || observed.isEmpty()) throw new IllegalArgumentException("Observed native points are required");
            degrees = resolvedPose(degrees);
            Map<String, Transform> world = new LinkedHashMap<String, Transform>(); double maximum = 0; int checked = 0;
            for (Map.Entry<String, double[]> entry : observed.entrySet()) {
                if (!bones.containsKey(entry.getKey()) || entry.getValue() == null || entry.getValue().length != 3) throw new IllegalArgumentException("Invalid observed bone " + entry.getKey());
                Vec expected = position(entry.getKey(), degrees, world); double[] actual = entry.getValue();
                maximum = Math.max(maximum, expected.distance(new Vec(actual[0], actual[1], actual[2]))); checked++;
            }
            if (checked == 0) throw new IllegalArgumentException("No native points were checked"); return maximum;
        }

        /** Residual against native derived positions captured on the same Ogre thread. */
        public double nativeResidual(Map<String, Double> degrees) {
            Map<String, double[]> observed = new LinkedHashMap<String, double[]>();
            for (Bone bone : bones.values()) observed.put(bone.name, new double[] {bone.derived.x, bone.derived.y, bone.derived.z});
            return maxWorldResidual(observed, degrees);
        }

        public double nativeTolerance(Map<String, Double> degrees) {
            degrees = resolvedPose(degrees);
            Map<String, Transform> world = new LinkedHashMap<String, Transform>();
            double left = position("LeftForeArm", degrees, world).distance(position("LeftHand", degrees, world));
            double right = position("RightForeArm", degrees, world).distance(position("RightHand", degrees, world));
            return Math.max(1e-7, Math.min(left, right) * 1e-4);
        }

        /** Primitive-only deterministic capture for ignored local diagnostics. */
        public String debugSnapshot() {
            StringBuilder out = new StringBuilder("hand-collision-v1 bones=").append(bones.size()).append(" mappers=").append(mappers.size());
            for (Bone bone : bones.values()) out.append('\n').append(bone.name).append(" parent=").append(bone.parent).append(" pos=").append(bone.position.x).append(',').append(bone.position.y).append(',').append(bone.position.z);
            return out.toString();
        }

        /**
         * Returns an arm-only projection.  It never changes a caller's map,
         * non-arm BAP channels, or timing.  An unresolved result is explicit;
         * callers must not label it as collision-free.
         */
        public Result correct(Map<String, Double> candidate, Map<String, Double> base,
                double phaseWeight, Map<String, Double> previous, double clearance) {
            return correct(candidate, base, phaseWeight, previous, null, clearance);
        }

        /**
         * Projects only the collision correction at a bounded rate.  Supplying
         * the prior raw semantic pose keeps an abruptly changing MeaningMiner
         * gesture intact: only the six corrective BAP deltas are rate-limited.
         */
        public Result correct(Map<String, Double> candidate, Map<String, Double> base,
                double phaseWeight, Map<String, Double> previousProjected,
                Map<String, Double> previousCandidate, double clearance) {
            double previousClearance = previousProjected == null ? clearance : Math.min(clearance, measure(previousProjected).minimum());
            return correct(candidate, base, phaseWeight, previousProjected, previousCandidate, previousClearance, clearance);
        }

        /** Uses the previous phase's accepted transition threshold for path validation. */
        public Result correct(Map<String, Double> candidate, Map<String, Double> base,
                double phaseWeight, Map<String, Double> previousProjected,
                Map<String, Double> previousCandidate, double previousClearance, double clearance) {
            return correct(candidate, base, phaseWeight, previousProjected, previousCandidate, previousClearance, clearance, -Double.MAX_VALUE, -Double.MAX_VALUE);
        }

        /** Uses independent finger-to-opposite-palm thresholds without weakening the original collision baseline. */
        public Result correct(Map<String, Double> candidate, Map<String, Double> base,
                double phaseWeight, Map<String, Double> previousProjected,
                Map<String, Double> previousCandidate, double previousClearance, double clearance,
                double previousFingerPalmClearance, double fingerPalmClearance) {
            if (candidate == null || base == null) throw new IllegalArgumentException("Candidate and base are required");
            if (!finite(phaseWeight) || phaseWeight < 0 || phaseWeight > 1 || !finite(clearance) || !finite(previousFingerPalmClearance) || !finite(fingerPalmClearance))
                throw new IllegalArgumentException("Invalid collision phase or clearance");
            if (phaseWeight == 1 && (clearance < 0 || (fingerPalmClearance > -Double.MAX_VALUE / 2 && fingerPalmClearance < 0))) throw new IllegalArgumentException("Full semantic phase requires non-negative clearances");
            Map<String, Double> projected = new LinkedHashMap<String, Double>(candidate);
            Metrics before = measure(projected);
            if (phaseWeight == 0) {
                Metrics clasp = measure(base);
                // The terminal exact-rest frame may not hide a six-degree snap.
                // It is accepted only after the same gradual-release bound used
                // during return has brought every corrective offset to zero.
                Map<String, Double> released = relaxedCorrection(base, previousProjected, previousCandidate);
                boolean endpointSafe = clasp.minimum() >= clearance && clasp.fingerPalm >= fingerPalmClearance && clasp.boundaryMinimum() >= 0;
                boolean pathSafe = previousProjected == null || safePath(previousProjected, base, previousClearance, clearance, previousFingerPalmClearance, fingerPalmClearance);
                return new Result(new LinkedHashMap<String, Double>(base), before, clasp, samePose(candidate, base) && samePose(released, base) && endpointSafe && pathSafe, 0);
            }
            // Release as soon as geometry permits, even while the raw candidate
            // itself is still unsafe. Waiting for raw safety holds a large
            // correction until the final return frames and causes a terminal
            // discontinuity at a fixed 60-frame direct-return duration.
            if (previousProjected != null && previousCandidate != null) {
                Map<String, Double> retained = retainedCorrection(candidate, previousProjected, previousCandidate);
                Metrics retainedMetrics = measure(retained);
                boolean retainedSafe = retainedMetrics.minimum() >= clearance && retainedMetrics.fingerPalm >= fingerPalmClearance
                        && retainedMetrics.boundaryMinimum() >= 0 && safePath(previousProjected, retained, previousClearance, clearance, previousFingerPalmClearance, fingerPalmClearance);
                if (retainedSafe) {
                    Map<String, Double> relaxed = relaxedCorrection(candidate, previousProjected, previousCandidate);
                    Metrics relaxedMetrics = measure(relaxed);
                    if (relaxedMetrics.minimum() >= clearance && relaxedMetrics.fingerPalm >= fingerPalmClearance
                            && relaxedMetrics.boundaryMinimum() >= 0 && safePath(previousProjected, relaxed, previousClearance, clearance, previousFingerPalmClearance, fingerPalmClearance))
                        return new Result(relaxed, before, relaxedMetrics, true, maxCorrection(relaxed, candidate));
                    Map<String, Double> selective = selectiveRelaxation(candidate, retained, previousProjected, previousCandidate,
                            previousClearance, clearance, previousFingerPalmClearance, fingerPalmClearance);
                    if (selective != null) {
                        Metrics selectiveMetrics = measure(selective);
                        return new Result(selective, before, selectiveMetrics, true, maxCorrection(selective, candidate));
                    }
                    return new Result(retained, before, retainedMetrics, true, maxCorrection(retained, candidate));
                }
            }
            if (before.minimum() >= clearance && before.fingerPalm >= fingerPalmClearance && before.boundaryMinimum() >= 0) {
                // A raw return frame can already be safe while an earlier coarse
                // correction remains active. Preserve that safe offset first,
                // then release only one degree per frame when its swept path is
                // safe. This avoids alternating six-degree decay/re-entry.
                Map<String, Double> retained = retainedCorrection(candidate, previousProjected, previousCandidate);
                Metrics retainedMetrics = measure(retained);
                boolean retainedSafe = retainedMetrics.minimum() >= clearance && retainedMetrics.fingerPalm >= fingerPalmClearance
                        && retainedMetrics.boundaryMinimum() >= 0 && safePath(previousProjected, retained, previousClearance, clearance, previousFingerPalmClearance, fingerPalmClearance);
                if (retainedSafe) {
                    Map<String, Double> relaxed = relaxedCorrection(candidate, previousProjected, previousCandidate);
                    Metrics relaxedMetrics = measure(relaxed);
                    if (relaxedMetrics.minimum() >= clearance && relaxedMetrics.fingerPalm >= fingerPalmClearance
                            && relaxedMetrics.boundaryMinimum() >= 0 && safePath(previousProjected, relaxed, previousClearance, clearance, previousFingerPalmClearance, fingerPalmClearance))
                        return new Result(relaxed, before, relaxedMetrics, true, maxCorrection(relaxed, candidate));
                    return new Result(retained, before, retainedMetrics, true, maxCorrection(retained, candidate));
                }
            }

            // Asset mirroring and the animated finger path make symmetric arm
            // changes insufficient. Search six bounded corrective DOFs, but
            // seed them independently rather than evaluating a 3^6 cube on a
            // 25 fps callback path.
            projected = retainedCorrection(candidate, previousProjected, previousCandidate);
            Metrics after = measure(projected); PathMargins initialMargins = pathMargins(previousProjected, projected, previousClearance, clearance, previousFingerPalmClearance, fingerPalmClearance); double score = safetyScore(after, initialMargins.existing, initialMargins.fingerPalm, initialMargins.boundary, clearance, fingerPalmClearance);
            if (score >= -1e-8) return new Result(projected, before, after, true, maxCorrection(projected, candidate));
            // Coordinated changes can be missed by a local one-joint walk.
            // Probe single and paired axes, then retain the strongest
            // unsuccessful seeds for the bounded beam search too.
            String[] joints = correctionJoints();
            double[][] levels = new double[joints.length][];
            for (int i = 0; i < joints.length; i++) levels[i] = seedLevels(joints[i], candidate, previousProjected, previousCandidate);
            Map<String, Double> safeSeed = null;
            Metrics safeSeedMetrics = null;
            double safeSeedDistance = Double.POSITIVE_INFINITY, safeSeedRawDistance = Double.POSITIVE_INFINITY;
            List<Search> seedBeam = new ArrayList<Search>();
            for (Map<String, Double> trial : seedCandidates(candidate, joints, levels)) {
                // A sparse seed also has untouched axes. Their zero deltas
                // must obey rate bounds inherited from the previous frame.
                trial = boundedCorrection(trial, candidate, previousProjected, previousCandidate);
                Metrics measured = measure(trial);
                PathMargins margins = pathMargins(previousProjected, trial, previousClearance, clearance, previousFingerPalmClearance, fingerPalmClearance);
                double trialScore = safetyScore(measured, margins.existing, margins.fingerPalm, margins.boundary, clearance, fingerPalmClearance);
                seedBeam.add(new Search(trial, measured, trialScore));
                if (trialScore < -1e-8) continue;
                double distance = correctionDistance(trial, candidate, previousProjected, previousCandidate);
                double rawDistance = maxCorrection(trial, candidate);
                if (safeSeed == null || distance < safeSeedDistance - EPSILON
                        || (Math.abs(distance - safeSeedDistance) <= EPSILON && rawDistance < safeSeedRawDistance)) {
                    safeSeed = trial; safeSeedMetrics = measured;
                    safeSeedDistance = distance; safeSeedRawDistance = rawDistance;
                }
            }
            if (safeSeed != null) {
                safeSeed = refineTowardPrevious(safeSeed, candidate, previousProjected, previousCandidate,
                        previousClearance, clearance, previousFingerPalmClearance, fingerPalmClearance);
                safeSeedMetrics = measure(safeSeed);
                return new Result(safeSeed, before, safeSeedMetrics, true, maxCorrection(safeSeed, candidate));
            }
            // A transition can require coordinated motion in more than one
            // shoulder/elbow pair.  Only after the inexpensive sparse probes
            // fail, evaluate the finite 2^6 rate-bounded corner set.
            for (Map<String, Double> trial : cornerCandidates(candidate, joints, levels)) {
                trial = boundedCorrection(trial, candidate, previousProjected, previousCandidate);
                Metrics measured = measure(trial);
                PathMargins margins = pathMargins(previousProjected, trial, previousClearance, clearance, previousFingerPalmClearance, fingerPalmClearance);
                double trialScore = safetyScore(measured, margins.existing, margins.fingerPalm, margins.boundary, clearance, fingerPalmClearance);
                if (trialScore < -1e-8) continue;
                double distance = correctionDistance(trial, candidate, previousProjected, previousCandidate);
                double rawDistance = maxCorrection(trial, candidate);
                if (safeSeed == null || distance < safeSeedDistance - EPSILON
                        || (Math.abs(distance - safeSeedDistance) <= EPSILON && rawDistance < safeSeedRawDistance)) {
                    safeSeed = trial; safeSeedMetrics = measured;
                    safeSeedDistance = distance; safeSeedRawDistance = rawDistance;
                }
            }
            if (safeSeed != null) {
                safeSeed = refineTowardPrevious(safeSeed, candidate, previousProjected, previousCandidate,
                        previousClearance, clearance, previousFingerPalmClearance, fingerPalmClearance);
                safeSeedMetrics = measure(safeSeed);
                return new Result(safeSeed, before, safeSeedMetrics, true, maxCorrection(safeSeed, candidate));
            }
            Search best = new Search(projected, after, score);
            Collections.sort(seedBeam, new Comparator<Search>() { @Override public int compare(Search a, Search b) { return Double.compare(b.score, a.score); } });
            List<Search> beam = new ArrayList<Search>();
            Set<String> visited = new HashSet<String>(); visited.add(correctionKey(projected, candidate));
            for (Search seed : seedBeam) {
                if (!visited.add(correctionKey(seed.pose, candidate))) continue;
                beam.add(seed); if (seed.score > best.score) best = seed;
                if (beam.size() == 8) break;
            }
            if (beam.isEmpty()) beam.add(best);
            int evaluated = 0;
            search: for (int iteration = 0; iteration < 32; iteration++) {
                List<Search> next = new ArrayList<Search>();
                for (Search parent : beam) for (String joint : correctionJoints()) for (double direction : directions(joint)) {
                    Map<String, Double> desired = independentTrial(parent.pose, candidate, joint, direction * .5);
                    Map<String, Double> trial = boundedCorrection(desired, candidate, previousProjected, previousCandidate);
                    if (!visited.add(correctionKey(trial, candidate))) continue;
                    if (++evaluated > 768) break search;
                    Metrics measured = measure(trial); PathMargins margins = pathMargins(previousProjected, trial, previousClearance, clearance, previousFingerPalmClearance, fingerPalmClearance); double trialScore = safetyScore(measured, margins.existing, margins.fingerPalm, margins.boundary, clearance, fingerPalmClearance);
                    Search found = new Search(trial, measured, trialScore); next.add(found);
                    if (found.score > best.score) best = found;
                    if (trialScore >= -1e-8) {
                        trial = refineTowardPrevious(trial, candidate, previousProjected, previousCandidate,
                                previousClearance, clearance, previousFingerPalmClearance, fingerPalmClearance);
                        return new Result(trial, before, measure(trial), true, maxCorrection(trial, candidate));
                    }
                }
                if (next.isEmpty()) break;
                Collections.sort(next, new Comparator<Search>() { @Override public int compare(Search a, Search b) { return Double.compare(b.score, a.score); } });
                beam = new ArrayList<Search>(next.subList(0, Math.min(8, next.size())));
            }
            projected = best.pose; after = best.metrics; score = best.score;
            boolean cleared = after.minimum() >= clearance && after.fingerPalm >= fingerPalmClearance && after.boundaryMinimum() >= 0 && safePath(previousProjected, projected, previousClearance, clearance, previousFingerPalmClearance, fingerPalmClearance);
            return new Result(projected, before, after, cleared, maxCorrection(projected, candidate));
        }

        /**
         * Advances an accepted direct-return correction while the raw target is
         * already the exact rest pose. This deliberately never searches for a
         * new escape correction: each active axis can only release by at most
         * one degree, under the usual endpoint and swept-path guards.
         */
        public Result settleAtBase(Map<String, Double> base, Map<String, Double> previousProjected,
                Map<String, Double> previousCandidate, double previousClearance, double clearance,
                double previousFingerPalmClearance, double fingerPalmClearance) {
            if (base == null || previousProjected == null || previousCandidate == null)
                throw new IllegalArgumentException("Direct settling needs base and prior projected/raw poses");
            if (!finite(previousClearance) || !finite(clearance) || !finite(previousFingerPalmClearance) || !finite(fingerPalmClearance))
                throw new IllegalArgumentException("Invalid direct-settling clearance");
            Metrics before = measure(base);
            Map<String, Double> retained = retainedCorrection(base, previousProjected, previousCandidate);
            Metrics retainedMetrics = measure(retained);
            if (retainedMetrics.minimum() < clearance || retainedMetrics.fingerPalm < fingerPalmClearance
                    || retainedMetrics.boundaryMinimum() < 0
                    || !safePath(previousProjected, retained, previousClearance, clearance,
                            previousFingerPalmClearance, fingerPalmClearance))
                return new Result(retained, before, retainedMetrics, false, maxCorrection(retained, base));
            Map<String, Double> relaxed = relaxedCorrection(base, previousProjected, previousCandidate);
            Metrics relaxedMetrics = measure(relaxed);
            if (relaxedMetrics.minimum() >= clearance && relaxedMetrics.fingerPalm >= fingerPalmClearance
                    && relaxedMetrics.boundaryMinimum() >= 0
                    && safePath(previousProjected, relaxed, previousClearance, clearance,
                            previousFingerPalmClearance, fingerPalmClearance))
                return new Result(relaxed, before, relaxedMetrics, true, maxCorrection(relaxed, base));
            Map<String, Double> proportional = proportionalRelaxation(base, retained);
            if (proportional != null) {
                Metrics proportionalMetrics = measure(proportional);
                if (proportionalMetrics.minimum() >= clearance && proportionalMetrics.fingerPalm >= fingerPalmClearance
                        && proportionalMetrics.boundaryMinimum() >= 0
                        && safePath(previousProjected, proportional, previousClearance, clearance,
                                previousFingerPalmClearance, fingerPalmClearance))
                    return new Result(proportional, before, proportionalMetrics, true, maxCorrection(proportional, base));
            }
            Map<String, Double> selective = selectiveRelaxation(base, retained, previousProjected, previousCandidate,
                    previousClearance, clearance, previousFingerPalmClearance, fingerPalmClearance);
            if (selective != null) {
                Metrics selectiveMetrics = measure(selective);
                return new Result(selective, before, selectiveMetrics, true, maxCorrection(selective, base));
            }
            // Retaining without verified progress would only conceal a stuck
            // correction until the bounded tail expires.
            return new Result(retained, before, retainedMetrics, false, maxCorrection(retained, base));
        }

        /** Preserves coupled corrective direction while reducing its largest axis by one degree. */
        private Map<String, Double> proportionalRelaxation(Map<String, Double> candidate, Map<String, Double> retained) {
            double maximum = 0;
            for (String joint : correctionJoints()) {
                Double raw = candidate.get(joint), value = retained.get(joint);
                if (raw != null && value != null) maximum = Math.max(maximum, Math.abs(value.doubleValue() - raw.doubleValue()));
            }
            if (maximum <= RELAX_CORRECTION_STEP + EPSILON) return null;
            double scale = (maximum - RELAX_CORRECTION_STEP) / maximum;
            Map<String, Double> output = new LinkedHashMap<String, Double>(retained);
            for (String joint : correctionJoints()) {
                Double raw = candidate.get(joint), value = retained.get(joint);
                if (raw != null && value != null)
                    output.put(joint, Double.valueOf(raw.doubleValue() + (value.doubleValue() - raw.doubleValue()) * scale));
            }
            return output;
        }

        private List<Map<String, Double>> seedCandidates(Map<String, Double> candidate, String[] joints, double[][] levels) {
            List<Map<String, Double>> results = new ArrayList<Map<String, Double>>();
            results.add(correctionSeed(candidate, joints, new double[joints.length]));
            for (int index = 0; index < joints.length; index++) for (double level : levels[index]) {
                double[] correction = new double[joints.length]; correction[index] = level;
                results.add(correctionSeed(candidate, joints, correction));
            }
            // Opposite shoulder moves and paired elbows are common escape
            // directions, yet this remains a fixed 31-candidate seed budget.
            for (int first = 0; first < joints.length; first += 2) for (double a : new double[] {levels[first][0], levels[first][levels[first].length - 1]}) for (double b : new double[] {levels[first + 1][0], levels[first + 1][levels[first + 1].length - 1]}) {
                double[] correction = new double[joints.length]; correction[first] = a; correction[first + 1] = b;
                results.add(correctionSeed(candidate, joints, correction));
            }
            return results;
        }
        private List<Map<String, Double>> cornerCandidates(Map<String, Double> candidate, String[] joints, double[][] levels) {
            List<Map<String, Double>> results = new ArrayList<Map<String, Double>>();
            for (int mask = 0; mask < (1 << joints.length); mask++) {
                double[] correction = new double[joints.length];
                for (int index = 0; index < joints.length; index++) correction[index] = levels[index][(mask & (1 << index)) == 0 ? 0 : levels[index].length - 1];
                results.add(correctionSeed(candidate, joints, correction));
            }
            return results;
        }

        /** Reuses the previous corrective offset without changing raw arm targets. */
        private Map<String, Double> retainedCorrection(Map<String, Double> candidate,
                Map<String, Double> previousProjected, Map<String, Double> previousCandidate) {
            if (previousProjected == null || previousCandidate == null) return candidate;
            Map<String, Double> output = new LinkedHashMap<String, Double>(candidate);
            for (String joint : correctionJoints()) {
                Double raw = candidate.get(joint), prior = previousProjected.get(joint), priorRaw = previousCandidate.get(joint);
                if (raw == null || prior == null || priorRaw == null) continue;
                double priorCorrection = prior.doubleValue() - priorRaw.doubleValue();
                if (priorCorrection < correctionMinimum(joint) - EPSILON || priorCorrection > correctionMaximum(joint) + EPSILON)
                    throw new IllegalArgumentException("Previous hand correction is outside the bounded workspace: " + joint);
                output.put(joint, Double.valueOf(raw.doubleValue() + clamp(priorCorrection, correctionMinimum(joint), correctionMaximum(joint))));
            }
            return output;
        }

        /** Releases a retained correction at a deliberately smaller rate than corrective entry. */
        private Map<String, Double> relaxedCorrection(Map<String, Double> candidate,
                Map<String, Double> previousProjected, Map<String, Double> previousCandidate) {
            if (previousProjected == null || previousCandidate == null) return candidate;
            Map<String, Double> output = new LinkedHashMap<String, Double>(candidate);
            for (String joint : correctionJoints()) {
                Double raw = candidate.get(joint), prior = previousProjected.get(joint), priorRaw = previousCandidate.get(joint);
                if (raw == null || prior == null || priorRaw == null) continue;
                double priorCorrection = prior.doubleValue() - priorRaw.doubleValue();
                if (priorCorrection < correctionMinimum(joint) - EPSILON || priorCorrection > correctionMaximum(joint) + EPSILON)
                    throw new IllegalArgumentException("Previous hand correction is outside the bounded workspace: " + joint);
                double released = priorCorrection - Math.signum(priorCorrection) * Math.min(Math.abs(priorCorrection), RELAX_CORRECTION_STEP);
                output.put(joint, Double.valueOf(raw.doubleValue() + released));
            }
            return output;
        }

        /**
         * When releasing every axis together crosses the tabletop boundary,
         * release only a safe subset. Every changed axis still moves at most
         * one degree toward its raw target; no new corrective direction is
         * introduced. The finite 2^6 search is only entered after full release
         * was unsafe and retains the greatest verified progress toward zero.
         */
        private Map<String, Double> selectiveRelaxation(Map<String, Double> candidate, Map<String, Double> retained,
                Map<String, Double> previousProjected, Map<String, Double> previousCandidate,
                double previousClearance, double clearance, double previousFingerPalmClearance, double fingerPalmClearance) {
            String[] joints = correctionJoints();
            double retainedMagnitude = correctionMagnitude(retained, candidate);
            int activeMask = 0;
            for (int index = 0; index < joints.length; index++) {
                Double raw = candidate.get(joints[index]), value = retained.get(joints[index]);
                if (raw != null && value != null && Math.abs(value.doubleValue() - raw.doubleValue()) > EPSILON) activeMask |= 1 << index;
            }
            Map<String, Double> best = null;
            double bestMagnitude = retainedMagnitude, bestScore = -Double.MAX_VALUE;
            for (int mask = 1; mask < (1 << joints.length); mask++) {
                if ((mask & ~activeMask) != 0 || mask == activeMask) continue;
                Map<String, Double> trial = new LinkedHashMap<String, Double>(retained);
                for (int index = 0; index < joints.length; index++) if ((mask & (1 << index)) != 0) {
                    String joint = joints[index]; Double raw = candidate.get(joint), value = retained.get(joint);
                    if (raw == null || value == null) continue;
                    double correction = value.doubleValue() - raw.doubleValue();
                    double released = correction - Math.signum(correction) * Math.min(Math.abs(correction), RELAX_CORRECTION_STEP);
                    trial.put(joint, Double.valueOf(raw.doubleValue() + released));
                }
                Metrics measured = measure(trial);
                if (measured.minimum() < clearance || measured.fingerPalm < fingerPalmClearance || measured.boundaryMinimum() < 0) continue;
                PathMargins margins = pathMargins(previousProjected, trial, previousClearance, clearance, previousFingerPalmClearance, fingerPalmClearance);
                if (!margins.existing.safe() || !margins.fingerPalm.safe() || !margins.boundary.safe()) continue;
                double magnitude = correctionMagnitude(trial, candidate);
                if (magnitude >= retainedMagnitude - EPSILON) continue;
                double score = safetyScore(measured, margins.existing, margins.fingerPalm, margins.boundary, clearance, fingerPalmClearance);
                if (best == null || magnitude < bestMagnitude - EPSILON
                        || (Math.abs(magnitude - bestMagnitude) <= EPSILON && score > bestScore)) {
                    best = trial; bestMagnitude = magnitude; bestScore = score;
                }
            }
            return best;
        }

        private double correctionMagnitude(Map<String, Double> pose, Map<String, Double> candidate) {
            double total = 0;
            for (String joint : correctionJoints()) {
                Double value = pose.get(joint), raw = candidate.get(joint);
                if (value != null && raw != null) total += Math.abs(value.doubleValue() - raw.doubleValue());
            }
            return total;
        }

        /** Finds the nearest safe point between the retained correction and a coarse safe seed. */
        private Map<String, Double> refineTowardPrevious(Map<String, Double> safeSeed, Map<String, Double> candidate,
                Map<String, Double> previousProjected, Map<String, Double> previousCandidate,
                double previousClearance, double clearance, double previousFingerPalmClearance, double fingerPalmClearance) {
            if (previousProjected == null || previousCandidate == null) return safeSeed;
            Map<String, Double> retained = retainedCorrection(candidate, previousProjected, previousCandidate);
            for (int step = 1; step < CONTINUITY_REFINEMENT_STEPS; step++) {
                double weight = step / (double)CONTINUITY_REFINEMENT_STEPS;
                Map<String, Double> trial = new LinkedHashMap<String, Double>(candidate);
                for (String joint : correctionJoints()) {
                    Double raw = candidate.get(joint), from = retained.get(joint), to = safeSeed.get(joint);
                    if (raw != null && from != null && to != null)
                        trial.put(joint, Double.valueOf(from.doubleValue() * (1.0 - weight) + to.doubleValue() * weight));
                }
                trial = boundedCorrection(trial, candidate, previousProjected, previousCandidate);
                Metrics measured = measure(trial);
                if (measured.minimum() >= clearance && measured.fingerPalm >= fingerPalmClearance
                        && measured.boundaryMinimum() >= 0
                        && safePath(previousProjected, trial, previousClearance, clearance, previousFingerPalmClearance, fingerPalmClearance))
                    return trial;
            }
            return safeSeed;
        }

        private Map<String, Double> boundedCorrection(Map<String, Double> desiredPose, Map<String, Double> candidate,
                Map<String, Double> previousProjected, Map<String, Double> previousCandidate) {
            if (previousProjected == null || previousCandidate == null) return desiredPose;
            Map<String, Double> output = new LinkedHashMap<String, Double>(candidate);
            for (String joint : correctionJoints()) {
                Double desired = desiredPose.get(joint), raw = candidate.get(joint), prior = previousProjected.get(joint), priorRaw = previousCandidate.get(joint);
                if (desired == null || raw == null || prior == null || priorRaw == null) continue;
                double priorCorrection = prior.doubleValue() - priorRaw.doubleValue();
                double desiredCorrection = desired.doubleValue() - raw.doubleValue();
                double lower = Math.max(correctionMinimum(joint), priorCorrection - MAX_CORRECTION_STEP);
                double upper = Math.min(correctionMaximum(joint), priorCorrection + MAX_CORRECTION_STEP);
                if (lower > upper) throw new IllegalArgumentException("Previous hand correction is outside the bounded workspace: " + joint);
                output.put(joint, Double.valueOf(raw.doubleValue() + clamp(desiredCorrection, lower, upper)));
            }
            return output;
        }

        private double[] seedLevels(String joint, Map<String, Double> candidate,
                Map<String, Double> previousProjected, Map<String, Double> previousCandidate) {
            Double raw = candidate.get(joint);
            Double prior = previousProjected == null ? null : previousProjected.get(joint);
            Double priorRaw = previousCandidate == null ? null : previousCandidate.get(joint);
            if (raw == null) return new double[] {0};
            double priorCorrection = prior == null || priorRaw == null ? 0 : prior.doubleValue() - priorRaw.doubleValue();
            double low = Math.max(correctionMinimum(joint), priorCorrection - MAX_CORRECTION_STEP);
            double high = Math.min(correctionMaximum(joint), priorCorrection + MAX_CORRECTION_STEP);
            if (low > high) throw new IllegalArgumentException("Previous hand correction is outside the bounded workspace: " + joint);
            return new double[] {low, (low + high) / 2.0, high};
        }

        private Map<String, Double> correctionSeed(Map<String, Double> candidate, String[] joints, double[] corrections) {
            Map<String, Double> result = new LinkedHashMap<String, Double>(candidate);
            for (int i = 0; i < joints.length; i++) {
                Double raw = candidate.get(joints[i]);
                if (raw != null) result.put(joints[i], Double.valueOf(raw.doubleValue() + corrections[i]));
            }
            return result;
        }

        private double correctionDistance(Map<String, Double> pose, Map<String, Double> candidate,
                Map<String, Double> previousProjected, Map<String, Double> previousCandidate) {
            double total = 0;
            for (String joint : correctionJoints()) {
                Double value = pose.get(joint), raw = candidate.get(joint);
                Double prior = previousProjected == null ? null : previousProjected.get(joint);
                Double priorRaw = previousCandidate == null ? null : previousCandidate.get(joint);
                if (value != null && raw != null) total += Math.abs(value.doubleValue() - raw.doubleValue()
                        - (prior == null || priorRaw == null ? 0 : prior.doubleValue() - priorRaw.doubleValue()));
            }
            return total;
        }

        public PathMargin pathMargin(Map<String, Double> previous, Map<String, Double> next, double previousClearance, double clearance) {
            return pathMargins(previous, next, previousClearance, clearance, -Double.MAX_VALUE, -Double.MAX_VALUE).existing;
        }
        private PathMargins pathMargins(Map<String, Double> previous, Map<String, Double> next, double previousClearance, double clearance, double previousFingerPalmClearance, double fingerPalmClearance) {
            if (previous == null || previous.isEmpty()) return new PathMargins(PathMargin.EMPTY, PathMargin.EMPTY, PathMargin.EMPTY);
            MarginAccumulator existing = new MarginAccumulator(), fingerPalm = new MarginAccumulator(), boundary = new MarginAccumulator();
            for (int sample = 1; sample <= 3; sample++) {
                double weight = sample / 4.0;
                Map<String, Double> frame = new LinkedHashMap<String, Double>(next);
                for (String joint : next.keySet()) {
                    Double from = previous.get(joint), to = next.get(joint);
                    if (from != null && to != null) frame.put(joint, Double.valueOf(from.doubleValue() * (1.0 - weight) + to.doubleValue() * weight));
                }
                Metrics metrics = measure(frame);
                existing.accept(metrics.minimum(), previousClearance * (1.0 - weight) + clearance * weight, sample);
                fingerPalm.accept(metrics.fingerPalm, previousFingerPalmClearance * (1.0 - weight) + fingerPalmClearance * weight, sample);
                boundary.accept(metrics.boundaryMinimum(), 0, sample);
            }
            return new PathMargins(existing.result(), fingerPalm.result(), boundary.result());
        }

        public PathMargin pathMarginFingerPalm(Map<String, Double> previous, Map<String, Double> next, double previousClearance, double clearance) {
            return pathMargins(previous, next, -Double.MAX_VALUE, -Double.MAX_VALUE, previousClearance, clearance).fingerPalm;
        }
        /** Swept hard-zero margin for torso, upper-arm, and tabletop proxies. */
        public PathMargin pathMarginBoundary(Map<String, Double> previous, Map<String, Double> next) {
            return pathMargins(previous, next, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE).boundary;
        }

        double pathMarginAll(Map<String, Double> previous, Map<String, Double> next, double clearance, double palmClearance) {
            PathMargins margins = pathMargins(previous, next, clearance, clearance, palmClearance, palmClearance);
            return Math.min(margins.boundary.minimumMargin, Math.min(margins.existing.minimumMargin, margins.fingerPalm.minimumMargin));
        }
        private boolean safePath(Map<String, Double> previous, Map<String, Double> next, double previousClearance, double clearance, double previousFingerPalmClearance, double fingerPalmClearance) {
            PathMargins margins = pathMargins(previous, next, previousClearance, clearance, previousFingerPalmClearance, fingerPalmClearance);
            return margins.existing.safe() && margins.fingerPalm.safe() && margins.boundary.safe();
        }

        private Map<String, Double> independentTrial(Map<String, Double> current, Map<String, Double> candidate, String joint, double delta) {
            Map<String, Double> desired = new LinkedHashMap<String, Double>(current); Double base = candidate.get(joint), value = current.get(joint);
            if (base == null || value == null) return desired;
            double correction = value.doubleValue() + delta - base.doubleValue();
            desired.put(joint, Double.valueOf(base.doubleValue() + clamp(correction, correctionMinimum(joint), correctionMaximum(joint)))); return desired;
        }
        private static double correctionMinimum(String joint) { return joint.indexOf("elbow") >= 0 ? -16.0 : -16.0; }
        private static double correctionMaximum(String joint) { return joint.indexOf("elbow") >= 0 ? 16.0 : 16.0; }
        private static double[] directions(String joint) { return new double[] {-1.0, 1.0}; }
        private static double safetyScore(Metrics metrics, PathMargin path, PathMargin fingerPalmPath, PathMargin boundaryPath, double clearance, double fingerPalmClearance) { return Math.min(Math.min(Math.min(metrics.minimum() - clearance, path.minimumMargin), Math.min(metrics.fingerPalm - fingerPalmClearance, fingerPalmPath.minimumMargin)), Math.min(metrics.boundaryMinimum(), boundaryPath.minimumMargin)); }
        private static double maxCorrection(Map<String, Double> projected, Map<String, Double> candidate) { double maximum = 0; for (String joint : correctionJoints()) { Double a=projected.get(joint), b=candidate.get(joint); if(a!=null&&b!=null) maximum=Math.max(maximum,Math.abs(a.doubleValue()-b.doubleValue())); } return maximum; }
    private static String correctionKey(Map<String, Double> pose, Map<String, Double> candidate) { StringBuilder key = new StringBuilder(); for (String joint : correctionJoints()) { Double value=pose.get(joint), base=candidate.get(joint); key.append(Math.round(((value==null||base==null?0:value.doubleValue()-base.doubleValue()) * 2.0))).append('/'); } return key.toString(); }

        private Vec position(String name, Map<String, Double> pose, Map<String, Transform> world) {
            Transform cached = world.get(name); if (cached != null) return cached.position;
            Bone bone = bones.get(name); if (bone == null) throw new IllegalStateException("Snapshot lacks bone " + name);
            Transform parent = bone.parent == null ? Transform.IDENTITY : transform(bone.parent, pose, world);
            Mapper mapper = mappers.get(name);
            Quat local = mapper == null ? twistOrientation(name, pose, bone.orientation) : mapper.localOrientation(pose);
            Transform result = new Transform(parent.rotation.multiply(local), parent.position.add(parent.rotation.rotate(bone.position)));
            world.put(name, result); return result.position;
        }
        private Transform transform(String name, Map<String, Double> pose, Map<String, Transform> world) { position(name, pose, world); return world.get(name); }
        private Quat twistOrientation(String name, Map<String, Double> pose, Quat fallback) {
            for (Mapper mapper : mappers.values()) if (name.equals(mapper.twistBone)) return mapper.twistLocalOrientation(pose);
            return fallback;
        }
    }

    public static final class Result {
        public final Map<String, Double> pose; public final Metrics before, after; public final boolean cleared; public final double maxCorrectionDegrees;
        Result(Map<String, Double> pose, Metrics before, Metrics after, boolean cleared, double maxCorrectionDegrees) {
            this.pose = Collections.unmodifiableMap(new LinkedHashMap<String, Double>(pose)); this.before = before; this.after = after; this.cleared = cleared; this.maxCorrectionDegrees = maxCorrectionDegrees;
        }
    }
    /** Immutable local calibration for a zero-extra-padding tabletop rest. */
    private static final class TableContactRest implements Serializable {
        private static final long serialVersionUID = 1L;
        final Map<String, Double> pose;
        final double releaseDistance;
        TableContactRest(Map<String, Double> pose, double releaseDistance) {
            if (pose == null || pose.isEmpty() || !finite(releaseDistance) || releaseDistance <= 0)
                throw new IllegalArgumentException("A finite non-empty tabletop contact rest and positive release distance are required");
            Map<String, Double> copy = new LinkedHashMap<String, Double>();
            for (Map.Entry<String, Double> entry : pose.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null || !finite(entry.getValue().doubleValue()))
                    throw new IllegalArgumentException("Tabletop contact rest contains an invalid BAP value");
                copy.put(entry.getKey(), Double.valueOf(entry.getValue().doubleValue()));
            }
            this.pose = Collections.unmodifiableMap(copy);
            this.releaseDistance = releaseDistance;
        }
    }

    /**
     * Immutable conservative proxies for the seated workspace.  They are not
     * a character mesh or physics solver: callers must provide table bounds in
     * environment coordinates and a separately verified avatar translation.
     */
    public static final class Boundaries implements Serializable {
        private static final long serialVersionUID = 1L;
        private static final Boundaries NONE = new Boundaries(null, false, 0, 0, 0, 0, 0, 0);
        final Tabletop table; final boolean bodyEnabled;
        final double worldX, worldY, worldZ;
        final double torsoRadiusScale, upperArmRadiusScale, torsoMargin, upperArmMargin, tableMargin;
        private Boundaries(Tabletop table, boolean bodyEnabled, double worldX, double worldY, double worldZ,
                double torsoMargin, double upperArmMargin, double tableMargin) {
            this.table=table; this.bodyEnabled=bodyEnabled; this.worldX=worldX; this.worldY=worldY; this.worldZ=worldZ;
            this.torsoRadiusScale=.38; this.upperArmRadiusScale=.08;
            this.torsoMargin=torsoMargin; this.upperArmMargin=upperArmMargin; this.tableMargin=tableMargin;
        }
        public static Boundaries none() { return NONE; }
        public static Boundaries seated(double minimumX, double maximumX, double minimumZ, double maximumZ, double topY,
                double avatarWorldX, double avatarWorldY, double avatarWorldZ) {
            return seated(minimumX, maximumX, minimumZ, maximumZ, topY, avatarWorldX, avatarWorldY, avatarWorldZ, .01, .008, .008);
        }
        public static Boundaries seated(double minimumX, double maximumX, double minimumZ, double maximumZ, double topY,
                double avatarWorldX, double avatarWorldY, double avatarWorldZ, double torsoMargin, double upperArmMargin, double tableMargin) {
            if (!finite(minimumX) || !finite(maximumX) || !finite(minimumZ) || !finite(maximumZ) || !finite(topY)
                    || !finite(avatarWorldX) || !finite(avatarWorldY) || !finite(avatarWorldZ)
                    || !finite(torsoMargin) || !finite(upperArmMargin) || !finite(tableMargin)
                    || minimumX >= maximumX || minimumZ >= maximumZ || torsoMargin < 0 || upperArmMargin < 0 || tableMargin < 0)
                throw new IllegalArgumentException("Invalid seated collision boundaries");
            return new Boundaries(new Tabletop(minimumX, maximumX, minimumZ, maximumZ, topY), true,
                    avatarWorldX, avatarWorldY, avatarWorldZ, torsoMargin, upperArmMargin, tableMargin);
        }
        boolean enabled() { return bodyEnabled || table != null; }
        Vec toWorld(Vec local) { return new Vec(local.x + worldX, local.y + worldY, local.z + worldZ); }
        Boundaries withRoundTable() {
            return new Boundaries(new Tabletop(table.minimumX, table.maximumX, table.minimumZ, table.maximumZ,
                    table.topY, true), bodyEnabled, worldX, worldY, worldZ, torsoMargin, upperArmMargin, tableMargin);
        }
    }
    private static final class Tabletop implements Serializable {
        private static final long serialVersionUID = 1L;
        final double minimumX, maximumX, minimumZ, maximumZ, topY;
        final boolean roundRest;
        // Bounds of the four leg meshes, in table.mesh local coordinates.
        private static final double[][] LEGS = {
                {.17019, .23883, 1.05269, 1.122}, {.57793, .64658, .17828, .24692},
                {1.04802, 1.11666, 1.45902, 1.52767}, {1.45461, 1.52326, .58708, .65572}
        };
        Tabletop(double minimumX,double maximumX,double minimumZ,double maximumZ,double topY){
            this(minimumX, maximumX, minimumZ, maximumZ, topY, false);
        }
        Tabletop(double minimumX,double maximumX,double minimumZ,double maximumZ,double topY,boolean roundRest){
            this.minimumX=minimumX;this.maximumX=maximumX;this.minimumZ=minimumZ;this.maximumZ=maximumZ;this.topY=topY;
            this.roundRest=roundRest;
            if (roundRest && (Math.abs(maximumX - minimumX - 1.6976) > .0001
                    || Math.abs(maximumZ - minimumZ - 1.6976) > .0001 || Math.abs(topY - .7078) > .0001))
                throw new IllegalArgumentException("Round-table rest boundary requires the calibrated stock table dimensions");
        }
        /** Surface clearance above the finite rectangular tabletop; its edge is deliberately not treated as mesh geometry. */
        double clearance(Vec start, Vec end, double radius) {
            if (roundRest) return finiteTableClearance(start, end, radius);
            double[] interval = projectedInterval(start.x, end.x, start.z, end.z, minimumX - radius, maximumX + radius, minimumZ - radius, maximumZ + radius);
            if (interval == null) return Double.POSITIVE_INFINITY;
            double y0 = start.y + (end.y - start.y) * interval[0], y1 = start.y + (end.y - start.y) * interval[1];
            return Math.min(y0, y1) - topY - radius;
        }
        private double finiteTableClearance(Vec start, Vec end, double radius) {
            double x = start.x - (minimumX + maximumX) * .5, z = start.z - (minimumZ + maximumZ) * .5;
            double dx = end.x - start.x, dz = end.z - start.z;
            double diskRadius = .84884 + radius;
            double a = dx * dx + dz * dz, b = x * dx + z * dz, c = x * x + z * z - diskRadius * diskRadius;
            double margin = Double.POSITIVE_INFINITY;
            if (a < EPSILON) {
                if (c <= 0) margin = verticalClearance(start, end, 0, 1, .67130 - radius, topY + radius);
            } else if (b * b - a * c >= 0) {
                double root = Math.sqrt(b * b - a * c);
                double low = Math.max(0, (-b - root) / a), high = Math.min(1, (-b + root) / a);
                if (low <= high) margin = verticalClearance(start, end, low, high, .67130 - radius, topY + radius);
            }
            for (double[] leg : LEGS) {
                double[] interval = projectedInterval(start.x, end.x, start.z, end.z,
                        minimumX + leg[0] - radius, minimumX + leg[1] + radius,
                        minimumZ + leg[2] - radius, minimumZ + leg[3] + radius);
                if (interval != null)
                    margin = Math.min(margin, verticalClearance(start, end, interval[0], interval[1], -radius, .69384 + radius));
            }
            return margin;
        }
        private static double verticalClearance(Vec start, Vec end, double low, double high, double bottom, double top) {
            double first = start.y + (end.y - start.y) * low, last = start.y + (end.y - start.y) * high;
            return Math.max(Math.min(first, last) - top, bottom - Math.max(first, last));
        }
        private static double[] projectedInterval(double x0,double x1,double z0,double z1,double minX,double maxX,double minZ,double maxZ) {
            double[] interval = new double[] {0,1};
            if (!clip(x0, x1 - x0, minX, maxX, interval) || !clip(z0, z1 - z0, minZ, maxZ, interval)) return null;
            return interval;
        }
        private static boolean clip(double origin,double delta,double minimum,double maximum,double[] interval) {
            if (Math.abs(delta) < EPSILON) return origin >= minimum && origin <= maximum;
            double a=(minimum-origin)/delta,b=(maximum-origin)/delta; if(a>b){double swap=a;a=b;b=swap;}
            interval[0]=Math.max(interval[0],a); interval[1]=Math.min(interval[1],b); return interval[0] <= interval[1];
        }
    }
    private static final class BoundaryMetrics {
        static final BoundaryMetrics NONE = new BoundaryMetrics(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        final double handsToTorso, handsToUpperArms, tabletop, upperArmsToTorso, upperArmsToTable;
        BoundaryMetrics(double handsToTorso,double handsToUpperArms,double tabletop,double upperArmsToTorso,double upperArmsToTable){this.handsToTorso=handsToTorso;this.handsToUpperArms=handsToUpperArms;this.tabletop=tabletop;this.upperArmsToTorso=upperArmsToTorso;this.upperArmsToTable=upperArmsToTable;}
    }
    public static final class PathMargin {
        static final PathMargin EMPTY = new PathMargin(Double.POSITIVE_INFINITY, Double.NaN, Double.NaN, -1);
        public final double minimumMargin, measured, threshold; public final int sample;
        PathMargin(double minimumMargin, double measured, double threshold, int sample) { this.minimumMargin=minimumMargin; this.measured=measured; this.threshold=threshold; this.sample=sample; }
        boolean safe() { return minimumMargin >= -1e-8; }
    }
    private static final class PathMargins { final PathMargin existing, fingerPalm, boundary; PathMargins(PathMargin existing, PathMargin fingerPalm, PathMargin boundary) { this.existing=existing; this.fingerPalm=fingerPalm; this.boundary=boundary; } }
    private static final class MarginAccumulator { double margin=Double.POSITIVE_INFINITY, measured=Double.NaN, threshold=Double.NaN; int sample=-1; void accept(double nextMeasured, double nextThreshold, int nextSample) { double nextMargin=nextMeasured-nextThreshold; if(nextMargin<margin){margin=nextMargin;measured=nextMeasured;threshold=nextThreshold;sample=nextSample;} } PathMargin result(){return new PathMargin(margin,measured,threshold,sample);} }
    public static final class Metrics {
        public final double hands, forearms, leftHandToRightForearm, rightHandToLeftForearm, fingers, fingerPalm;
        public final double handsToTorso, handsToUpperArms, tabletop, upperArmsToTorso, upperArmsToTable;
        public final Vec leftArm, leftForearm, leftHand, rightArm, rightForearm, rightHand;
        Metrics(double hands, double forearms, double lhrf, double rhlf, double fingers, double fingerPalm,
                double handsToTorso, double handsToUpperArms, double tabletop, double upperArmsToTorso, double upperArmsToTable, Vec la, Vec lf, Vec lh, Vec ra, Vec rf, Vec rh) {
            this.hands=hands; this.forearms=forearms; leftHandToRightForearm=lhrf; rightHandToLeftForearm=rhlf; this.fingers=fingers; this.fingerPalm=fingerPalm;
            this.handsToTorso=handsToTorso; this.handsToUpperArms=handsToUpperArms; this.tabletop=tabletop; this.upperArmsToTorso=upperArmsToTorso; this.upperArmsToTable=upperArmsToTable;
            leftArm=la; leftForearm=lf; leftHand=lh; rightArm=ra; rightForearm=rf; rightHand=rh;
        }
        public double minimum() { return Math.min(Math.min(Math.min(hands, forearms), Math.min(leftHandToRightForearm, rightHandToLeftForearm)), fingers); }
        /** Boundary proxies deliberately have an independent hard zero threshold. */
        public double boundaryMinimum() { return Math.min(Math.min(handsToTorso, handsToUpperArms), Math.min(tabletop, Math.min(upperArmsToTorso, upperArmsToTable))); }
    }

    private static final class Bone implements Serializable {
        private static final long serialVersionUID = 1L;
        final String name, parent; final Vec position, derived; final Quat orientation;
        Bone(String name, String parent, Vec position, Quat orientation, Vec derived) { this.name=name; this.parent=parent; this.position=position; this.orientation=orientation; this.derived=derived; }
    }
    private static final class Mapper implements Serializable {
        private static final long serialVersionUID = 1L;
        final String kind; final Quat pre, post; final Axis[] axes; final double scale, twist;
        final Mapper acromium; final String twistBone; final Quat twistPre, twistPost;
        Mapper(String kind, Quat pre, Quat post, Axis[] axes, double scale, double twist, Mapper acromium, String twistBone, Quat twistPre, Quat twistPost) {
            this.kind=kind; this.pre=pre; this.post=post; this.axes=axes; this.scale=scale; this.twist=twist; this.acromium=acromium; this.twistBone=twistBone; this.twistPre=twistPre; this.twistPost=twistPost;
        }
        static Mapper capture(Object raw, String ignoredBone) throws Exception {
            String kind = raw.getClass().getSimpleName();
            if (!("OneDOF".equals(kind) || "TwoDOF".equals(kind) || "ThreeDOF".equals(kind)
                    || "ThreeDOFScaled".equals(kind) || "YawTwistBeforeMapper".equals(kind)
                    || "YawTwistAfterMapper".equals(kind) || "SortedTwistMapper".equals(kind)
                    || "SortedTwistBeforeMapper".equals(kind) || "ShoulderSortedTwistMapper".equals(kind)))
                throw new IllegalStateException("Unsupported Autodesk BAP mapper " + kind);
            Axis[] axes = axes(raw);
            if (axes.length == 0) return null;
            Mapper acromium = null;
            Object extra = optionalField(raw, "acromium");
            if (extra != null) acromium = capture(extra, ignoredBone);
            Object rawTwist = optionalField(raw, "twist"); Object rawTwistBone = rawTwist == null ? null : field(rawTwist, "bone");
            return new Mapper(kind, quaternion(field(raw, "preRotation")), quaternion(field(raw, "postRotation")), axes,
                    number(optionalField(raw, "scale"), 1), number(optionalField(raw, "twistFactor"), 0), acromium,
                    rawTwistBone == null ? null : String.valueOf(call(rawTwistBone, "getName")), rawTwist == null ? null : quaternion(field(rawTwist, "preRotation")), rawTwist == null ? null : quaternion(field(rawTwist, "postRotation")));
        }
        Quat localOrientation(Map<String, Double> values) {
            Quat rotation = rotation(values);
            return post.multiply(rotation).multiply(pre).normalized();
        }
        Quat rotation(Map<String, Double> values) {
            double a1 = value(values, 0), a2 = value(values, 1), a3 = value(values, 2);
            Quat q1 = axes.length < 1 ? Quat.IDENTITY : Quat.axis(axes[0].axis, a1 * scale);
            Quat q2 = axes.length < 2 ? Quat.IDENTITY : Quat.axis(axes[1].axis, a2 * scale);
            Quat q3 = axes.length < 3 ? Quat.IDENTITY : Quat.axis(axes[2].axis, a3 * scale);
            Quat unmodified = q3.multiply(q2).multiply(q1).normalized();
            Quat result = unmodified;
            if (kind.indexOf("SortedTwist") >= 0) {
                Quat split = Quat.axis(axes[0].axis, a1 * twist);
                q1 = Quat.axis(axes[0].axis, a1 * (1.0 - twist));
                if (kind.indexOf("Before") >= 0) {
                    q2 = Quat.axis(split.inverse().rotate(axes[1].axis), a2);
                    q3 = Quat.axis(split.inverse().rotate(axes[2].axis), a3);
                }
                result = q3.multiply(q2).multiply(q1);
            }
            if (kind.indexOf("YawTwist") >= 0) {
                double yaw = Math.atan2(2*unmodified.x*unmodified.z + 2*unmodified.y*unmodified.w, 1 - 2*(unmodified.x*unmodified.x + unmodified.y*unmodified.y)) * twist;
                Quat t = Quat.axis(new Vec(0, 1, 0), yaw).inverse();
                result = kind.indexOf("Before") >= 0 ? t.multiply(result) : result.multiply(t);
            }
            if (acromium != null) result = acromium.rotation(values).multiply(result);
            return result.normalized();
        }
        Quat twistLocalOrientation(Map<String, Double> values) {
            if (twistPre == null || twistPost == null) throw new IllegalStateException("Missing nested twist snapshot");
            double a1 = value(values, 0), a2 = value(values, 1), a3 = value(values, 2);
            Quat original = (axes.length < 3 ? Quat.IDENTITY : Quat.axis(axes[2].axis, a3 * scale))
                    .multiply(axes.length < 2 ? Quat.IDENTITY : Quat.axis(axes[1].axis, a2 * scale))
                    .multiply(axes.length < 1 ? Quat.IDENTITY : Quat.axis(axes[0].axis, a1 * scale)).normalized();
            Quat twistRotation;
            if (kind.indexOf("SortedTwist") >= 0) twistRotation = Quat.axis(axes[0].axis, value(values, 0) * twist);
            else {
                double yaw = Math.atan2(2*original.x*original.z + 2*original.y*original.w, 1 - 2*(original.x*original.x + original.y*original.y)) * twist;
                twistRotation = Quat.axis(new Vec(0, 1, 0), yaw);
            }
            return twistPost.multiply(twistRotation).multiply(twistPre).normalized();
        }
        private double value(Map<String, Double> values, int index) {
            if (index >= axes.length) return 0;
            Double supplied = values.get(axes[index].name);
            return Math.toRadians(supplied == null ? axes[index].defaultDegrees : supplied.doubleValue());
        }
        private static Axis[] axes(Object raw) throws Exception {
            List<Axis> out = new ArrayList<Axis>();
            for (int i=1; i<=3; i++) {
                Object type = optionalField(raw, "type" + i), axis = optionalField(raw, "axis" + i);
                if (type != null && axis != null) out.add(new Axis(String.valueOf(type), vector(axis), Math.toDegrees(number(optionalField(raw, "lastVal" + i), 0))));
            }
            return out.toArray(new Axis[out.size()]);
        }
    }
    private static final class Axis implements Serializable { private static final long serialVersionUID = 1L; final String name; final Vec axis; final double defaultDegrees; Axis(String n, Vec a, double d){name=n;axis=a;defaultDegrees=d;} }
    public static final class Vec implements Serializable {
        private static final long serialVersionUID = 1L;
        public final double x,y,z; Vec(double x,double y,double z){this.x=x;this.y=y;this.z=z;}
        Vec add(Vec o){return new Vec(x+o.x,y+o.y,z+o.z);} Vec sub(Vec o){return new Vec(x-o.x,y-o.y,z-o.z);} Vec scale(double s){return new Vec(x*s,y*s,z*s);}
        double dot(Vec o){return x*o.x+y*o.y+z*o.z;} double length2(){return dot(this);} double length(){return Math.sqrt(length2());} double distance(Vec o){return Math.sqrt(sub(o).length2());}
    }
    private static final class Quat implements Serializable {
        private static final long serialVersionUID = 1L;
        static final Quat IDENTITY = new Quat(0,0,0,1); final double x,y,z,w;
        Quat(double x,double y,double z,double w){this.x=x;this.y=y;this.z=z;this.w=w;}
        static Quat axis(Vec a,double angle){double l=Math.sqrt(a.length2()); if(l<EPSILON)return IDENTITY; double s=Math.sin(angle/2)/l; return new Quat(a.x*s,a.y*s,a.z*s,Math.cos(angle/2));}
        Quat multiply(Quat b){return new Quat(w*b.x+b.w*x+y*b.z-z*b.y,w*b.y+b.w*y+z*b.x-x*b.z,w*b.z+b.w*z+x*b.y-y*b.x,w*b.w-x*b.x-y*b.y-z*b.z);}
        Quat inverse(){return new Quat(-x,-y,-z,w).normalized();} Quat normalized(){double l=Math.sqrt(x*x+y*y+z*z+w*w); return l<EPSILON?IDENTITY:new Quat(x/l,y/l,z/l,w/l);}
        Vec rotate(Vec v){Quat q=normalized(), p=new Quat(v.x,v.y,v.z,0); Quat r=q.multiply(p).multiply(q.inverse()); return new Vec(r.x,r.y,r.z);}
    }
    private static final class Transform { static final Transform IDENTITY=new Transform(Quat.IDENTITY,new Vec(0,0,0)); final Quat rotation; final Vec position; Transform(Quat r,Vec p){rotation=r;position=p;} }
    private static final class Segment { final Vec start,end; Segment(Vec start,Vec end){this.start=start;this.end=end;} }
    private static final class BoundarySegment {
        final Segment segment; final double radius; final String side; final boolean handSurface;
        BoundarySegment(Segment segment,double radius,String side,boolean handSurface){this.segment=segment;this.radius=radius;this.side=side;this.handSurface=handSurface;}
    }
    private static final class Search { final Map<String,Double> pose; final Metrics metrics; final double score; Search(Map<String,Double> pose, Metrics metrics, double score){this.pose=pose;this.metrics=metrics;this.score=score;} }

    private static void requireArm(Map<String,Bone> bones,String name){if(!bones.containsKey(name))throw new IllegalStateException("Autodesk snapshot lacks required arm bone "+name);}
    private static void requireTorso(Map<String,Bone> bones) { for (String name : new String[] {"Spine", "Spine1", "Spine2", "Spine3", "Spine4", "Neck"}) if (!bones.containsKey(name)) throw new IllegalStateException("Autodesk snapshot lacks required torso bone " + name); }
    private static void putDelta(Map<String,Double> out,Map<String,Double> base,String key,double delta){Double value=base.get(key); if(value!=null)out.put(key,Double.valueOf(value.doubleValue()+delta));}
    private static String[] correctionJoints(){return new String[]{"l_shoulder_abduct","r_shoulder_abduct","l_shoulder_flexion","r_shoulder_flexion","l_elbow_flexion","r_elbow_flexion"};}
    private static boolean samePose(Map<String,Double> candidate, Map<String,Double> base) { if (!candidate.keySet().equals(base.keySet())) return false; for (String joint : candidate.keySet()) { Double a=candidate.get(joint), b=base.get(joint); if (a==null ? b!=null : b==null || Math.abs(a.doubleValue()-b.doubleValue())>EPSILON) return false; } return true; }
    private static double palmRadius(Vec index, Vec middle, Vec pinky) { return Math.max(index.distance(pinky) * .5, index.distance(middle) * .75); }
    private static double clamp(double value, double minimum, double maximum) { return Math.max(minimum, Math.min(maximum, value)); }
    /** Exact closest-point distance for two finite line segments, including interior crossings. */
    private static double segmentDistance(Vec p1,Vec q1,Vec p2,Vec q2){
        double ux=q1.x-p1.x,uy=q1.y-p1.y,uz=q1.z-p1.z, vx=q2.x-p2.x,vy=q2.y-p2.y,vz=q2.z-p2.z, wx=p1.x-p2.x,wy=p1.y-p2.y,wz=p1.z-p2.z;
        double a=ux*ux+uy*uy+uz*uz,b=ux*vx+uy*vy+uz*vz,c=vx*vx+vy*vy+vz*vz,d=ux*wx+uy*wy+uz*wz,e=vx*wx+vy*wy+vz*wz,D=a*c-b*b;
        double sN,sD=D,tN,tD=D; if(D<EPSILON){sN=0;sD=1;tN=e;tD=c;}else{sN=b*e-c*d;tN=a*e-b*d;if(sN<0){sN=0;tN=e;tD=c;}else if(sN>sD){sN=sD;tN=e+b;tD=c;}}
        if(tN<0){tN=0;if(-d<0)sN=0;else if(-d>a)sN=sD;else{sN=-d;sD=a;}}else if(tN>tD){tN=tD;if((-d+b)<0)sN=0;else if((-d+b)>a)sN=sD;else{sN=-d+b;sD=a;}}
        double sc=Math.abs(sN)<EPSILON?0:sN/sD,tc=Math.abs(tN)<EPSILON?0:tN/tD, dx=wx+sc*ux-tc*vx,dy=wy+sc*uy-tc*vy,dz=wz+sc*uz-tc*vz; return Math.sqrt(dx*dx+dy*dy+dz*dz);
    }
    private static List<?> asList(Object value){return value instanceof List<?>?(List<?>)value:Collections.emptyList();}
    private static Object field(Object target,String name)throws Exception{Object value=optionalField(target,name);if(value==null)throw new NoSuchFieldException(name);return value;}
    private static Object optionalField(Object target,String name)throws Exception{for(Class<?> c=target.getClass();c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(NoSuchFieldException miss){}return null;}
    private static Object call(Object target,String name)throws Exception{return call(target,name,new Class<?>[0],new Object[0]);}
    private static Object call(Object target,String name,Class<?> parameter,Object argument)throws Exception{return call(target,name,new Class<?>[]{parameter},new Object[]{argument});}
    private static Object call(Object target,String name,Class<?>[] parameters,Object[] arguments)throws Exception{Method m=target.getClass().getMethod(name,parameters);m.setAccessible(true);return m.invoke(target,arguments);}
    private static long nativePointer(Object target)throws Exception{return ((Number)call(target,"getNativePointer")).longValue();}
    private static double number(Object value,double fallback){return value instanceof Number?((Number)value).doubleValue():fallback;}
    private static Vec vector(Object value)throws Exception{return new Vec(component(value,"x"),component(value,"y"),component(value,"z"));}
    private static Quat quaternion(Object value)throws Exception{return new Quat(component(value,"x"),component(value,"y"),component(value,"z"),component(value,"w")).normalized();}
    private static double component(Object value,String axis)throws Exception { for (String name : new String[]{axis, "get" + axis, "get" + Character.toUpperCase(axis.charAt(0)) + axis.substring(1)}) try { return ((Number)call(value,name)).doubleValue(); } catch (NoSuchMethodException absent) { } throw new NoSuchMethodException("No numeric " + axis + " component on " + value.getClass().getName()); }
    private static boolean finite(double value){return !Double.isNaN(value)&&!Double.isInfinite(value);}
}
