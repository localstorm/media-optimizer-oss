package co.kuznetsov.mediapipe;

import co.kuznetsov.medialib.util.HealthStatus;
import co.kuznetsov.medialib.util.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates audio from narration text using Google Cloud TTS REST API.
 *
 * <p>Renders headline and body separately with different voices:
 * <ul>
 *   <li>Headline: en-US-Chirp3-HD-Aoede (female)</li>
 *   <li>Body: en-US-Chirp3-HD-Fenrir (male)</li>
 * </ul>
 *
 * <p>Body &lt;em&gt;...&lt;/em&gt; tags are converted to SSML prosody emphasis.
 * All chunks are synthesized as LINEAR16 PCM and concatenated seamlessly
 * before a single MP3 encode pass, avoiding inter-chunk audio artifacts.
 * Headline and body are separated by a 500ms silence.
 */
public class TtsGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(TtsGenerator.class);

    private static final String TTS_URL =
            "https://texttospeech.googleapis.com/v1/text:synthesize?key=";
    private static final String VOICE_HEADLINE = "en-US-Chirp3-HD-Aoede";
    private static final String VOICE_BODY = "en-US-Chirp3-HD-Fenrir";
    private static final String LANGUAGE_CODE = "en-US";
    private static final int SAMPLE_RATE_HZ = 24000;
    // Keep chunks small so each TTS request completes well within HTTP_TIMEOUT.
    // Journey voices are slow (~17 chars/s); 1000 bytes ≈ ~900 chars ≈ ~53s max.
    // Using 800 bytes gives comfortable headroom; SSML path subtracts wrapper overhead.
    private static final int TTS_MAX_BYTES = 800;
    private static final Pattern EM_TAG = Pattern.compile("<em>(.*?)</em>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern ANY_EM_TAG = Pattern.compile("</?em>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[.!?])\\s+");
    private static final long TTS_RETRY_DELAY_MS = 5 * 60 * 1000L;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(120);
    private static final String HEALTH_COMPONENT = "GoogleTTS";

    private final Config config;
    private final HttpClient httpClient;
    private final HealthStatus healthStatus;

    public TtsGenerator(Config config, HealthStatus healthStatus) {
        this.config = config;
        this.healthStatus = healthStatus;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Generate an MP3 audio file from narration text.
     *
     * @param narration  narration text (headline + blank line + body with optional &lt;em&gt; tags)
     * @param outputFile path to write the MP3
     * @return duration of the generated audio in seconds
     * @throws Exception if TTS or ffmpeg fails after all retries
     */
    public long generate(String narration, Path outputFile) throws Exception {
        String[] parts = splitHeadlineAndBody(narration);
        String headline = parts[0];
        String body = parts[1];

        Path tmpDir = Files.createTempDirectory("mediapipe-tts");
        try {
            List<Path> segments = new ArrayList<>();

            LOG.info("Rendering 500ms leading silence...");
            segments.add(generateSilence(tmpDir, "leading", 0.5));

            if (!headline.isBlank()) {
                LOG.info("Rendering headline (voice: {})...", VOICE_HEADLINE);
                segments.addAll(renderPlainText(headline, VOICE_HEADLINE, tmpDir, "headline"));

                LOG.info("Rendering 500ms pause...");
                segments.add(generateSilence(tmpDir));
            }

            LOG.info("Rendering body (voice: {})...", VOICE_BODY);
            segments.addAll(renderBody(body, VOICE_BODY, tmpDir));

            LOG.info("Rendering 2s trailing silence...");
            segments.add(generateSilence(tmpDir, "trailing", 2.0));

            LOG.info("Encoding {} PCM segment(s) to MP3...", segments.size());
            encodeToMp3(segments, outputFile, tmpDir);
        } finally {
            deleteDir(tmpDir);
        }
        return probeAudioDuration(outputFile);
    }

    private long probeAudioDuration(Path audioFile) throws IOException {
        String[] command = {
            "ffprobe", "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            audioFile.toString()
        };
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        try {
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            boolean finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("ffprobe timed out");
            }
            if (process.exitValue() != 0) {
                throw new IOException("ffprobe failed with exit code: " + process.exitValue());
            }
            return Math.round(Double.parseDouble(output));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("ffprobe interrupted", e);
        }
    }

    private List<Path> renderPlainText(
            String text, String voice, Path tmpDir, String prefix) throws Exception {
        String clean = ANY_EM_TAG.matcher(text).replaceAll("");
        List<String> chunks = splitIntoChunks(clean, TTS_MAX_BYTES);
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Path out = tmpDir.resolve(prefix + "-" + i + ".wav");
            Files.write(out, synthesizeText(chunks.get(i), voice));
            files.add(out);
        }
        return files;
    }

    private List<Path> renderBody(String text, String voice, Path tmpDir) throws Exception {
        boolean hasEmTags = ANY_EM_TAG.matcher(text).find();
        List<Path> files = new ArrayList<>();

        if (hasEmTags) {
            LOG.info("Using SSML for body emphasis");
            int ssmlOverhead = "<speak></speak>".getBytes(StandardCharsets.UTF_8).length;
            List<String> chunks = splitIntoChunks(text, TTS_MAX_BYTES - ssmlOverhead);
            for (int i = 0; i < chunks.size(); i++) {
                String ssml = buildSsml(chunks.get(i));
                Path out = tmpDir.resolve("body-" + i + ".wav");
                Files.write(out, synthesizeSsml(ssml, voice));
                files.add(out);
            }
        } else {
            files.addAll(renderPlainText(text, voice, tmpDir, "body"));
        }

        return files;
    }

    private byte[] synthesizeText(String text, String voice) throws Exception {
        String body = "{"
                + "\"input\":{\"text\":" + jsonString(text) + "},"
                + "\"voice\":{\"languageCode\":\"" + LANGUAGE_CODE + "\","
                + "\"name\":\"" + voice + "\"},"
                + "\"audioConfig\":{\"audioEncoding\":\"LINEAR16\","
                + "\"sampleRateHertz\":" + SAMPLE_RATE_HZ + "}"
                + "}";
        return callTtsApi(body);
    }

    private byte[] synthesizeSsml(String ssml, String voice) throws Exception {
        String body = "{"
                + "\"input\":{\"ssml\":" + jsonString(ssml) + "},"
                + "\"voice\":{\"languageCode\":\"" + LANGUAGE_CODE + "\","
                + "\"name\":\"" + voice + "\"},"
                + "\"audioConfig\":{\"audioEncoding\":\"LINEAR16\","
                + "\"sampleRateHertz\":" + SAMPLE_RATE_HZ + "}"
                + "}";
        return callTtsApi(body);
    }

    private byte[] callTtsApi(String requestBody) throws InterruptedException {
        return Retry.untilSuccess(TTS_RETRY_DELAY_MS, () -> {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TTS_URL + config.googleApiKey()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .timeout(HTTP_TIMEOUT)
                    .build();

            HttpResponse<String> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                healthStatus.markDown(HEALTH_COMPONENT, e.getMessage());
                throw e;
            }

            if (response.statusCode() != 200) {
                String detail = "TTS API error " + response.statusCode() + ": " + response.body();
                healthStatus.markDown(HEALTH_COMPONENT, detail);
                throw new IOException(detail);
            }

            String audioContent = extractAudioContent(response.body());
            byte[] decoded = Base64.getDecoder().decode(audioContent);
            healthStatus.markUp(HEALTH_COMPONENT);
            return decoded;
        });
    }

    private String extractAudioContent(String json) throws IOException {
        // Parse "audioContent" field from JSON response without a JSON library
        int idx = json.indexOf("\"audioContent\"");
        if (idx < 0) {
            throw new IOException("No audioContent in TTS response: " + json);
        }
        int start = json.indexOf('"', idx + 14) + 1;
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }

    private String jsonString(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }

    /**
     * Splits text into chunks that fit within maxBytes (UTF-8), breaking only at sentence
     * boundaries. Falls back to splitting at whitespace if a single sentence exceeds the limit.
     */
    private List<String> splitIntoChunks(String text, int maxBytes) {
        List<String> chunks = new ArrayList<>();
        String[] sentences = SENTENCE_BOUNDARY.split(text);
        StringBuilder current = new StringBuilder();

        for (String sentence : sentences) {
            if (sentence.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
                if (current.length() > 0) {
                    chunks.add(current.toString().trim());
                    current.setLength(0);
                }
                chunks.addAll(splitAtWhitespace(sentence, maxBytes));
                continue;
            }
            String separator = current.length() > 0 ? " " : "";
            String candidate = current + separator + sentence;
            if (candidate.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
                chunks.add(current.toString().trim());
                current.setLength(0);
                current.append(sentence);
            } else {
                current.append(separator).append(sentence);
            }
        }

        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }

    private List<String> splitAtWhitespace(String text, int maxBytes) {
        List<String> chunks = new ArrayList<>();
        String[] words = text.split("\\s+");
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            String separator = current.length() > 0 ? " " : "";
            String candidate = current + separator + word;
            if (candidate.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
                if (current.length() > 0) {
                    chunks.add(current.toString().trim());
                    current.setLength(0);
                }
                current.append(word);
            } else {
                current.append(separator).append(word);
            }
        }
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }

    private Path generateSilence(Path tmpDir) throws IOException {
        return generateSilence(tmpDir, "pause", 0.5);
    }

    private Path generateSilence(Path tmpDir, String name, double durationSeconds) throws IOException {
        Path out = tmpDir.resolve(name + ".wav");
        runFfmpeg("-f", "lavfi", "-i", "anullsrc=r=" + SAMPLE_RATE_HZ + ":cl=mono",
                "-t", String.valueOf(durationSeconds), "-acodec", "pcm_s16le",
                out.toString(), "-y");
        return out;
    }

    private void encodeToMp3(List<Path> segments, Path output, Path tmpDir) throws IOException {
        if (segments.size() == 1) {
            runFfmpeg("-i", segments.get(0).toString(),
                    "-acodec", "libmp3lame", "-q:a", "4",
                    output.toString(), "-y");
            return;
        }

        Path concatList = tmpDir.resolve("concat.txt");
        StringBuilder listContent = new StringBuilder();
        for (Path segment : segments) {
            listContent.append("file '").append(segment).append("'\n");
        }
        Files.writeString(concatList, listContent.toString());

        runFfmpeg("-f", "concat", "-safe", "0",
                "-i", concatList.toString(),
                "-acodec", "libmp3lame", "-q:a", "4",
                output.toString(), "-y");
    }

    private String buildSsml(String text) {
        // Normalize whitespace: newlines become spaces (SSML doesn't allow raw newlines)
        String normalized = text.replaceAll("\\s*\\n+\\s*", " ").trim();
        Matcher matcher = EM_TAG.matcher(normalized);
        StringBuilder sb = new StringBuilder("<speak>");
        int last = 0;
        while (matcher.find()) {
            String before = normalized.substring(last, matcher.start());
            // Strip any orphaned/unmatched <em> tags left by chunk boundaries
            sb.append(escapeXml(ANY_EM_TAG.matcher(before).replaceAll("")));
            sb.append("<prosody volume=\"+3dB\">")
              .append(escapeXml(matcher.group(1)))
              .append("</prosody>");
            last = matcher.end();
        }
        String tail = normalized.substring(last);
        sb.append(escapeXml(ANY_EM_TAG.matcher(tail).replaceAll("")));
        sb.append("</speak>");
        return sb.toString();
    }

    private String escapeXml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private void runFfmpeg(String... args) throws IOException {
        String[] command = new String[args.length + 1];
        command[0] = "ffmpeg";
        System.arraycopy(args, 0, command, 1, args.length);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            boolean finished = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("ffmpeg timed out");
            }
            if (process.exitValue() != 0) {
                throw new IOException("ffmpeg failed with exit code: " + process.exitValue());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("ffmpeg interrupted", e);
        }
    }

    private String[] splitHeadlineAndBody(String narration) {
        String[] lines = narration.split("\n", 3);
        if (lines.length >= 3 && lines[1].isBlank()) {
            return new String[]{lines[0].trim(), lines[2].trim()};
        }
        return new String[]{"", narration.trim()};
    }

    private void deleteDir(Path dir) {
        try {
            Files.walk(dir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }
}
