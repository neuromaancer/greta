/** Headless regression checks for deterministic listener-gaze/backchannel timing. */
public final class ListenerGazeTest {
    private ListenerGazeTest() { }

    public static void main(String[] args) {
        ListenerGaze.TimingPlan shortTurn = ListenerGaze.planTiming(
                "facilitator", "patient-a", "patient-b", 1000, 2800, 71L, 0);
        require(shortTurn.listenerFrame == 30, "both listeners begin at Audio+200 ms rounded to 25 fps");
        require(event(shortTurn, "listener-start", "patient-a").start == 30, "first listener onset");
        require(event(shortTurn, "listener-start", "patient-b").start == 30, "second listener onset");
        ListenerGaze.TimingEvent handoff = event(shortTurn, "speaker-handoff", "facilitator");
        require(handoff != null && "patient-b".equals(handoff.target), "short turn hands speaker to actual next actor");
        require(handoff.start + handoff.duration <= shortTurn.endFrame, "handoff ends inside audio interval");

        ListenerGaze.TimingPlan nextInitialPeer = ListenerGaze.planTiming(
                "facilitator", "patient-a", "patient-a", 1000, 4500, 71L, 0);
        require(event(nextInitialPeer, "speaker-midpoint", "facilitator") == null,
                "handoff to initial peer wins over a conflicting midpoint switch");
        require("patient-a".equals(nextInitialPeer.speakerFinalTarget("facilitator")),
                "3.5-second turn ends facing the actual initial-peer next actor");
        require(event(nextInitialPeer, "speaker-handoff", "facilitator") != null,
                "3.5-second initial-peer target receives an explicit bounded handoff");

        ListenerGaze.TimingPlan nextOtherPeer = ListenerGaze.planTiming(
                "facilitator", "patient-a", "patient-b", 1000, 4500, 71L, 0);
        require(event(nextOtherPeer, "speaker-midpoint", "facilitator") != null,
                "feasible midpoint remains when it reaches the actual next actor");
        require("patient-b".equals(nextOtherPeer.speakerFinalTarget("facilitator")),
                "3.5-second turn ends facing the actual other-peer next actor");
        require(event(nextOtherPeer, "speaker-handoff", "facilitator") == null,
                "does not oscillate at the end when midpoint already faces next actor");

        ListenerGaze.TimingEvent scheduledNod = null;
        for (long seed = 0; seed < 64; seed++) {
            ListenerGaze.TimingPlan plan = ListenerGaze.planTiming(
                    "patient-a", "facilitator", "patient-b", 1000, 6000, seed, 0);
            require(plan.nodCount() <= 1, "listener nods never overlap");
            require(plan.nodCount() == 0 || plan.nodCount() == 1, "at most one stochastic nod is scheduled");
            for (ListenerGaze.TimingEvent event : plan.events()) if (event.nod) {
                if (scheduledNod == null) scheduledNod = event;
                require(event.duration * 40 >= 1200 && event.duration * 40 <= 1400,
                        "listener backchannel remains visible for about one second");
                require(event.start >= plan.listenerFrame + 20, "nod waits for listener yaw to settle");
                int deadline = plan.handoffFrame < 0 ? plan.endFrame : plan.handoffFrame;
                require(event.start + event.duration + 2 <= deadline, "nod finishes before interval end/handoff");
            }
        }
        if (Boolean.parseBoolean(System.getProperty("gmit.gaze.backchannel", "true"))) {
            require(scheduledNod != null, "adjacent deterministic seeds include a generated nod for envelope verification");
            int midpoint = scheduledNod.start + scheduledNod.duration / 2;
            double peak = ListenerGaze.nodPitchAt(scheduledNod.start, scheduledNod.duration, scheduledNod.pitch, midpoint);
            require(Math.toDegrees(peak) > 9 && Math.toDegrees(peak) < 11,
                    "the generated nod reaches the configured aggregate pitch target");
            require(ListenerGaze.nodPitchAt(scheduledNod.start, scheduledNod.duration, scheduledNod.pitch, scheduledNod.start) == 0
                            && ListenerGaze.nodPitchAt(scheduledNod.start, scheduledNod.duration, scheduledNod.pitch,
                                    scheduledNod.start + scheduledNod.duration) == 0,
                    "the generated nod starts and ends neutral");
        }
        ListenerGaze.TimingPlan continuation = ListenerGaze.planTiming(
                "patient-a", "facilitator", "patient-a", 1000, 2800, 71L, 0);
        require(event(continuation, "speaker-handoff", "patient-a") == null, "same-actor continuation has no invented handoff");
        ListenerGaze.TimingPlan finalTurn = ListenerGaze.planTiming(
                "patient-a", "facilitator", null, 1000, 2800, 71L, 0);
        require(event(finalTurn, "speaker-handoff", "patient-a") == null, "final turn with unknown next actor has no invented handoff");
        ListenerGaze.TimingPlan repeat = ListenerGaze.planTiming(
                "facilitator", "patient-a", "patient-b", 1000, 2800, 71L, 0);
        samePlan(shortTurn, repeat);
        ListenerGaze.TimingPlan late = ListenerGaze.planTiming(
                "facilitator", "patient-a", "patient-b", 1000, 4500, 71L, 100);
        for (ListenerGaze.TimingEvent event : late.events()) require(event.start >= 101, "late scheduling does not write past frames");
        if (Boolean.parseBoolean(System.getProperty("gmit.gaze.backchannel", "true"))) {
            int nods = 0;
            for (long seed = 0; seed < 100; seed++) nods += ListenerGaze.planTiming(
                    "patient-a", "facilitator", "patient-b", 1000, 6000, seed, 0).nodCount();
            require(nods >= 25 && nods <= 75,
                    "100 adjacent seeds produce a non-degenerate 50% backchannel gate: " + nods);
            java.util.Map<String, Integer> counts = new java.util.HashMap<>();
            counts.put("facilitator", Integer.valueOf(0));
            counts.put("patient-b", Integer.valueOf(2));
            int facilitatorNods = 0;
            for (long seed = 0; seed < 64; seed++) {
                ListenerGaze.TimingPlan fair = ListenerGaze.planTiming(
                        "patient-a", "facilitator", "patient-b", 1000, 6000, seed, 0, counts);
                samePlan(fair, ListenerGaze.planTiming(
                        "patient-a", "facilitator", "patient-b", 1000, 6000, seed, 0, counts));
                for (ListenerGaze.TimingEvent event : fair.events()) if (event.nod) {
                    require("facilitator".equals(event.actor), "less-used facilitator gets the listener opportunity");
                    facilitatorNods++;
                }
            }
            require(facilitatorNods > 0 && facilitatorNods < 64, "fair selection preserves occasional triggering");
            counts.put("facilitator", Integer.valueOf(3));
            for (long seed = 0; seed < 64; seed++) {
                ListenerGaze.TimingPlan fair = ListenerGaze.planTiming(
                        "patient-a", "facilitator", "patient-b", 1000, 6000, seed, 0, counts);
                for (ListenerGaze.TimingEvent event : fair.events()) if (event.nod)
                    require("patient-b".equals(event.actor), "priority follows counts rather than a fixed actor role");
            }
            require(counts.get("facilitator").intValue() == 3 && counts.get("patient-b").intValue() == 2,
                    "planning does not mutate the caller's history snapshot");
        } else {
            for (long seed = 0; seed < 64; seed++) require(ListenerGaze.planTiming(
                    "patient-a", "facilitator", "patient-b", 1000, 6000, seed, 0).nodCount() == 0,
                    "backchannel property disables every nod");
        }
        System.out.println("PASS ListenerGaze timing: simultaneous listener yaw, bounded handoff, exclusive nods");
    }

    private static ListenerGaze.TimingEvent event(ListenerGaze.TimingPlan plan, String kind, String actor) {
        for (ListenerGaze.TimingEvent event : plan.events()) if (kind.equals(event.kind) && actor.equals(event.actor)) return event;
        return null;
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void samePlan(ListenerGaze.TimingPlan first, ListenerGaze.TimingPlan second) {
        require(first.events().size() == second.events().size(), "same seed reproduces event count");
        for (int i = 0; i < first.events().size(); i++) {
            ListenerGaze.TimingEvent left = first.events().get(i), right = second.events().get(i);
            require(left.kind.equals(right.kind) && left.actor.equals(right.actor)
                    && (left.target == null ? right.target == null : left.target.equals(right.target))
                    && left.start == right.start && left.duration == right.duration
                    && Double.doubleToLongBits(left.pitch) == Double.doubleToLongBits(right.pitch),
                    "same seed reproduces complete event plan at index " + i);
        }
    }
}
