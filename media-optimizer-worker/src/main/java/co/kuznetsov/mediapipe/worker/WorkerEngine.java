package co.kuznetsov.mediapipe.worker;

import co.kuznetsov.medialib.index.MediaIndex;
import co.kuznetsov.medialib.index.MediaRecord;
import co.kuznetsov.medialib.index.ProcessingStatus;
import co.kuznetsov.medialib.util.HealthStatus;
import co.kuznetsov.mediapipe.Config;
import co.kuznetsov.mediapipe.YTMediaOptimizationPipeline;
import co.kuznetsov.mediapipe.model.ApiKeys;
import co.kuznetsov.mediapipe.model.ChannelSubscription;
import co.kuznetsov.mediapipe.model.DigestDeliveryTime;
import co.kuznetsov.mediapipe.model.SourceType;
import co.kuznetsov.mediapipe.model.GlobalSettings;
import co.kuznetsov.mediapipe.model.OptimizerConfig;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.stream.Collectors;

public final class WorkerEngine {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerEngine.class);

    private final ConfigHolder configHolder;
    private final String awsRegion;
    private final String s3Bucket;
    private final MediaIndex index;
    private final HealthStatus healthStatus;
    private Scheduler scheduler;

    public WorkerEngine(
            ConfigHolder configHolder, String awsRegion, String s3Bucket,
            MediaIndex index, HealthStatus healthStatus) {
        this.configHolder = configHolder;
        this.awsRegion = awsRegion;
        this.s3Bucket = s3Bucket;
        this.index = index;
        this.healthStatus = healthStatus;
    }

    public HealthStatus healthStatus() {
        return healthStatus;
    }

    public void start() throws SchedulerException {
        scheduler = StdSchedulerFactory.getDefaultScheduler();
        scheduler.start();

        OptimizerConfig config = configHolder.get();
        List<String> allChannelIds = config.getSubscriptions().stream()
            .map(ChannelSubscription::getChannelId)
            .toList();
        recoverIncompleteStates(allChannelIds, index);
        GlobalSettings settings = config.getSettings();
        int fetchPeriodSeconds = settings.getFetchPeriodSeconds();
        int processingPeriodSeconds = settings.getProcessingPeriodSeconds();
        long retainMillis = (long) settings.getRetainProcessedSeconds() * 1000;

        if (fetchPeriodSeconds <= 0) {
            throw new IllegalStateException("fetch_period_seconds must be > 0, got: " + fetchPeriodSeconds);
        }
        if (processingPeriodSeconds <= 0) {
            throw new IllegalStateException("processing_period_seconds must be > 0, got: " + processingPeriodSeconds);
        }
        if (retainMillis <= 0) {
            throw new IllegalStateException("retain_processed_seconds must be > 0, got: " + settings.getRetainProcessedSeconds());
        }
        ChannelVideoFetcher videoFetcher = new YouTubeChannelVideoFetcher(ApiKeys.get().youtubeApiKey());

        ChannelTextFetcher textFetcher = new TelegramChannelTextFetcher();
        ChannelTextFetcher rssAtomFetcher = new RssAtomChannelTextFetcher(config);

        String tempSpace = System.getenv("TEMPSPACE_DIR");
        if (tempSpace == null || tempSpace.isBlank()) {
            tempSpace = "/tempspace";
        }
        String modelsDir = System.getenv("MODELS_DIR");
        if (modelsDir == null || modelsDir.isBlank()) {
            modelsDir = "/models";
        }

        Config pipelineConfig = Config.builder()
            .anthropicApiKey(ApiKeys.get().anthropicApiKey())
            .googleApiKey(ApiKeys.get().googleTtsApiKey())
            .narratorModel(settings.getNarratorModel())
            .whisperCpuPct(settings.getWhisperCpuPct())
            .whisperModel(settings.getWhisperModel())
            .outputDir(Path.of(tempSpace))
            .modelsDir(Path.of(modelsDir))
            .s3Bucket(s3Bucket)
            .awsRegion(awsRegion)
            .maxInputWords(settings.getMaxInputWords())
            .build();
        YTMediaOptimizationPipeline pipeline = new YTMediaOptimizationPipeline(pipelineConfig, healthStatus);


        Map<String, ChannelSubscription> subscriptionMap = config.getSubscriptions().stream()
            .collect(Collectors.toMap(ChannelSubscription::getChannelId, s -> s));

        for (ChannelSubscription subscription : config.getSubscriptions()) {
            SourceType sourceType = subscription.getSourceType();
            if (sourceType == null || !sourceType.isContentPlatform()) {
                LOG.info("Skipping channel fetch scheduling for '{}' (sourceType={})",
                    subscription.getName(), sourceType);
                continue;
            }
            switch (sourceType) {
                case YOUTUBE -> scheduleVideoChannel(subscription, fetchPeriodSeconds, videoFetcher, index);
                case TELEGRAM -> scheduleTextChannel(subscription, fetchPeriodSeconds, textFetcher, index, settings.getTimezone());
                case RSS_ATOM -> scheduleTextChannel(subscription, fetchPeriodSeconds, rssAtomFetcher, index, settings.getTimezone());
                default -> LOG.warn("Unknown sourceType {} for channel '{}', skipping",
                    sourceType, subscription.getName());
            }
        }

        scheduleRetention(allChannelIds, processingPeriodSeconds, retainMillis, index);
        scheduleProcessing(allChannelIds, processingPeriodSeconds, index, pipeline, subscriptionMap, config);

        List<String> digestChannelIds = config.getSubscriptions().stream()
            .filter(ChannelSubscription::isDigestMode)
            .map(ChannelSubscription::getChannelId)
            .toList();
        if (!digestChannelIds.isEmpty()) {
            TimeZone tz = settings.getTimezone() != null
                ? TimeZone.getTimeZone(ZoneId.of(settings.getTimezone()))
                : TimeZone.getDefault();
            if (settings.getMorningDigestTime() != null) {
                scheduleDigest(DigestDeliveryTime.MORNING, settings.getMorningDigestTime(), tz,
                    digestChannelIds, index, subscriptionMap, config, pipelineConfig);
            }
            if (settings.getEveningDigestTime() != null) {
                scheduleDigest(DigestDeliveryTime.EVENING, settings.getEveningDigestTime(), tz,
                    digestChannelIds, index, subscriptionMap, config, pipelineConfig);
            }
        }

        LOG.info("WorkerEngine started: {} channel(s) scheduled every {}s, processing/retention every {}s",
            config.getSubscriptions().size(), fetchPeriodSeconds, processingPeriodSeconds);
    }

    private static void recoverIncompleteStates(List<String> channelIds, MediaIndex index) {
        LOG.info("Bootstrap recovery: scanning processed-media for incomplete states");
        int reset = 0;
        int moved = 0;
        for (String channelId : channelIds) {
            for (MediaRecord record : index.queryMediaForChannel(channelId)) {
                switch (record.processingStatus()) {
                    case DOWNLOADING, TRANSCRIBING, OPTIMIZING, SYNTHESIZING, UPLOADING -> {
                        index.upsertMedia(withStatus(record, ProcessingStatus.PENDING));
                        LOG.warn("Bootstrap recovery: reset {} {} -> PENDING (was {})",
                            channelId, record.videoId(), record.processingStatus());
                        reset++;
                    }
                    case DONE -> {
                        index.moveToFeed(record);
                        LOG.warn("Bootstrap recovery: moved {} {} to feed (DONE not yet promoted)",
                            channelId, record.videoId());
                        moved++;
                    }
                    default -> { }
                }
            }
        }
        LOG.info("Bootstrap recovery complete: {} reset to PENDING, {} moved to feed", reset, moved);
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

    public void stop() throws SchedulerException {
        if (scheduler != null) {
            scheduler.shutdown(true);
        }
    }

    private void scheduleRetention(
            List<String> channelIds,
            int periodSeconds,
            long retainMillis,
            MediaIndex index) throws SchedulerException {
        JobDataMap dataMap = new JobDataMap();
        dataMap.put(MediaRetentionJob.KEY_INDEX, index);
        dataMap.put(MediaRetentionJob.KEY_CHANNEL_IDS, channelIds);
        dataMap.put(MediaRetentionJob.KEY_RETAIN_MILLIS, retainMillis);

        JobDetail job = JobBuilder.newJob(MediaRetentionJob.class)
            .withIdentity("retention", "maintenance")
            .usingJobData(dataMap)
            .build();

        Trigger trigger = TriggerBuilder.newTrigger()
            .withIdentity("trigger-retention", "maintenance")
            .startNow()
            .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                .withIntervalInSeconds(periodSeconds)
                .repeatForever())
            .build();

        scheduler.scheduleJob(job, trigger);
        LOG.info("Scheduled retention job every {}s (retainMillis={})", periodSeconds, retainMillis);
    }

    private void scheduleProcessing(
            List<String> channelIds,
            int periodSeconds,
            MediaIndex index,
            YTMediaOptimizationPipeline pipeline,
            Map<String, ChannelSubscription> subscriptionMap,
            OptimizerConfig config) throws SchedulerException {
        JobDataMap dataMap = new JobDataMap();
        dataMap.put(MediaProcessingJob.KEY_INDEX, index);
        dataMap.put(MediaProcessingJob.KEY_CHANNEL_IDS, channelIds);
        dataMap.put(MediaProcessingJob.KEY_PIPELINE, pipeline);
        dataMap.put(MediaProcessingJob.KEY_SUBSCRIPTIONS, subscriptionMap);
        dataMap.put(MediaProcessingJob.KEY_CONFIG, config);

        JobDetail job = JobBuilder.newJob(MediaProcessingJob.class)
            .withIdentity("processing", "maintenance")
            .usingJobData(dataMap)
            .build();

        Trigger trigger = TriggerBuilder.newTrigger()
            .withIdentity("trigger-processing", "maintenance")
            .startNow()
            .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                .withIntervalInSeconds(periodSeconds)
                .repeatForever())
            .build();

        scheduler.scheduleJob(job, trigger);
        LOG.info("Scheduled processing job every {}s", periodSeconds);
    }

    private void scheduleDigest(
            DigestDeliveryTime deliveryTime,
            String hhMm,
            TimeZone tz,
            List<String> channelIds,
            MediaIndex index,
            Map<String, ChannelSubscription> subscriptionMap,
            OptimizerConfig config,
            Config pipelineConfig) throws SchedulerException {
        String[] parts = hhMm.split(":");
        String cron = "0 " + parts[1] + " " + parts[0] + " * * ?";

        JobDataMap dataMap = new JobDataMap();
        dataMap.put(DigestJob.KEY_INDEX, index);
        dataMap.put(DigestJob.KEY_CHANNEL_IDS, channelIds);
        dataMap.put(DigestJob.KEY_SUBSCRIPTIONS, subscriptionMap);
        dataMap.put(DigestJob.KEY_CONFIG, config);
        dataMap.put(DigestJob.KEY_PIPELINE_CONFIG, pipelineConfig);
        dataMap.put(DigestJob.KEY_HEALTH_STATUS, healthStatus);
        dataMap.put(DigestJob.KEY_DELIVERY_TIME, deliveryTime);

        JobDetail job = JobBuilder.newJob(DigestJob.class)
            .withIdentity("digest-" + deliveryTime.name().toLowerCase(), "digest")
            .usingJobData(dataMap)
            .build();

        Trigger trigger = TriggerBuilder.newTrigger()
            .withIdentity("trigger-digest-" + deliveryTime.name().toLowerCase(), "digest")
            .withSchedule(CronScheduleBuilder.cronSchedule(cron).inTimeZone(tz))
            .build();

        scheduler.scheduleJob(job, trigger);
        LOG.info("Scheduled {} digest job at {} {} (cron: {})", deliveryTime, hhMm, tz.getID(), cron);
    }

    private void scheduleVideoChannel(
            ChannelSubscription subscription,
            int periodSeconds,
            ChannelVideoFetcher fetcher,
            MediaIndex index) throws SchedulerException {
        String channelId = subscription.getChannelId();

        JobDataMap dataMap = new JobDataMap();
        dataMap.put(ChannelFetchJob.KEY_FETCHER, fetcher);
        dataMap.put(ChannelFetchJob.KEY_INDEX, index);
        dataMap.put(ChannelFetchJob.KEY_SUBSCRIPTION, subscription);

        JobDetail job = JobBuilder.newJob(ChannelFetchJob.class)
            .withIdentity("fetch-" + channelId, "channels")
            .usingJobData(dataMap)
            .build();

        Trigger trigger = TriggerBuilder.newTrigger()
            .withIdentity("trigger-" + channelId, "channels")
            .startNow()
            .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                .withIntervalInSeconds(periodSeconds)
                .repeatForever())
            .build();

        scheduler.scheduleJob(job, trigger);
        LOG.info("Scheduled video fetch job for channel '{}' ({})", subscription.getName(), channelId);
    }

    private void scheduleTextChannel(
            ChannelSubscription subscription,
            int periodSeconds,
            ChannelTextFetcher fetcher,
            MediaIndex index,
            String timezone) throws SchedulerException {
        String channelId = subscription.getChannelId();

        JobDataMap dataMap = new JobDataMap();
        dataMap.put(ChannelTextFetchJob.KEY_FETCHER, fetcher);
        dataMap.put(ChannelTextFetchJob.KEY_INDEX, index);
        dataMap.put(ChannelTextFetchJob.KEY_SUBSCRIPTION, subscription);
        dataMap.put(ChannelTextFetchJob.KEY_TIMEZONE, timezone);

        JobDetail job = JobBuilder.newJob(ChannelTextFetchJob.class)
            .withIdentity("fetch-" + channelId, "channels")
            .usingJobData(dataMap)
            .build();

        Trigger trigger = TriggerBuilder.newTrigger()
            .withIdentity("trigger-" + channelId, "channels")
            .startNow()
            .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                .withIntervalInSeconds(periodSeconds)
                .repeatForever())
            .build();

        scheduler.scheduleJob(job, trigger);
        LOG.info("Scheduled text fetch job for channel '{}' ({})", subscription.getName(), channelId);
    }
}
