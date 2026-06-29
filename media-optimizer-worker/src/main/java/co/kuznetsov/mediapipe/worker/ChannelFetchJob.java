package co.kuznetsov.mediapipe.worker;

import co.kuznetsov.medialib.index.ChannelRecord;
import co.kuznetsov.medialib.index.MediaIndex;
import co.kuznetsov.medialib.index.MediaRecord;
import co.kuznetsov.medialib.index.ProcessingStatus;
import co.kuznetsov.mediapipe.model.ChannelSubscription;
import co.kuznetsov.mediapipe.model.ChannelVideo;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@DisallowConcurrentExecution
public final class ChannelFetchJob implements Job {

    static final String KEY_FETCHER = "fetcher";
    static final String KEY_INDEX = "index";
    static final String KEY_SUBSCRIPTION = "subscription";

    private static final Logger LOG = LoggerFactory.getLogger(ChannelFetchJob.class);
    private static final long LOOKBACK_MILLIS = 24L * 60 * 60 * 1000;

    @Override
    public void execute(JobExecutionContext context) {
        JobDataMap data = context.getJobDetail().getJobDataMap();
        ChannelVideoFetcher fetcher = (ChannelVideoFetcher) data.get(KEY_FETCHER);
        MediaIndex index = (MediaIndex) data.get(KEY_INDEX);
        ChannelSubscription subscription = (ChannelSubscription) data.get(KEY_SUBSCRIPTION);

        String channelId = subscription.getChannelId();
        LOG.info("ChannelFetchJob started: {}", subscription.getName());

        try {
            run(fetcher, index, subscription, channelId);
        } catch (Exception e) {
            LOG.error("ChannelFetchJob failed for channel {}, will retry next cycle: {}", subscription.getName(), e.getMessage(), e);
        }
    }

    private void run(ChannelVideoFetcher fetcher, MediaIndex index,
            ChannelSubscription subscription, String channelId) {
        // feedsInto: media records are written under the virtual channel's ID,
        // but cursor tracking always uses the real source channel ID.
        String mediaChannelId = subscription.getFeedsInto() != null ? subscription.getFeedsInto() : channelId;

        Optional<ChannelRecord> channelRecord = index.getChannel(channelId);
        long lastPublishedMillis = channelRecord
            .map(ChannelRecord::lastVideoPublishedAt)
            .filter(s -> s != null)
            .map(s -> {
                try {
                    return Long.parseLong(s);
                } catch (NumberFormatException e) {
                    LOG.warn("Ignoring malformed lastVideoPublishedAt '{}' for channel {}", s, subscription.getName());
                    return 0L;
                }
            })
            .orElse(0L);

        long since = lastPublishedMillis > 0 ? lastPublishedMillis - LOOKBACK_MILLIS
            : Instant.now().toEpochMilli() - LOOKBACK_MILLIS;
        List<ChannelVideo> videos = fetcher.fetchNewVideos(subscription, since);

        int stored = 0;
        int skippedOld = 0;
        String lastVideoId = null;
        String lastVideoPublishedAt = null;
        for (ChannelVideo video : videos) {
            long videoPublishedMillis = video.getPublishedAt().toEpochMilli();
            if (videoPublishedMillis <= lastPublishedMillis) {
                LOG.debug("Skipping old video {} (publishedAt={} <= lastVideoPublishedAt={}) for channel {}",
                    video.getVideoId(), videoPublishedMillis, lastPublishedMillis, channelId);
                skippedOld++;
                continue;
            }
            if (index.getMedia(mediaChannelId, video.getVideoId()).isPresent()) {
                LOG.debug("Skipping already-indexed video {} for channel {}", video.getVideoId(), subscription.getName());
            } else {
                ProcessingStatus status = video.getDurationSeconds() >= subscription.getMinVideoLengthSeconds()
                    ? ProcessingStatus.PENDING
                    : ProcessingStatus.SKIPPED;
                index.upsertMedia(toMediaRecord(mediaChannelId, subscription, video, status));
                stored++;
            }
            lastVideoId = video.getVideoId();
            lastVideoPublishedAt = String.valueOf(video.getPublishedAt().toEpochMilli());
        }

        if (lastVideoId != null) {
            index.upsertChannel(new ChannelRecord(channelId, subscription.getName(), lastVideoId, null, null, null, lastVideoPublishedAt));
            LOG.debug("Updated lastVideoId={} lastVideoPublishedAt={} for channel {}", lastVideoId, lastVideoPublishedAt, subscription.getName());
        }

        LOG.info("ChannelFetchJob done for {}: {} fetched, {} skipped (old), {} newly stored",
            subscription.getName(), videos.size(), skippedOld, stored);
    }

    static String stripTitle(String title) {
        if (title == null) {
            return null;
        }
        // Remove emoji (Unicode blocks: Emoticons, Misc Symbols, Dingbats, Supplemental Symbols, etc.)
        // and anything outside printable ASCII + common extended Latin
        return title
            .replaceAll("[\\p{So}\\p{Cs}\\p{Co}\\p{Cn}]", "") // emojis and special symbols
            .replaceAll("<[^>]*>", "")                          // HTML tags
            .replaceAll("[\\p{Cc}&&[^\t\n\r]]", "")            // control characters (keep tab/newline/CR)
            .strip();
    }

    private static MediaRecord toMediaRecord(String channelId, ChannelSubscription subscription, ChannelVideo video, ProcessingStatus status) {
        return new MediaRecord(
            channelId,
            subscription.getName(),
            video.getVideoId(),
            video.getPublishedAt().toEpochMilli(),
            null,
            status,
            subscription.getSourceType() == null ? null : subscription.getSourceType().name(),
            subscription.getSourceType() == null ? null : subscription.getSourceType().getContentType().name(),
            stripTitle(video.getTitle()),
            null,
            video.getDurationSeconds(),
            null, null, null, null, null, null, null, null, 0L, null, false, null
        );
    }
}
