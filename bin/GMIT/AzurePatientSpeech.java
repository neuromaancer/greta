import greta.core.behaviorrealizer.ClientPhoneme;
import greta.core.util.audio.Audio;
import greta.core.util.speech.Phoneme;
import greta.core.util.speech.Phoneme.PhonemeType;
import greta.core.util.speech.Speech;
import greta.core.util.speech.TTS;
import greta.core.util.time.TimeMarker;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** B-only Azure Speech adapter for the GMIT automatic replay entry point. */
final class AzurePatientSpeech implements TTS {
    private static final String VOICE = "en-US-JennyNeural";
    private static final int SAMPLE_RATE = 48000;
    private static final long TICKS_PER_SECOND = 10000000L;
    private static final long MAX_TICKS = 15 * TICKS_PER_SECOND;
    // Same Azure viseme representatives as Greta's existing AzureTTS module.
    private static final String[] IPA = {
        "sil", "æ", "ɑ", "ɔ", "ɛ", "ɝ", "j", "w", "o", "aʊ", "ɔɪ",
        "aɪ", "h", "ɹ", "l", "s", "ʃ", "ð", "f", "d", "k", "p"
    };
    private final Path python;
    private final Path worker;
    private final Path output;
    private final ClientPhoneme phonemeMap = new ClientPhoneme();
    private Speech speech;
    private Audio audio;
    private List<Phoneme> phonemes;
    private Map<String, Long> bookmarks;
    private long durationTicks;
    private long trailingSilenceTicks;
    private int requests;

    AzurePatientSpeech(String python, Path output) {
        this.python = Paths.get(python);
        this.output = output;
        worker = Paths.get("..", "integrations", "gmit", "azure_speech.py").toAbsolutePath().normalize();
        if (!this.python.isAbsolute() || !Files.isRegularFile(this.python) || !Files.isRegularFile(worker))
            throw new IllegalArgumentException("Azure replay requires an absolute Python path and the deployed worker");
    }

    @Override public synchronized void setSpeech(Speech speech) {
        if (this.speech == speech) return;
        this.speech = speech;
        audio = null;
        phonemes = null;
        bookmarks = null;
    }

    @Override public synchronized void compute(boolean temporize, boolean withAudio, boolean withPhonemes) {
        if (!temporize && !withAudio && !withPhonemes) return;
        try {
            if (audio == null) synthesize();
            if (temporize) applyBookmarks();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Azure Speech synthesis interrupted", interrupted);
        } catch (Exception failure) {
            throw new IllegalStateException("Azure Speech synthesis failed for patient B", failure);
        }
    }

    @Override public synchronized List<Phoneme> getPhonemes() { return phonemes; }
    @Override public synchronized Audio getAudio() { return audio; }
    @Override public boolean isInterruptionReactionSupported() { return false; }

