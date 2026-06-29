package co.kuznetsov.mediapipe.worker;

import co.kuznetsov.medialib.index.MediaIndex;
import co.kuznetsov.medialib.index.MediaRecord;
import co.kuznetsov.medialib.index.ProcessingStatus;
import co.kuznetsov.medialib.upload.MediaUploader;
import co.kuznetsov.medialib.util.HealthStatus;
import co.kuznetsov.mediapipe.Config;
import co.kuznetsov.mediapipe.MediaOptimizationJob;
import co.kuznetsov.mediapipe.Narrator;
import co.kuznetsov.mediapipe.TtsGenerator;
import co.kuznetsov.mediapipe.model.ChannelSubscription;
import co.kuznetsov.mediapipe.model.DigestDeliveryTime;
import co.kuznetsov.mediapipe.model.OptimizerConfig;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Quartz job that runs at configured digest times. For each digest-mode channel, it:
 * <ol>
 *   <li>Collects all {@code PENDING_DIGEST} records</li>
 *   <li>Concatenates their summary texts and runs a second-pass narration</li>
 *   <li>Synthesizes the digest narration to audio via TTS</li>
 *   <li>Uploads the audio to S3 and inserts a new digest {@code MediaRecord} into the feed table</li>
 *   <li>Moves each source record to the feed table and removes it from processed-media</li>
 * </ol>
 */
@DisallowConcurrentExecution
public final class DigestJob implements Job {

    static final String KEY_INDEX = "index";
    static final String KEY_CHANNEL_IDS = "channelIds";
    static final String KEY_SUBSCRIPTIONS = "subscriptions";
    static final String KEY_CONFIG = "config";
    static final String KEY_PIPELINE_CONFIG = "pipelineConfig";
    static final String KEY_HEALTH_STATUS = "healthStatus";
    static final String KEY_DELIVERY_TIME = "deliveryTime";

    private static final Logger LOG = LoggerFactory.getLogger(DigestJob.class);
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        .withZone(ZoneOffset.UTC);

    @Override
    public void execute(JobExecutionContext context) {
        JobDataMap data = context.getJobDetail().getJobDataMap();
        MediaIndex index = (MediaIndex) data.get(KEY_INDEX);
        @SuppressWarnings("unchecked")
        List<String> channelIds = (List<String>) data.get(KEY_CHANNEL_IDS);
        @SuppressWarnings("unchecked")
        Map<String, ChannelSubscription> subscriptions =
            (Map<String, ChannelSubscription>) data.get(KEY_SUBSCRIPTIONS);
        OptimizerConfig config = (OptimizerConfig) data.get(KEY_CONFIG);
        Config pipelineConfig = (Config) data.get(KEY_PIPELINE_CONFIG);
        HealthStatus healthStatus = (HealthStatus) data.get(KEY_HEALTH_STATUS);

        DigestDeliveryTime deliveryTime = (DigestDeliveryTime) data.get(KEY_DELIVERY_TIME);
        LOG.info("DigestJob started: deliveryTime={}, channels={}", deliveryTime, channelIds.size());

        for (String channelId : channelIds) {
            try {
                digestChannel(channelId, deliveryTime, index, subscriptions, config, pipelineConfig, healthStatus);
            } catch (Exception e) {
                LOG.error("DigestJob failed for channel {}: {}", channelId, e.getMessage(), e);
            }
        }

        LOG.info("DigestJob done");
    }

