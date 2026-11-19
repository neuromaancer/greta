/*
 * This file is part of the auxiliaries of Greta.
 *
 * Greta is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package greta.auxiliary.MeaningMiner;

/** Maintains speech-marker offsets while MeaningMiner processes one sentence at a time. */
final class SentenceMarkerOffsets {

    private final int sentenceStart;
    private final int sentenceTokenCount;

    SentenceMarkerOffsets(int sentenceStart, int sentenceTokenCount) {
        if (sentenceStart < 0 || sentenceTokenCount < 0) {
            throw new IllegalArgumentException("Sentence marker offsets must be non-negative");
        }
        this.sentenceStart = sentenceStart;
        this.sentenceTokenCount = sentenceTokenCount;
    }

    int sentenceStart() {
        return sentenceStart;
    }

    int sentenceEndInclusive() {
        if (sentenceTokenCount == 0) {
            throw new IllegalStateException("An empty sentence has no final word marker");
        }
        return Math.addExact(sentenceStart, sentenceTokenCount - 1);
    }

    /** Returns the first word marker available to the next sentence. */
    int nextSentenceStart() {
        return Math.addExact(sentenceStart, sentenceTokenCount);
    }

    /** Converts Stanford's one-based dependency index into a zero-based speech marker. */
    int markerForEnglishDependencyIndex(int dependencyIndex) {
        if (dependencyIndex < 1 || dependencyIndex > sentenceTokenCount) {
            throw new IllegalArgumentException("English dependency index is outside the sentence");
        }
        return Math.addExact(sentenceStart, dependencyIndex - 1);
    }

    /** Converts the French tokenizer's zero-based token index into a speech marker. */
    int markerForFrenchTokenIndex(int tokenIndex) {
        if (tokenIndex < 0 || tokenIndex >= sentenceTokenCount) {
            throw new IllegalArgumentException("French token index is outside the sentence");
        }
        return Math.addExact(sentenceStart, tokenIndex);
    }
}
