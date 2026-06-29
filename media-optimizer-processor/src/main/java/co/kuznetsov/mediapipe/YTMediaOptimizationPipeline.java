package co.kuznetsov.mediapipe;

import co.kuznetsov.medialib.upload.MediaUploader;
import co.kuznetsov.medialib.util.HealthStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Phase 0 media optimization pipeline.
 *
 * <p>Given a {@link MediaOptimizationJob}, produces a narrated audio file:
 * <ol>
 *   <li>Transcribe video (Whisper → captions fallback)</li>
 *   <li>Generate narration (Claude)</li>
 *   <li>Synthesize audio (Google TTS)</li>
 * </ol>
 *
 * <p>Example usage:
 * <pre>{@code
 * Config config = Config.builder()
 *     .anthropicApiKey(System.getenv("ANTHROPIC_API_KEY"))
 *     .googleApiKey(System.getenv("GOOGLE_API_KEY"))
 *     .outputDir(Path.of("/tmp/output"))
 *     .build();
 *
 * MediaOptimizationJob job = MediaOptimizationJob.builder("https://youtube.com/watch?v=...")
 *     .channelName("NetworkChuck")
 *     .targetSummarySeconds(120)
 *     .promptExtra("Skip extended coffee analogies.")
 *     .build();
 *
 * new YTMediaOptimizationPipeline(config).process(job);
 * }</pre>
 */
public class YTMediaOptimizationPipeline {

    private static final Logger LOG = LoggerFactory.getLogger(YTMediaOptimizationPipeline.class);

    private final Config config;
    private final HealthStatus healthStatus;

    public YTMediaOptimizationPipeline(Config config, HealthStatus healthStatus) {
        this.config = config;
        this.healthStatus = healthStatus;
    }

    public Config config() {
        return config;
    }

    public HealthStatus healthStatus() {
        return healthStatus;
    }

    /**
     * Process a single media optimization job.
     *
     * @param job the job to process
     * @return result containing paths to generated narration and audio files
     * @throws Exception on any pipeline failure
     */
    public MediaOptimizationResult process(MediaOptimizationJob job) throws Exception {
        String videoId = extractVideoId(job.videoUrl());
        LOG.info("Processing job: videoId={}, channel={}", videoId, job.channelName());

        Path outputDir = job.outputDir() != null ? job.outputDir() : config.outputDir();
        Files.createDirectories(outputDir);

        // Step 1 — Transcribe
        LOG.info("Step 1: Transcribing...");
        Transcriber transcriber = new Transcriber(config);
        String transcript = transcriber.transcribe(job.videoUrl(), videoId);
        LOG.info("Transcript: {} chars", transcript.length());

        // Step 2 — Narrate
        LOG.info("Step 2: Generating narration...");
        Narrator narrator = new Narrator(config);
        String narration = narrator.narrate(transcript, job);
        int wordCount = narration.split("\\s+").length;
        LOG.info("Narration: {} words", wordCount);

        Path narrationFile = outputDir.resolve(videoId + ".txt");
        Files.writeString(narrationFile, narration);
        LOG.info("Narration saved: {}", narrationFile);

        if (job.narrateOnly()) {
            LOG.info("Narrate-only mode: skipping TTS and S3 upload");
            return new MediaOptimizationResult(videoId, narrationFile, null, null, 0L, transcript, narration);
        }

        // Step 3 — TTS
        LOG.info("Step 3: Generating audio...");
        TtsGenerator tts = new TtsGenerator(config, healthStatus);
        Path audioFile = outputDir.resolve(videoId + ".mp3");
        long outputTimeSeconds = tts.generate(narration, audioFile);
        LOG.info("Audio saved: {} ({}KB, {}s)", audioFile, Files.size(audioFile) / 1024, outputTimeSeconds);

        // Step 4 — Upload to S3
        String s3Key = null;
        if (config.s3Bucket() != null && !config.s3Bucket().isBlank()) {
            LOG.info("Step 4: Uploading to S3...");
            MediaUploader uploader = new MediaUploader(config.s3Bucket(), config.awsRegion());
            s3Key = uploader.upload(audioFile, videoId, System.currentTimeMillis());
        }

        return new MediaOptimizationResult(videoId, narrationFile, audioFile, s3Key, outputTimeSeconds, transcript, narration);
    }

    static String extractVideoId(String url) {
        if (url.contains("v=")) {
            String query = url.substring(url.indexOf("v=") + 2);
            int ampIdx = query.indexOf('&');
            return ampIdx >= 0 ? query.substring(0, ampIdx) : query;
        }
        if (url.contains("youtu.be/")) {
            String path = url.substring(url.lastIndexOf('/') + 1);
            int queryIdx = path.indexOf('?');
            return queryIdx >= 0 ? path.substring(0, queryIdx) : path;
        }
        throw new IllegalArgumentException("Cannot extract video ID from URL: " + url);
    }
}
