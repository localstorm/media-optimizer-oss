package co.kuznetsov.medialib.index;

/**
 * A processed media record from the media-optimizer.processed-media table.
 */
public record MediaRecord(
        String channelId,
        String channelName,
        String videoId,
        Long publishedAt,
        String publishedAtFormatted,
        ProcessingStatus processingStatus,
        String sourceType,
        String contentType,
        String inputTitle,
        String outputTitle,
        Long inputTimeSeconds,
        String outputTime,
        String transcriptText,
        String summaryText,
        String inputFilePath,
        String outputAudioUrl,
        String downloadTime,
        String uploadTime,
        String processingTime,
        long createdAt,
        String createdAtFormatted,
        boolean isPlayed,
        Integer compressionFactor
) {
}
