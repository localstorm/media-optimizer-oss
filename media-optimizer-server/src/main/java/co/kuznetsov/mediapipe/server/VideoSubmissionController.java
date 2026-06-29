package co.kuznetsov.mediapipe.server;

import co.kuznetsov.medialib.index.ChannelRecord;
import co.kuznetsov.medialib.index.MediaIndex;
import co.kuznetsov.medialib.index.MediaRecord;
import co.kuznetsov.medialib.index.ProcessingStatus;
import co.kuznetsov.mediapipe.model.ChannelSubscription;
import co.kuznetsov.mediapipe.model.ChannelVideo;
import co.kuznetsov.mediapipe.model.OptimizerConfig;
import co.kuznetsov.mediapipe.model.SourceType;
import co.kuznetsov.mediapipe.worker.ChannelVideoFetcher;
import co.kuznetsov.mediapipe.worker.ConfigHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api")
public class VideoSubmissionController {

    private static final Logger LOG = LoggerFactory.getLogger(VideoSubmissionController.class);
    private static final AtomicLong LAST_UUID7_MS = new AtomicLong(0);

    private final MediaIndex mediaIndex;
    private final ChannelVideoFetcher contentFetcher;
    private final ConfigHolder configHolder;

    public VideoSubmissionController(MediaIndex mediaIndex, ChannelVideoFetcher contentFetcher,
            ConfigHolder configHolder) {
        this.mediaIndex = mediaIndex;
        this.contentFetcher = contentFetcher;
        this.configHolder = configHolder;
    }

    public record SubmitVideoRequest(SourceType sourceType, String videoId, Integer compressionFactor) { }

    public record SubmitVideoResponse(String channelId, String videoId, String status) { }

    public record SubmitTextRequest(String channelName, String title, String text, Integer compressionFactor) { }

    // UUID v7: 48-bit Unix timestamp ms | 4-bit version (0x7) | 12-bit seq | 2-bit variant | 62-bit random
    // Sequence counter ensures monotonicity within the same millisecond.
    private static UUID uuidV7() {
        long ms = Instant.now().toEpochMilli();
        long last = LAST_UUID7_MS.get();
        long seq;
        if (ms > last) {
            LAST_UUID7_MS.compareAndSet(last, ms);
            seq = 0;
        } else {
            // same or backward tick — bump the stored value and use it as ms to preserve order
            ms = LAST_UUID7_MS.incrementAndGet();
            seq = ms & 0xFFFL;
        }
        long random1 = (long) (Math.random() * 0xFFFFFFFFFFFFL) & 0xFFFFFFFFFFFFL;
        long msb = (ms << 16) | 0x7000L | (seq & 0xFFFL);
        long lsb = 0x8000000000000000L | random1;
        return new UUID(msb, lsb);
    }

    private static Integer resolveCompressionFactor(Integer apiValue, ChannelSubscription sub, OptimizerConfig config) {
        if (apiValue != null && apiValue > 0) {
            return apiValue;
        }
        if (sub.getFeedsInto() != null) {
            return config.findSubscriptionById(sub.getFeedsInto())
                .map(ChannelSubscription::getCompressionFactor)
                .filter(f -> f > 0)
                .orElse(null);
        }
        int channelFactor = sub.getCompressionFactor();
        return channelFactor > 0 ? channelFactor : null;
    }