    private static void digestChannel(
            String channelId,
            DigestDeliveryTime deliveryTime,
            MediaIndex index,
            Map<String, ChannelSubscription> subscriptions,
            OptimizerConfig config,
            Config pipelineConfig,
            HealthStatus healthStatus) throws Exception {
        ChannelSubscription subscription = subscriptions.get(channelId);
        if (!DigestDeliveryTime.channelMatches(
                subscription != null ? subscription.getDigestDeliveryTime() : null, deliveryTime)) {
            LOG.info("Skipping digest for channel {} — channelDeliveryTime={} does not match jobDeliveryTime={}",
                subscription != null ? subscription.getName() : channelId,
                subscription != null ? subscription.getDigestDeliveryTime() : null, deliveryTime);
            return;
        }

        List<MediaRecord> pending = index.queryMediaForChannel(channelId).stream()
            .filter(r -> r.processingStatus() == ProcessingStatus.PENDING_DIGEST)
            .sorted(java.util.Comparator.comparingLong(r -> r.publishedAt() == null ? 0L : r.publishedAt()))
            .collect(Collectors.toList());

        if (pending.isEmpty()) {
            LOG.info("No PENDING_DIGEST records for channel {}", subscription.getName());
            return;
        }

        LOG.info("Digesting {} record(s) for channel {}", pending.size(), subscription.getName());

        String combinedSummary = pending.stream()
            .map(MediaRecord::summaryText)
            .filter(t -> t != null && !t.isBlank())
            .collect(Collectors.joining("\n\n---\n\n"));

        if (combinedSummary.isBlank()) {
            LOG.warn("All PENDING_DIGEST records for channel {} have no summary text — skipping", subscription.getName());
            return;
        }

        String digestPrompt = config.getPromptText(subscription.getDigestPromptRef())
            .orElseThrow(() -> new IllegalStateException(
                "digest_prompt_ref '" + subscription.getDigestPromptRef() + "' not found for channel " + channelId));

        int digestCompressionFactor = config.getSettings().getDigestCompressionFactor();
        MediaOptimizationJob digestJob = MediaOptimizationJob.builder("digest://" + channelId)
            .channelName(subscription.getName())
            .compressionFactor(digestCompressionFactor)
            .promptOverride(digestPrompt)
            .timeOfDay(deliveryTime.name().toLowerCase())
            .build();

        Narrator narrator = new Narrator(pipelineConfig);
        String digestNarration = narrator.narrate(combinedSummary, digestJob);
        LOG.info("Digest narration generated for channel {}: {} chars", subscription.getName(), digestNarration.length());

        String digestVideoId = digestVideoId(pending);
        LOG.info("Digest videoId: {}", digestVideoId);

        Path tmpDir = Files.createTempDirectory("mediapipe-digest");
        String s3Key = null;
        long outputTimeSeconds = 0L;
        try {
            Path audioFile = tmpDir.resolve(digestVideoId + ".mp3");
            TtsGenerator tts = new TtsGenerator(pipelineConfig, healthStatus);
            outputTimeSeconds = tts.generate(digestNarration, audioFile);
            LOG.info("Digest audio: {}KB, {}s", Files.size(audioFile) / 1024, outputTimeSeconds);

            if (pipelineConfig.s3Bucket() != null && !pipelineConfig.s3Bucket().isBlank()) {
                MediaUploader uploader = new MediaUploader(pipelineConfig.s3Bucket(), pipelineConfig.awsRegion());
                s3Key = uploader.upload(audioFile, digestVideoId, System.currentTimeMillis());
            }
        } finally {
            deleteTmpDir(tmpDir);
        }

        long now = Instant.now().toEpochMilli();
        String nowFormatted = Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        long secs = outputTimeSeconds;
        String outputTime = secs > 0 ? String.format("%d:%02d", secs / 60, secs % 60) : null;

        String digestTitle = digestNarration.lines().findFirst().map(String::strip).orElse(null);

        MediaRecord digestRecord = new MediaRecord(
            channelId, subscription.getName(), digestVideoId,
            now, nowFormatted,
            ProcessingStatus.DONE,
            subscription.getSourceType() == null ? null : subscription.getSourceType().name(),
            subscription.getSourceType() == null ? null : subscription.getSourceType().getContentType().name(),
            null, digestTitle, null, outputTime,
            null, digestNarration, null, s3Key,
            null, null, null,
            now, nowFormatted, false, null
        );
        index.insertFeed(digestRecord);
        LOG.info("Inserted digest record {} into feed", digestVideoId);

        for (MediaRecord record : pending) {
            try {
                index.deleteMedia(record.channelId(), record.videoId());
                LOG.info("Deleted consumed digest source {} (channel={})", record.videoId(), subscription.getName());
            } catch (Exception e) {
                LOG.error("Failed to delete digest source {} (channel={}): {}",
                    record.videoId(), subscription.getName(), e.getMessage(), e);
            }
        }
    }

    private static String digestVideoId(List<MediaRecord> records) {
        String input = records.stream()
            .map(MediaRecord::videoId)
            .sorted()
            .collect(Collectors.joining(","));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1")
                .digest(input.getBytes(StandardCharsets.UTF_8));
            return "digest:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 not available", e);
        }
    }

    private static void deleteTmpDir(Path dir) {
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
