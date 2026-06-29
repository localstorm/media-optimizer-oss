package co.kuznetsov.mediapipe.server;

import co.kuznetsov.medialib.index.MediaIndex;
import co.kuznetsov.medialib.util.HealthStatus;
import co.kuznetsov.mediapipe.model.ApiKeys;
import co.kuznetsov.mediapipe.model.OptimizerConfig;
import co.kuznetsov.mediapipe.worker.ChannelVideoFetcher;
import co.kuznetsov.mediapipe.worker.ConfigHolder;
import co.kuznetsov.mediapipe.worker.WorkerEngine;
import co.kuznetsov.mediapipe.worker.YouTubeChannelVideoFetcher;
import org.quartz.SchedulerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
public class WorkerConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerConfiguration.class);

    @Value("${optimizer.s3.bucket}")
    private String s3Bucket;

    @Value("${optimizer.s3.key}")
    private String s3Key;

    @Value("${optimizer.aws.region}")
    private String awsRegion;

    @Bean
    public S3Client s3Client() {
        return S3Client.create();
    }

    @Bean
    public S3Presigner s3Presigner() {
        return S3Presigner.builder()
            .region(Region.of(awsRegion))
            .build();
    }

    @Bean
    public MediaIndex mediaIndex(ConfigHolder configHolder) {
        MediaIndex.Builder builder = MediaIndex.builder();
        if (awsRegion != null && !awsRegion.isBlank()) {
            builder.region(awsRegion);
        }
        int retentionDays = configHolder.get().getSettings().getFeedRetentionDays();
        if (retentionDays > 0) {
            builder.feedRetentionDays(retentionDays);
        }
        return builder.build();
    }

    @Bean
    public ConfigHolder configHolder(S3Client s3Client) throws Exception {
        OptimizerConfig initialConfig = loadInitialConfig(s3Client);
        return new ConfigHolder(s3Client, s3Bucket, s3Key, initialConfig);
    }

    @Bean
    public ChannelVideoFetcher channelVideoFetcher() {
        return new YouTubeChannelVideoFetcher(ApiKeys.get().youtubeApiKey());
    }

    @Bean
    public HealthStatus healthStatus() {
        return new HealthStatus();
    }

    @Bean(destroyMethod = "stop")
    public WorkerEngine workerEngine(
            ConfigHolder configHolder, MediaIndex mediaIndex, HealthStatus healthStatus)
            throws SchedulerException {
        WorkerEngine engine = new WorkerEngine(configHolder, awsRegion, s3Bucket, mediaIndex, healthStatus);
        engine.start();
        LOG.info("WorkerEngine started via Spring context");
        return engine;
    }

    private OptimizerConfig loadInitialConfig(S3Client s3Client) throws Exception {
        GetObjectRequest request = GetObjectRequest.builder()
            .bucket(s3Bucket)
            .key(s3Key)
            .build();
        try (ResponseInputStream<GetObjectResponse> response = s3Client.getObject(request)) {
            return OptimizerConfig.fromXml(response);
        }
    }
}
