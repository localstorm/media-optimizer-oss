package co.kuznetsov.mediapipe.worker;

import co.kuznetsov.mediapipe.model.OptimizerConfig;
import java.io.InputStream;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

public final class ConfigHolder {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigHolder.class);
    private static final long REFRESH_PERIOD_SECONDS = 300;

    private final S3Client s3Client;
    private final String s3Bucket;
    private final String s3Key;
    private final AtomicReference<OptimizerConfig> config;
    private final AtomicReference<Instant> lastRefreshedAt;
    private final ScheduledExecutorService scheduler;

    public ConfigHolder(S3Client s3Client, String s3Bucket, String s3Key, OptimizerConfig initialConfig) {
        this.s3Client = s3Client;
        this.s3Bucket = s3Bucket;
        this.s3Key = s3Key;
        this.config = new AtomicReference<>(initialConfig);
        this.lastRefreshedAt = new AtomicReference<>(Instant.now());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "config-refresh");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::refresh, REFRESH_PERIOD_SECONDS, REFRESH_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    public OptimizerConfig get() {
        return config.get();
    }

    public Instant getLastRefreshedAt() {
        return lastRefreshedAt.get();
    }

    public void close() {
        scheduler.shutdownNow();
    }

    private void refresh() {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                .bucket(s3Bucket)
                .key(s3Key)
                .build();
            try (ResponseInputStream<GetObjectResponse> response = s3Client.getObject(request)) {
                OptimizerConfig fetched = parseConfig(response);
                config.set(fetched);
                lastRefreshedAt.set(Instant.now());
                LOG.info("Config refreshed from s3://{}/{}", s3Bucket, s3Key);
            }
        } catch (Exception e) {
            LOG.warn("Failed to refresh config from s3://{}/{}; keeping current config in memory: {}",
                s3Bucket, s3Key, e.getMessage());
        }
    }

    private static OptimizerConfig parseConfig(InputStream in) throws Exception {
        try {
            return OptimizerConfig.fromXml(in);
        } catch (Exception e) {
            throw new Exception("Config XML is corrupt: " + e.getMessage(), e);
        }
    }
}
