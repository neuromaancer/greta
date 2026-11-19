import greta.core.intentions.BasicIntention;
import greta.core.intentions.Intention;
import greta.core.util.time.TimeMarker;
import java.util.Arrays;
import java.util.Collections;

/** Headless checks for sparse-only selector gating and probability bounds. */
public final class MeaningMinerSparseActivationSelectorTest {
    public static void main(String[] args) {
        Intention schema = intention("imageschema", "object", "schema");
        Intention speech = intention("speech", "", "speech");
        MeaningMinerSparseActivationSelector.Gate gate = new MeaningMinerSparseActivationSelector.Gate();
        gate.activate("patient-a", "event-1", Arrays.<Object>asList(schema, speech));
        require(gate.accepts(schema), "accept active admitted image schema");
        require(!gate.accepts(speech), "do not boost admitted speech");
        require(!gate.accepts(intention("imageschema", "object", "other")), "do not boost unadmitted schema");
        gate.clear();
        require(!gate.accepts(schema), "closed gate is inert");
        require(MeaningMinerSparseActivationSelector.effectiveProbability(.95) == .97, "raise base by two points");
        require(MeaningMinerSparseActivationSelector.effectiveProbability(.99) == .995, "cap boosted activation");
        require(MeaningMinerSparseActivationSelector.effectiveProbability(.997) == .997, "never lower baseline activation");
        require(Double.isNaN(MeaningMinerSparseActivationSelector.effectiveProbability(Double.NaN)), "preserve invalid value for stock handling");
        System.out.println("PASS sparse_activation_gate probability_bounds");
    }

    private static Intention intention(String name, String type, String id) {
        return new BasicIntention(name, type, id, new TimeMarker("start", 0), new TimeMarker("end", 1));
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
