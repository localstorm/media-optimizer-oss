package co.kuznetsov.mediapipe.worker;

import co.kuznetsov.medialib.index.ChannelRecord;
import co.kuznetsov.medialib.index.MediaIndex;
import co.kuznetsov.medialib.index.MediaRecord;
import co.kuznetsov.medialib.index.ProcessingStatus;
import co.kuznetsov.mediapipe.MediaOptimizationJob;
import co.kuznetsov.mediapipe.MediaOptimizationResult;
import co.kuznetsov.mediapipe.Narrator;
import co.kuznetsov.mediapipe.TextOptimizationJob;
import co.kuznetsov.mediapipe.TextOptimizationPipeline;
import co.kuznetsov.mediapipe.YTMediaOptimizationPipeline;
import co.kuznetsov.mediapipe.model.ChannelSubscription;
import co.kuznetsov.mediapipe.model.ContentType;
import co.kuznetsov.mediapipe.model.OptimizerConfig;
import co.kuznetsov.mediapipe.model.SourceType;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@DisallowConcurrentExecution
public final class MediaProcessingJob implements Job {

    static final String KEY_INDEX = "index";
    static final String KEY_CHANNEL_IDS = "channelIds";
    static final String KEY_PIPELINE = "pipeline";
    static final String KEY_SUBSCRIPTIONS = "subscriptions";
    static final String KEY_CONFIG = "config";

    private static final Logger LOG = LoggerFactory.getLogger(MediaProcessingJob.class);

    @Override
    public void execute(JobExecutionContext context) {
        JobDataMap data = context.getJobDetail().getJobDataMap();
        MediaIndex index = (MediaIndex) data.get(KEY_INDEX);
        @SuppressWarnings("unchecked")
        List<String> channelIds = (List<String>) data.get(KEY_CHANNEL_IDS);
        YTMediaOptimizationPipeline pipeline = (YTMediaOptimizationPipeline) data.get(KEY_PIPELINE);
        @SuppressWarnings("unchecked")
        Map<String, ChannelSubscription> subscriptions = (Map<String, ChannelSubscription>) data.get(KEY_SUBSCRIPTIONS);
        OptimizerConfig config = (OptimizerConfig) data.get(KEY_CONFIG);

        LOG.info("MediaProcessingJob started: channels={}", channelIds.size());

        List<MediaRecord> allRecords = channelIds.stream()
            .flatMap(channelId -> index.queryMediaForChannel(channelId).stream())
            .sorted(Comparator.comparingLong(r -> r.publishedAt() == null ? 0L : r.publishedAt()))
            .toList();

        int deleted = 0;
        int processed = 0;
        int failed = 0;
        for (MediaRecord record : allRecords) {
            if (record.processingStatus() == ProcessingStatus.SKIPPED) {
                index.deleteMedia(record.channelId(), record.videoId());
                deleted++;
                advanceChannelIfNewer(index, record);
            } else if (record.processingStatus() == ProcessingStatus.PENDING) {
                boolean success = processRecord(index, pipeline, subscriptions, config, record);
                if (success) {
                    processed++;
                } else {
                    failed++;
                }
                advanceChannelIfNewer(index, record);
            }
        }

        LOG.info("MediaProcessingJob done: {} SKIPPED deleted, {} processed, {} failed", deleted, processed, failed);
    }

