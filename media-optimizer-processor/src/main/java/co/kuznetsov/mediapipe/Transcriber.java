package co.kuznetsov.mediapipe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Transcribes a YouTube video to plain text.
 *
 * <p>Priority:
 * <ol>
 *   <li>Local Whisper tiny (~1.3 min for 10 min video)</li>
 *   <li>YouTube auto-captions via yt-dlp</li>
 * </ol>
 */
public class Transcriber {

    private static final Logger LOG = LoggerFactory.getLogger(Transcriber.class);

    private static final int PROCESS_TIMEOUT_MINUTES = 240;
    private static final Pattern VTT_TIMESTAMP = Pattern.compile("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3}.*-->.*$");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern SRT_SEQUENCE = Pattern.compile("^\\d+$");
    private static final Pattern WHISPER_TIMESTAMP = Pattern.compile("^\\[\\d{2}:\\d{2}\\.\\d{3} --> \\d{2}:\\d{2}\\.\\d{3}\\].*");

    private final Config config;

    public Transcriber(Config config) {
        this.config = config;
    }

    /**
     * Transcribe a YouTube video URL.
     *
     * @param url     YouTube URL
     * @param videoId extracted video ID
     * @return plain text transcript
     * @throws IOException          on I/O failure
     * @throws InterruptedException if interrupted
     */
    public String transcribe(String url, String videoId) throws IOException, InterruptedException {
        Path tmpDir = config.outputDir().resolve("mediapipe-" + videoId);
        Files.createDirectories(tmpDir);
        try {
            // Attempt 1: local Whisper
            String whisperResult = tryWhisper(url, videoId, tmpDir);
            if (whisperResult != null && !whisperResult.isBlank()) {
                LOG.info("Transcription via Whisper ({} chars)", whisperResult.length());
                return whisperResult;
            }

            // Attempt 2: YouTube captions
            LOG.info("Whisper failed or unavailable, trying YouTube captions...");
            String captionsResult = tryCaptions(url, videoId, tmpDir);
            if (captionsResult != null && !captionsResult.isBlank()) {
                LOG.info("Transcription via captions ({} chars)", captionsResult.length());
                return captionsResult;
            }

            throw new IOException("All transcription methods failed for: " + url);
        } finally {
            deleteDir(tmpDir);
        }
    }

    private String tryWhisper(String url, String videoId, Path tmpDir) {
        try {
            // Download audio
            LOG.info("Downloading audio...");
            int cores = Runtime.getRuntime().availableProcessors();
            int threads = Math.max(1, (cores * config.whisperCpuPct()) / 100);

            runProcess(tmpDir, "yt-dlp",
                    "--extract-audio", "--audio-format", "mp3", "--audio-quality", "5",
                    "--output", tmpDir + "/" + videoId + ".%(ext)s",
                    url);

            Path audioFile = tmpDir.resolve(videoId + ".mp3");
            if (!Files.exists(audioFile)) {
                LOG.warn("Audio file not found after download");
                return null;
            }

            // Run Whisper
            LOG.info("Running Whisper {} ({} threads)...", config.whisperModel(), threads);
            runProcess(tmpDir, "whisper",
                    audioFile.toString(),
                    "--model", config.whisperModel(),
                    "--model_dir", config.modelsDir().toString(),
                    "--language", "en",
                    "--threads", String.valueOf(threads),
                    "--output_format", "txt",
                    "--output_dir", tmpDir.toString());

            Path txtFile = tmpDir.resolve(videoId + ".txt");
            if (!Files.exists(txtFile)) {
                LOG.warn("Whisper output file not found");
                return null;
            }

            return cleanWhisperOutput(Files.readString(txtFile));

        } catch (Exception e) {
            LOG.warn("Whisper transcription failed: {}", e.getMessage());
            return null;
        }
    }

    private String tryCaptions(String url, String videoId, Path tmpDir) {
        try {
            runProcess(tmpDir, "yt-dlp",
                    "--write-auto-sub", "--sub-lang", "en", "--skip-download",
                    "--output", tmpDir + "/" + videoId,
                    url);

            Path vttFile = tmpDir.resolve(videoId + ".en.vtt");
            if (!Files.exists(vttFile)) {
                LOG.warn("Caption file not found");
                return null;
            }

            return parseVtt(Files.readString(vttFile));

        } catch (Exception e) {
            LOG.warn("Caption fetch failed: {}", e.getMessage());
            return null;
        }
    }

    private void runProcess(Path workDir, String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();
        // Drain stdout/stderr on a background thread to prevent pipe-buffer deadlock
        Thread drainer = Thread.ofVirtual().start(() -> {
            try (InputStream is = process.getInputStream()) {
                is.transferTo(java.io.OutputStream.nullOutputStream());
            } catch (IOException ignored) {
            }
        });

        boolean finished = process.waitFor(PROCESS_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        drainer.join(TimeUnit.SECONDS.toMillis(5));

        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Process timed out: " + command[0]);
        }

        if (process.exitValue() != 0) {
            throw new IOException("Process failed (exit " + process.exitValue() + "): " + command[0]);
        }
    }

    private String cleanWhisperOutput(String raw) {
        List<String> lines = new ArrayList<>();
        for (String line : raw.split("\n")) {
            line = line.trim();
            // Strip Whisper timestamp lines like [00:01.000 --> 00:03.000]
            if (WHISPER_TIMESTAMP.matcher(line).matches()) {
                // Extract text after the timestamp
                int bracketEnd = line.indexOf(']');
                if (bracketEnd >= 0 && bracketEnd + 1 < line.length()) {
                    line = line.substring(bracketEnd + 1).trim();
                } else {
                    continue;
                }
            }
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
        return String.join(" ", lines);
    }

    private String parseVtt(String vtt) {
        List<String> lines = new ArrayList<>();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();

        for (String line : vtt.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("WEBVTT") || line.startsWith("NOTE")) {
                continue;
            }
            if (VTT_TIMESTAMP.matcher(line).matches() || SRT_SEQUENCE.matcher(line).matches()) {
                continue;
            }
            // Strip HTML tags
            line = HTML_TAG.matcher(line).replaceAll("");
            line = line.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").trim();
            if (!line.isBlank() && seen.add(line)) {
                lines.add(line);
            }
        }
        return String.join(" ", lines);
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
