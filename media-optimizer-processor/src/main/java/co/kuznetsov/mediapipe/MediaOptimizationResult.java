package co.kuznetsov.mediapipe;

import java.nio.file.Path;

/**
 * Result of a completed {@link MediaOptimizationPipeline} run.
 */
public record MediaOptimizationResult(
        String videoId,
        Path narrationFile,
        Path audioFile,
        String s3Key,
        long outputTimeSeconds,
        String transcript,
        String summaryText
) {
    /** Sentinel returned by the narrator when the source content has no usable text. */
    public static final String EMPTY_MESSAGE = "EMPTY_MESSAGE";
}