    @PostMapping("/submit-video")
    public ResponseEntity<SubmitVideoResponse> submitVideo(@RequestBody SubmitVideoRequest request) {
        if (request.sourceType() == null || request.videoId() == null || request.videoId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (request.sourceType() != SourceType.YOUTUBE) {
            LOG.warn("submitVideo: unsupported sourceType={}", request.sourceType());
            return ResponseEntity.badRequest().build();
        }

        String videoId = request.videoId().trim();
        OptimizerConfig config = configHolder.get();
        String submissionChannelName = config.getSettings().getDefaultVideoSubmissionChannel();
        Optional<ChannelSubscription> subOpt = config.findSubscriptionByName(submissionChannelName);
        String channelId = subOpt.map(ChannelSubscription::getChannelId).orElse(submissionChannelName);
        String channelName = subOpt.map(ChannelSubscription::getName).orElse(submissionChannelName);
        LOG.info("submitVideo: sourceType={} videoId={} compressionFactor={} channelId={} channelName={}",
            request.sourceType(), videoId, request.compressionFactor(), channelId, channelName);

        Optional<ChannelVideo> videoOpt = contentFetcher.fetchVideoById(videoId);
        if (videoOpt.isEmpty()) {
            LOG.warn("submitVideo: could not fetch metadata for videoId={}", videoId);
            return ResponseEntity.notFound().build();
        }
        ChannelVideo video = videoOpt.get();

        String lastVideoPublishedAt = String.valueOf(video.getPublishedAt().toEpochMilli());
        mediaIndex.upsertChannel(new ChannelRecord(
            channelId,
            channelName,
            videoId,
            null,
            null,
            null,
            lastVideoPublishedAt
        ));

        MediaRecord mediaRecord = new MediaRecord(
            channelId,
            channelName,
            videoId,
            video.getPublishedAt().toEpochMilli(),
            null,
            ProcessingStatus.PENDING,
            request.sourceType().name(),
            request.sourceType().getContentType().name(),
            video.getTitle(),
            null,
            video.getDurationSeconds(),
            null, null, null, null, null, null, null, null,
            0L, null, false, request.compressionFactor()
        );
        mediaIndex.upsertMedia(mediaRecord);

        LOG.info("submitVideo: stored videoId={} title='{}' in channel='{}' as PENDING", videoId, video.getTitle(), channelId);
        return ResponseEntity.ok(new SubmitVideoResponse(channelId, videoId, ProcessingStatus.PENDING.name()));
    }

    @PostMapping("/submit-text")
    public ResponseEntity<SubmitVideoResponse> submitText(@RequestBody SubmitTextRequest request) {
        if (request.channelName() == null || request.channelName().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (request.text() == null || request.text().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        OptimizerConfig config = configHolder.get();
        Optional<ChannelSubscription> subOpt = config.findSubscriptionByName(request.channelName().trim());
        if (subOpt.isEmpty()) {
            LOG.warn("submitText: channel not found: {}", request.channelName());
            return ResponseEntity.notFound().build();
        }
        ChannelSubscription sub = subOpt.get();
        if (sub.getSourceType() != SourceType.SUBMISSIONS) {
            LOG.warn("submitText: channel '{}' has sourceType={}, expected SUBMISSIONS",
                request.channelName(), sub.getSourceType());
            return ResponseEntity.badRequest().build();
        }

        String channelId = sub.getChannelId();
        String channelName = sub.getName();
        String mediaChannelId = sub.getFeedsInto() != null ? sub.getFeedsInto() : channelId;
        Integer compressionFactor = resolveCompressionFactor(request.compressionFactor(), sub, config);
        String itemId = uuidV7().toString();
        long now = Instant.now().toEpochMilli();

        LOG.info("submitText: channelId={} channelName={} mediaChannelId={} itemId={} title='{}' compressionFactor={}",
            channelId, channelName, mediaChannelId, itemId, request.title(), compressionFactor);

        mediaIndex.upsertChannel(new ChannelRecord(
            channelId, channelName, itemId, null, null, null, String.valueOf(now)
        ));

        MediaRecord mediaRecord = new MediaRecord(
            mediaChannelId,
            channelName,
            itemId,
            now,
            null,
            ProcessingStatus.PENDING,
            SourceType.SUBMISSIONS.name(),
            SourceType.SUBMISSIONS.getContentType().name(),
            request.title().trim(),
            null,
            null,
            null, request.text(), null, null, null, null, null, null,
            0L, null, false, compressionFactor
        );
        mediaIndex.upsertMedia(mediaRecord);

        LOG.info("submitText: stored itemId={} title='{}' in channel='{}' as PENDING",
            itemId, request.title(), channelName);
        return ResponseEntity.ok(new SubmitVideoResponse(channelId, itemId, ProcessingStatus.PENDING.name()));
    }
}
