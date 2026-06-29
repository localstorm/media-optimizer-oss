package co.kuznetsov.mediapipe.worker;

import co.kuznetsov.medialib.index.MediaIndex;
import co.kuznetsov.medialib.index.MediaRecord;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

@DisallowConcurrentExecution
public final class MediaRetentionJob implements Job {

    static final String KEY_INDEX = "index";
    static final String KEY_CHANNEL_IDS = "channelIds";
    static final String KEY_RETAIN_MILLIS = "retainMillis";

    private static final Logger LOG = LoggerFactory.getLogger(MediaRetentionJob.class);

    @Override
    public void execute(JobExecutionContext context) {
        JobDataMap data = context.getJobDetail().getJobDataMap();
        MediaIndex index = (MediaIndex) data.get(KEY_INDEX);
        @SuppressWarnings("unchecked")
        List<String> channelIds = (List<String>) data.get(KEY_CHANNEL_IDS);
        long retainMillis = data.getLong(KEY_RETAIN_MILLIS);

        long cutoffMillis = Instant.now().toEpochMilli() - retainMillis;
        LOG.info("MediaRetentionJob started: cutoff={}ms, channels={}", cutoffMillis, channelIds.size());

        int total = 0;
        for (String channelId : channelIds) {
            List<MediaRecord> records = index.queryMediaForChannel(channelId);
            for (MediaRecord record : records) {
                if (record.createdAt() < cutoffMillis) {
                    index.deleteMedia(channelId, record.videoId());
                    total++;
                    LOG.debug("Retired media channelId={} videoId={}", channelId, record.videoId());
                }
            }
        }

        LOG.info("MediaRetentionJob done: {} record(s) retired", total);
    }
}
