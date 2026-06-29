package co.kuznetsov.medialib.index;

/**
 * Processing state for a channel in the media optimization pipeline.
 */
public enum ProcessingStatus {
    PENDING,
    DOWNLOADING,
    TRANSCRIBING,
    OPTIMIZING,
    SYNTHESIZING,
    UPLOADING,
    DONE,
    PENDING_DIGEST,
    SKIPPED,
    FAILED
}
