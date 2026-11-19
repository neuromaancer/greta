package greta.auxiliary.MeaningMiner;

import greta.core.intentions.Intention;
import greta.core.intentions.IdeationalUnitIntention;
import greta.core.util.CharacterManager;
import greta.core.util.time.SynchPoint;
import java.util.List;
import java.util.Map;

/** Regression checks for punctuated multi-sentence MeaningMiner marker offsets. */
public final class ImageSchemaExtractorMarkerTest {

    private ImageSchemaExtractorMarkerTest() {
    }

    public static void main(String[] args) {
        SentenceMarkerOffsets firstSentence = new SentenceMarkerOffsets(0, 3); // "We agree."
        assertEquals(0, firstSentence.sentenceStart());
        assertEquals(2, firstSentence.sentenceEndInclusive());
        assertEquals(3, firstSentence.nextSentenceStart());
        assertEquals(0, firstSentence.markerForEnglishDependencyIndex(1));
        assertEquals(2, firstSentence.markerForEnglishDependencyIndex(3));

        SentenceMarkerOffsets secondSentence = new SentenceMarkerOffsets(firstSentence.nextSentenceStart(), 4); // "Then reduce red meat."
        assertEquals(3, secondSentence.sentenceStart());
        assertEquals(6, secondSentence.sentenceEndInclusive());
        assertEquals(7, secondSentence.nextSentenceStart());
        assertEquals(3, secondSentence.markerForEnglishDependencyIndex(1));
        assertEquals(6, secondSentence.markerForEnglishDependencyIndex(4));
        assertEquals(3, secondSentence.markerForFrenchTokenIndex(0));
        assertEquals(6, secondSentence.markerForFrenchTokenIndex(3));
        assertIllegalConstruction(-1, 1);
        assertIllegalConstruction(1, -1);
        assertIllegalEnglishIndex(secondSentence, 0);
        assertIllegalFrenchIndex(secondSentence, 4);

        assertExtractorPreservesPunctuatedSentenceSpans();
    }

    private static void assertEquals(int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError("Expected " + expected + " but was " + actual);
        }
    }

    private static void assertIllegalConstruction(int start, int count) {
        try {
            new SentenceMarkerOffsets(start, count);
            throw new AssertionError("Expected invalid marker offset to fail");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void assertIllegalEnglishIndex(SentenceMarkerOffsets offsets, int index) {
        try {
            offsets.markerForEnglishDependencyIndex(index);
            throw new AssertionError("Expected invalid English index to fail");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void assertIllegalFrenchIndex(SentenceMarkerOffsets offsets, int index) {
        try {
            offsets.markerForFrenchTokenIndex(index);
            throw new AssertionError("Expected invalid French index to fail");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void assertExtractorPreservesPunctuatedSentenceSpans() {
        String bml = "<bml id=\"marker-test\"><speech id=\"s1\" language=\"english\" start=\"0.0\" text=\"\">"
                + "<tm id=\"tm0\"/>I <tm id=\"tm1\"/>eat <tm id=\"tm2\"/>meat <tm id=\"tm3\"/>. "
                + "<tm id=\"tm4\"/>We <tm id=\"tm5\"/>reduce <tm id=\"tm6\"/>red <tm id=\"tm7\"/>meat <tm id=\"tm8\"/>.</speech></bml>";
        try {
            ImageSchemaExtractor extractor = new ImageSchemaExtractor(CharacterManager.getStaticInstance());
            List<Intention> intentions = extractor.processText_2(bml);
            assertIdeationalUnit(intentionById(intentions, "id_0"), "s1:tm0-0.2", "s1:tm3-0.2");
            assertIdeationalUnit(intentionById(intentions, "id_1"), "s1:tm4-0.2", "s1:tm8-0.2");
            assertSchemaIndexes(extractor.getSchemaTokenIndexes());
        } catch (Exception exception) {
            throw new AssertionError("MeaningMiner multi-sentence extraction failed", exception);
        }
    }

    private static Intention intentionById(List<Intention> intentions, String id) {
        for (Intention intention : intentions) {
            if (id.equals(intention.getId())) {
                return intention;
            }
        }
        throw new AssertionError("Missing ideational unit " + id);
    }

    private static void assertIdeationalUnit(Intention intention, String expectedStart, String expectedEnd) {
        if (!(intention instanceof IdeationalUnitIntention)) {
            throw new AssertionError("Expected an ideational unit but got " + intention.getClass().getName());
        }
        assertEquals(expectedStart, reference(intention.getStart().getReferences().get(0)));
        assertEquals(expectedEnd, reference(intention.getEnd().getReferences().get(0)));
    }

    private static void assertSchemaIndexes(Map<String, Integer> indexes) {
        for (Integer index : indexes.values()) {
            if (index == null || index < 0 || index > 8) {
                throw new AssertionError("Schema marker is outside the input: " + index);
            }
        }
        if (!indexes.containsValue(2) || !indexes.containsValue(7)) {
            throw new AssertionError("Expected schemas anchored to meat in both sentences: " + indexes);
        }
    }

    private static String reference(SynchPoint synchPoint) {
        return synchPoint.getTargetName() + (synchPoint.getOffset() < 0 ? synchPoint.getOffset() : "");
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + " but was " + actual);
        }
    }
}
