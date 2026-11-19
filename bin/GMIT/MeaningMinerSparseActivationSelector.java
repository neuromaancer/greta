import greta.core.behaviorplanner.MultimodalSignalSelector;
import greta.core.behaviorplanner.baseline.DynamicLine;
import greta.core.behaviorplanner.lexicon.BehaviorSet;
import greta.core.intentions.Intention;
import greta.core.signals.Signal;
import greta.core.util.CharacterManager;
import greta.core.util.parameter.EngineParameter;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Opt-in selector for already admitted sparse MeaningMiner image schemas.
 * Planner exposes addSelector but not removal; once its gate is cleared this
 * selector is inert and retains no actor or graph object.
 */
final class MeaningMinerSparseActivationSelector extends MultimodalSignalSelector {
    static final double ACTIVATION_INCREMENT = .02;
    static final double ACTIVATION_CAP = .995;

    static final class Gate {
        private final Set<Intention> intentions = Collections.newSetFromMap(new IdentityHashMap<Intention, Boolean>());
        private boolean active;
        private String actor = "";
        private String eventId = "";

        synchronized void activate(String actor, String eventId, List<?> values) {
            clear();
            for (Object value : values) {
                if (value instanceof Intention && "imageschema".equalsIgnoreCase(((Intention)value).getName()))
                    intentions.add((Intention)value);
            }
            this.actor = actor;
            this.eventId = eventId;
            active = !intentions.isEmpty();
        }

        synchronized boolean accepts(Intention intention) {
            return active && intentions.contains(intention);
        }

        synchronized void clear() {
            active = false;
            intentions.clear();
            actor = "";
            eventId = "";
        }

        synchronized String actor() { return actor; }
        synchronized String eventId() { return eventId; }
    }

    private final Gate gate;

    MeaningMinerSparseActivationSelector(Gate gate) {
        if (gate == null) throw new IllegalArgumentException("Missing sparse activation gate");
        this.gate = gate;
    }

    Gate gate() { return gate; }

    @Override
    public boolean acceptIntention(Intention intention, List<Intention> context) {
        return gate.accepts(intention);
    }

    @Override
    public List<Signal> selectFrom(Intention intention, BehaviorSet behaviorSet, DynamicLine dynamicLine,
            List<Signal> existingSignals, CharacterManager manager) {
        // Planner can retain a selector while a bridge closes. Preserve stock
        // behavior if the active scoped turn has already finished.
        if (!gate.accepts(intention)) return super.selectFrom(intention, behaviorSet, dynamicLine, existingSignals, manager);
        EngineParameter activation = dynamicLine.getParameter("gesture", "OAC");
        if (activation == null) return super.selectFrom(intention, behaviorSet, dynamicLine, existingSignals, manager);
        double previous = activation.getValue();
        double base = activation.getValueIn(0, 1);
        double effective = effectiveProbability(base);
        if (effective <= base) return super.selectFrom(intention, behaviorSet, dynamicLine, existingSignals, manager);
        activation.setValue(activation.getMin() + effective * (activation.getMax() - activation.getMin()));
        try {
            System.out.println("GMIT_MEANING_MINER activation_boost actor=" + gate.actor()
                    + " event_id=" + gate.eventId() + " intention_id=" + intention.getId()
                    + " gesture_oac_base=" + base + " gesture_oac_effective=" + effective);
            return super.selectFrom(intention, behaviorSet, dynamicLine, existingSignals, manager);
        } finally {
            activation.setValue(previous);
        }
    }

    static double effectiveProbability(double base) {
        if (!Double.isFinite(base)) return base;
        return Math.max(base, Math.min(ACTIVATION_CAP, base + ACTIVATION_INCREMENT));
    }
}
