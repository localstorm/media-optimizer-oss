package co.kuznetsov.mediapipe.worker;

import co.kuznetsov.medialib.index.ChannelRecord;
import co.kuznetsov.medialib.index.MediaIndex;
import co.kuznetsov.medialib.index.MediaRecord;
import co.kuznetsov.medialib.index.ProcessingStatus;
import co.kuznetsov.mediapipe.model.ChannelSubscription;
import co.kuznetsov.mediapipe.model.ChannelText;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@DisallowConcurrentExecution
public final class ChannelTextFetchJob implements Job {

    static final String KEY_FETCHER = "fetcher";
    static final String KEY_INDEX = "index";
    static final String KEY_SUBSCRIPTION = "subscription";
    static final String KEY_TIMEZONE = "timezone";

    private static final Logger LOG = LoggerFactory.getLogger(ChannelTextFetchJob.class);
    private static final long LOOKBACK_MILLIS = 24L * 60 * 60 * 1000;
    private static final DateTimeFormatter HUMAN_DATE_BASE = DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.LONG)
        .withLocale(Locale.ENGLISH);

    @Override
    public void execute(JobExecutionContext context) {
        JobDataMap data = context.getJobDetail().getJobDataMap();
        ChannelTextFetcher fetcher = (ChannelTextFetcher) data.get(KEY_FETCHER);
        MediaIndex index = (MediaIndex) data.get(KEY_INDEX);
        ChannelSubscription subscription = (ChannelSubscription) data.get(KEY_SUBSCRIPTION);
        String timezone = (String) data.get(KEY_TIMEZONE);

        ZoneId zone;
        try {
            zone = timezone != null && !timezone.isBlank() ? ZoneId.of(timezone) : ZoneId.of("UTC");
        } catch (Exception e) {
            LOG.warn("Invalid timezone '{}', falling back to UTC", timezone);
            zone = ZoneId.of("UTC");
        }
        DateTimeFormatter dateFormatter = HUMAN_DATE_BASE.withZone(zone);

        String channelId = subscription.getChannelId();
        LOG.info("ChannelTextFetchJob started: {}", subscription.getName());

        try {
            run(fetcher, index, subscription, channelId, dateFormatter);
        } catch (Exception e) {
            LOG.error("ChannelTextFetchJob failed for channel {}, will retry next cycle: {}", subscription.getName(), e.getMessage(), e);
        }
    }

    private void run(ChannelTextFetcher fetcher, MediaIndex index,
            ChannelSubscription subscription, String channelId, DateTimeFormatter dateFormatter) {
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
        List<ChannelText> texts = fetcher.fetchNewTexts(subscription, since);

        int stored = 0;
        int skippedOld = 0;
        String lastTextId = null;
        String lastPublishedAt = null;
        for (ChannelText text : texts) {
            long publishedMillis = text.getPublishedAt().toEpochMilli();
            if (publishedMillis <= lastPublishedMillis) {
                LOG.debug("Skipping old text {} (publishedAt={} <= lastVideoPublishedAt={}) for channel {}",
                    text.getTextId(), publishedMillis, lastPublishedMillis, channelId);
                skippedOld++;
                continue;
            }
            if (index.getMedia(mediaChannelId, text.getTextId()).isPresent()) {
                LOG.debug("Skipping already-indexed text {} for channel {}", text.getTextId(), subscription.getName());
            } else {
                index.upsertMedia(toMediaRecord(mediaChannelId, subscription, text, dateFormatter));
                stored++;
            }
            lastTextId = text.getTextId();
            lastPublishedAt = String.valueOf(text.getPublishedAt().toEpochMilli());
        }

        if (lastTextId != null) {
            index.upsertChannel(new ChannelRecord(channelId, subscription.getName(), lastTextId, null, null, null, lastPublishedAt));
            LOG.debug("Updated lastTextId={} lastPublishedAt={} for channel {}", lastTextId, lastPublishedAt, subscription.getName());
        }

        LOG.info("ChannelTextFetchJob done for {}: {} fetched, {} skipped (old), {} newly stored",
            subscription.getName(), texts.size(), skippedOld, stored);
    }

    private static MediaRecord toMediaRecord(String channelId, ChannelSubscription subscription,
            ChannelText text, DateTimeFormatter dateFormatter) {
        return new MediaRecord(
            channelId,
            subscription.getName(),
            text.getTextId(),
            text.getPublishedAt().toEpochMilli(),
            null,
            ProcessingStatus.PENDING,
            subscription.getSourceType() == null ? null : subscription.getSourceType().name(),
            subscription.getSourceType() == null ? null : subscription.getSourceType().getContentType().name(),
            ChannelFetchJob.stripTitle(text.getTitle()),
            null,
            text.getDurationSeconds(),
            null, dateFormatter.format(text.getPublishedAt()) + "\n\n" + text.getContent(),
            null, null, null, null, null, null, 0L, null, false, null
        );
    }
}