    private static boolean processRecord(
            MediaIndex index,
            YTMediaOptimizationPipeline pipeline,
            Map<String, ChannelSubscription> subscriptions,
            OptimizerConfig config,
            MediaRecord record) {
        String mediaId = record.videoId();
        String channelId = record.channelId();

        index.upsertMedia(withStatus(record, ProcessingStatus.OPTIMIZING));
        try {
            ChannelSubscription subscription = subscriptions.get(channelId);
            String promptExtra = subscription != null && subscription.getPromptExtraRef() != null
                ? config.getPromptText(subscription.getPromptExtraRef()).orElse(null) : null;
            String promptOverride = subscription != null && subscription.getPromptRef() != null
                ? config.getPromptText(subscription.getPromptRef()).orElse(null) : null;
            boolean digestMode = subscription != null && subscription.isDigestMode();
            int compressionFactor = record.compressionFactor() != null && record.compressionFactor() > 0
                ? record.compressionFactor()
                : (subscription != null && subscription.getCompressionFactor() > 0
                    ? subscription.getCompressionFactor() : 10);

            if (record.sourceType() == null || record.sourceType().isBlank()) {
                throw new IllegalStateException("Record " + mediaId + " has no sourceType");
            }
            SourceType sourceType;
            try {
                sourceType = SourceType.valueOf(record.sourceType());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("Record " + mediaId + " has unrecognized sourceType: " + record.sourceType());
            }
            boolean isTextSource = sourceType.getContentType() == ContentType.TEXT;

            MediaOptimizationResult result;
            if (isTextSource) {
                if (record.transcriptText() == null || record.transcriptText().isBlank()) {
                    throw new IllegalStateException(
                        "Text record " + mediaId + " (sourceType=" + record.sourceType() + ") has no transcriptText");
                }
                LOG.info("Processing text record {} (channel={} sourceType={})", mediaId, record.channelName(), record.sourceType());
                TextOptimizationJob job = TextOptimizationJob.builder(mediaId, record.transcriptText())
                    .channelName(record.channelName() != null ? record.channelName() : "unknown")
                    .compressionFactor(compressionFactor)
                    .promptExtra(promptExtra)
                    .promptOverride(promptOverride)
                    .narrateOnly(digestMode)
                    .build();
                TextOptimizationPipeline textPipeline = new TextOptimizationPipeline(pipeline.config(), pipeline.healthStatus());
                result = textPipeline.process(job);
            } else {
                LOG.info("Processing video {} (channel={})", mediaId, record.channelName());
                String videoUrl = toVideoUrl(record.sourceType(), mediaId);
                MediaOptimizationJob job = MediaOptimizationJob.builder(videoUrl)
                    .channelName(record.channelName() != null ? record.channelName() : "unknown")
                    .compressionFactor(compressionFactor)
                    .promptExtra(promptExtra)
                    .promptOverride(promptOverride)
                    .narrateOnly(digestMode)
                    .build();
                result = pipeline.process(job);
            }

            if (MediaOptimizationResult.EMPTY_MESSAGE.equals(result.summaryText())) {
                LOG.info("Record {} produced EMPTY_MESSAGE summary — marking SKIPPED and deleting", mediaId);
                index.upsertMedia(withStatus(record, ProcessingStatus.SKIPPED));
                index.deleteMedia(record.channelId(), mediaId);
                advanceChannelIfNewer(index, record);
                return true;
            }

            String generatedTitle = null;
            boolean titleMissing = record.outputTitle() == null || record.outputTitle().isBlank();
            if (titleMissing && config.getSettings() != null
                    && config.getSettings().getTitlePromptRef() != null) {
                String titlePromptText = config.getPromptText(
                        config.getSettings().getTitlePromptRef()).orElse(null);
                if (titlePromptText != null) {
                    LOG.info("Generating title for record {} using prompt_ref={}",
                            mediaId, config.getSettings().getTitlePromptRef());
                    Narrator narrator = new Narrator(pipeline.config());
                    generatedTitle = narrator.generateTitle(titlePromptText, result.summaryText());
                    LOG.info("Generated title for record {}: '{}'", mediaId, generatedTitle);
                }
            }

            ProcessingStatus finalStatus = digestMode ? ProcessingStatus.PENDING_DIGEST : ProcessingStatus.DONE;
            MediaRecord done = withResult(record, result, finalStatus, generatedTitle);
            index.upsertMedia(done);
            LOG.info("Processed record {}: channel={} status={}", mediaId, record.channelName(), finalStatus);

            if (!digestMode) {
                index.moveToFeed(done);
                LOG.info("Moved record {} to feed (digest_mode=false)", mediaId);
            }
            deleteOutputFiles(result);
            return true;
        } catch (Exception e) {
            LOG.error("Failed to process record {} (channel={}): {}", mediaId, record.channelName(), e.getMessage(), e);
            index.upsertMedia(withStatus(record, ProcessingStatus.FAILED));
            return false;
        }
    }

    private static String toVideoUrl(String sourceType, String videoId) {
        if ("YOUTUBE".equals(sourceType)) {
            return "https://www.youtube.com/watch?v=" + videoId;
        }
        throw new IllegalArgumentException("Cannot build video URL for unsupported sourceType: " + sourceType);
    }

    private static MediaRecord withStatus(MediaRecord r, ProcessingStatus status) {
        return new MediaRecord(
            r.channelId(), r.channelName(), r.videoId(),
            r.publishedAt(), r.publishedAtFormatted(),
            status,
            r.sourceType(), r.contentType(),
            r.inputTitle(), r.outputTitle(), r.inputTimeSeconds(), r.outputTime(),
            r.transcriptText(), r.summaryText(), r.inputFilePath(), r.outputAudioUrl(),
            r.downloadTime(), r.uploadTime(), r.processingTime(),
            r.createdAt(), r.createdAtFormatted(), r.isPlayed(), r.compressionFactor()
        );
    }

    private static void deleteOutputFiles(MediaOptimizationResult result) {
        deleteIfExists(result.narrationFile());
        deleteIfExists(result.audioFile());
    }

    private static void deleteIfExists(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOG.warn("Failed to delete output file {}: {}", path, e.getMessage());
        }
    }

    private static MediaRecord withResult(
            MediaRecord r, MediaOptimizationResult result, ProcessingStatus status, String generatedTitle) {
        long secs = result.outputTimeSeconds();
        String outputTime = secs > 0 ? String.format("%d:%02d", secs / 60, secs % 60) : null;
        String outputTitle = (r.outputTitle() != null && !r.outputTitle().isBlank())
                ? r.outputTitle() : generatedTitle;
        return new MediaRecord(
            r.channelId(), r.channelName(), r.videoId(),
            r.publishedAt(), r.publishedAtFormatted(),
            status,
            r.sourceType(), r.contentType(),
            r.inputTitle(), outputTitle, r.inputTimeSeconds(), outputTime,
            result.transcript(), result.summaryText(), null, result.s3Key(),
            r.downloadTime(), r.uploadTime(), r.processingTime(),
            r.createdAt(), r.createdAtFormatted(), r.isPlayed(), r.compressionFactor()
        );
    }

    private static void advanceChannelIfNewer(MediaIndex index, MediaRecord record) {
        String channelId = record.channelId();
        long recordPublishedAt = record.publishedAt() == null ? 0L : record.publishedAt();
        ChannelRecord channel = index.getChannel(channelId).orElse(null);
        long currentLatest = 0L;
        if (channel != null && channel.lastVideoPublishedAt() != null) {
            currentLatest = Long.parseLong(channel.lastVideoPublishedAt());
        }
        if (recordPublishedAt > currentLatest) {
            index.upsertChannel(new ChannelRecord(
                channelId,
                record.channelName(),
                record.videoId(),
                null, null, null,
                String.valueOf(recordPublishedAt)
            ));
            LOG.debug("Advanced lastVideoId={} lastVideoPublishedAt={} for channel {}",
                record.videoId(), recordPublishedAt, channelId);
        }
    }
}
