package co.kuznetsov.mediapipe;

import co.kuznetsov.medialib.upload.MediaUploader;
import co.kuznetsov.medialib.util.HealthStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Text optimization pipeline.
 *
 * <p>Given a {@link TextOptimizationJob}, produces a narrated audio file:
 * <ol>
 *   <li>Generate narration from raw text (Claude)</li>
 *   <li>Synthesize audio (Google TTS)</li>
 * </ol>
 *
 * <p>No transcription step — input text is used directly as the transcript.
 */
public class TextOptimizationPipeline {

    private static final Logger LOG = LoggerFactory.getLogger(TextOptimizationPipeline.class);

    private final Config config;
    private final HealthStatus healthStatus;

    public TextOptimizationPipeline(Config config, HealthStatus healthStatus) {
        this.config = config;
        this.healthStatus = healthStatus;
    }

    /**
     * Process a single text optimization job.
     *
     * @param job the job to process
     * @return result containing paths to generated narration and audio files
     * @throws Exception on any pipeline failure
     */
    public MediaOptimizationResult process(TextOptimizationJob job) throws Exception {
        String mediaId = job.mediaId();
        LOG.info("Processing text job: mediaId={}, channel={}", mediaId, job.channelName());

        Path outputDir = job.outputDir() != null ? job.outputDir() : config.outputDir();
        Files.createDirectories(outputDir);

        // Step 1 — Narrate
        LOG.info("Step 1: Generating narration...");
        Narrator narrator = new Narrator(config);
        String narration = narrator.narrate(job);
        int wordCount = narration.split("\\s+").length;
        LOG.info("Narration: {} words", wordCount);

        Path narrationFile = outputDir.resolve(mediaId + ".txt");
        Files.writeString(narrationFile, narration);
        LOG.info("Narration saved: {}", narrationFile);

        if (job.narrateOnly()) {
            LOG.info("Narrate-only mode: skipping TTS and S3 upload");
            return new MediaOptimizationResult(mediaId, narrationFile, null, null, 0L, job.text(), narration);
        }

        // Step 2 — TTS
        LOG.info("Step 2: Generating audio...");
        TtsGenerator tts = new TtsGenerator(config, healthStatus);
        Path audioFile = outputDir.resolve(mediaId + ".mp3");
        long outputTimeSeconds = tts.generate(narration, audioFile);
        LOG.info("Audio saved: {} ({}KB, {}s)", audioFile, Files.size(audioFile) / 1024, outputTimeSeconds);

        // Step 3 — Upload to S3
        String s3Key = null;
        if (config.s3Bucket() != null && !config.s3Bucket().isBlank()) {
            LOG.info("Step 3: Uploading to S3...");
            MediaUploader uploader = new MediaUploader(config.s3Bucket(), config.awsRegion());
            s3Key = uploader.upload(audioFile, mediaId, System.currentTimeMillis());
        }

        return new MediaOptimizationResult(mediaId, narrationFile, audioFile, s3Key, outputTimeSeconds, job.text(), narration);
    }
}
