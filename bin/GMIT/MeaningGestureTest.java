import java.util.Arrays;

/** Headless bounds and recovery checks for the MeaningMiner tabletop envelope. */
public final class MeaningGestureTest {
    public static void main(String[] args) {
        require(MeaningGesture.create("e", Arrays.asList("PATH"), 10, 69) == null, "short audio rejected");
        require(MeaningGesture.create("e", java.util.Collections.<String>emptyList(), 10, 100) == null, "empty markers rejected");
        MeaningGesture.Plan plan = MeaningGesture.create("event-1", Arrays.asList("PATH", "CONTAINER"), 20, 120);
        require(plan != null && "PATH".equals(plan.schema), "first semantic marker selected");
        require(plan.covers(20) && plan.covers(plan.finishFrame), "bounded interval");
        require(plan.weight(20) == 0 && plan.weight(plan.finishFrame) == 0, "clasp restored at bounds");
        require(plan.weight(40) > .9, "gesture reaches envelope");
        require(plan.expired(plan.finishFrame + 1), "expired after deterministic return");
        // Future source frames must not mutate a plan that will still be applied
        // when the Greta Timer reaches its scheduled peak.
        require(plan.weight(plan.finishFrame + 50) == 0 && plan.weight(40) > .9,
                "future frame sampling cannot erase scheduled peak");
        require(Math.abs(plan.profile.shoulderFlexion) <= 7 && plan.profile.release <= .55, "bounded tabletop profile");
        System.out.println("PASS meaning_gesture_short_turn_marker_selection_future_frame_bounded_envelope_return");
    }

    private static void require(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
