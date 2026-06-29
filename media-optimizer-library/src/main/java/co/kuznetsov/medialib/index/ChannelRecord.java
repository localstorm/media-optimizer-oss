package co.kuznetsov.medialib.index;

/**
 * Snapshot of a channel's processing state from the media-optimizer.channels table.
 */
public record ChannelRecord(
        String channelId,
        String channelName,
        String lastVideoId,
        ProcessingStatus processingStatus,
        String lastError,
        String skipReason,
        String lastVideoPublishedAt
) {
}