    private void synthesize() throws Exception {
        Path directory = Files.createDirectory(output.resolve(String.format("azure-b-%03d", ++requests)));
        Path ssml = directory.resolve("input.ssml");
        Path wav = directory.resolve("speech.wav");
        Path timing = directory.resolve("timing.xml");
        // Reuse Greta's speech serialization so MeaningMiner token anchors and
        // spoken text keep their existing order. Azure uses named bookmarks.
        String document = speech.toSSML().toString(false)
                .replaceFirst("<prosody[^>]*>", "<voice name=\"" + VOICE + "\">")
                .replace("</prosody>", "</voice>")
                .replace("<mark name=", "<bookmark mark=");
        Files.write(ssml, document.getBytes(StandardCharsets.UTF_8));
        Process process = new ProcessBuilder(python.toString(), worker.toString(), ssml.toAbsolutePath().toString(),
                wav.toAbsolutePath().toString(), timing.toAbsolutePath().toString())
                .redirectErrorStream(true).redirectOutput(directory.resolve("worker.log").toFile()).start();
        try {
            if (!process.waitFor(25, TimeUnit.SECONDS))
                throw new IOException("Azure Speech worker timed out");
            if (process.exitValue() != 0) throw new IOException("Azure Speech worker failed; see " + directory.getFileName());
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                if (!process.waitFor(5, TimeUnit.SECONDS))
                    throw new IOException("Azure Speech worker did not exit after termination");
            }
        }
        readResult(wav, timing);
        System.out.println("GMIT_AZURE_TTS synthesized actor=patient-b voice=" + VOICE + " request=" + requests
                + " sample_rate=" + SAMPLE_RATE + " duration_ticks=" + durationTicks
                + " phonemes=" + phonemes.size() + " bookmarks=" + bookmarks.size());
    }

    private void readResult(Path wav, Path timing) throws Exception {
        DocumentBuilderFactory xml = DocumentBuilderFactory.newInstance();
        xml.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        xml.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        xml.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Element root = xml.newDocumentBuilder().parse(timing.toFile()).getDocumentElement();
        if (!"azure-speech".equals(root.getTagName()) || !VOICE.equals(root.getAttribute("voice"))
                || !Integer.toString(SAMPLE_RATE).equals(root.getAttribute("sample-rate")))
            throw new IOException("Unexpected Azure Speech timing metadata");
        long duration = Long.parseLong(root.getAttribute("duration-ticks"));
        if (duration <= 0 || duration > MAX_TICKS) throw new IOException("Azure speech duration is out of bounds");
        List<Phoneme> segments = new ArrayList<>();
        Map<String, Long> markers = new HashMap<>();
        long previous = 0;
        long silence = -1;
        PhonemeType previousType = PhonemeType.pause;
        boolean hasSpeechViseme = false;
        for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!(child instanceof Element)) continue;
            Element event = (Element) child;
            long ticks = Long.parseLong(event.getAttribute("ticks"));
            if (ticks < 0 || ticks > duration) throw new IOException("Azure timing is outside the audio");
            String id = event.getAttribute("id");
            if ("bookmark".equals(event.getTagName())) {
                if (id.isEmpty() || markers.put(id, ticks) != null) throw new IOException("Invalid or duplicate Azure bookmark");
            } else if ("viseme".equals(event.getTagName())) {
                int index = Integer.parseInt(id);
                if (ticks < previous || index < 0 || index >= IPA.length) throw new IOException("Invalid Azure viseme");
                appendSegment(segments, previousType, ticks - previous);
                previousType = phonemeMap.convertIPAPhoneme2GretaPhoneme(IPA[index]);
                if (previousType == null) throw new IOException("Unmapped Azure viseme " + index);
                if (previousType == PhonemeType.h) previousType = PhonemeType.a;
                previous = ticks;
                hasSpeechViseme |= index != 0 && ticks < duration;
                if (index != 0) silence = -1;
                else if (hasSpeechViseme && silence < 0) silence = ticks;
            } else throw new IOException("Unknown Azure timing event");
        }
        appendSegment(segments, previousType, duration - previous);
        if (!hasSpeechViseme || segments.size() < 3) throw new IOException("Azure speech has insufficient lip timing");
        try (AudioInputStream input = AudioSystem.getAudioInputStream(wav.toFile())) {
            AudioFormat format = input.getFormat();
            long frames = input.getFrameLength();
            if (!AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding()) || format.getSampleRate() != SAMPLE_RATE
                    || format.getSampleSizeInBits() != 16 || format.getChannels() != 1 || format.getFrameSize() != 2
                    || format.isBigEndian() || frames <= 0 || frames > 15L * SAMPLE_RATE
                    || Math.abs(duration - frames * TICKS_PER_SECOND / SAMPLE_RATE) > 1)
                throw new IOException("Azure WAV and timing metadata do not match");
            ByteArrayOutputStream pcm = new ByteArrayOutputStream((int) frames * 2);
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1;) {
                if (pcm.size() + count > frames * 2) throw new IOException("Azure WAV exceeds its frame length");
                pcm.write(buffer, 0, count);
            }
            if (pcm.size() != frames * 2) throw new IOException("Azure WAV is incomplete");
            audio = new Audio(format, pcm.toByteArray());
        }
        phonemes = segments;
        bookmarks = markers;
        durationTicks = duration;
        trailingSilenceTicks = silence;
    }

    private static void appendSegment(List<Phoneme> phonemes, PhonemeType type, long ticks) {
        if (ticks > 0) phonemes.add(new Phoneme(type, ticks / (double) TICKS_PER_SECOND));
    }

    private void applyBookmarks() throws IOException {
        TimeMarker start = speech.getStart();
        TimeMarker end = speech.getEnd();
        // Azure may omit consecutive bookmarks after the final spoken word.
        // Only markers in the punctuation/whitespace suffix can share the
        // measured start of trailing silence. Interior anchors are required.
        Set<String> suffix = new HashSet<>();
        List<Object> elements = speech.getSpeechElements();
        for (int i = elements.size() - 1; i >= 0; i--) {
            Object element = elements.get(i);
            if (element instanceof TimeMarker) suffix.add(((TimeMarker) element).getName());
            else if (!(element instanceof String) || ((String) element).codePoints().anyMatch(Character::isLetterOrDigit)) break;
        }
        int recovered = 0;
        for (TimeMarker marker : speech.getTimeMarkers()) {
            if (marker == start || marker == end || bookmarks.containsKey(marker.getName())) continue;
            if (trailingSilenceTicks < 0 || !suffix.contains(marker.getName()))
                throw new IOException("Azure Speech omitted Greta's interior marker " + marker.getName());
            bookmarks.put(marker.getName(), trailingSilenceTicks);
            recovered++;
        }
        if (!start.isConcretized()) start.setValue(0);
        long previous = 0;
        for (TimeMarker marker : speech.getTimeMarkers()) {
            if (marker == start || marker == end) continue;
            long ticks = bookmarks.get(marker.getName());
            if (ticks < previous) throw new IOException("Azure bookmark times are out of speech order");
            marker.setValue(start.getValue() + ticks / (double) TICKS_PER_SECOND);
            previous = ticks;
        }
        end.setValue(start.getValue() + durationTicks / (double) TICKS_PER_SECOND);
        System.out.println("GMIT_AZURE_TTS anchors actor=patient-b request=" + requests
                + " recovered_suffix=" + recovered + " trailing_silence_ticks=" + trailingSilenceTicks);
    }
}
