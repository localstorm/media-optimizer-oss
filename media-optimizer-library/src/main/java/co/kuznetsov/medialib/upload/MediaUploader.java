package co.kuznetsov.medialib.upload;

import co.kuznetsov.medialib.util.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Uploads media files to S3.
 *
 * <p>Keys follow the pattern {@code media/YYYY-MM-DD/{videoId}.mp3}.
 */
public final class MediaUploader {

    private static final Logger LOG = LoggerFactory.getLogger(MediaUploader.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneOffset.UTC);
    private static final int UPLOAD_MAX_ATTEMPTS = 60;
    private static final long UPLOAD_RETRY_DELAY_MS = 60_000L;

    private final S3Client s3;
    private final String bucket;

    public MediaUploader(String bucket, String region) {
        this.bucket = bucket;
        this.s3 = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    /**
     * Uploads the given MP3 file to S3 and returns the S3 key.
     *
     * @param audioFile local path to the MP3 file
     * @param videoId   YouTube video ID used as the filename
     * @param publishedAt epoch millis of the video's publish date, used for the date prefix
     * @return the S3 key the file was uploaded to
     * @throws Exception if all upload attempts fail
     */
    public String upload(Path audioFile, String videoId, long publishedAt) throws Exception {
        String date = DATE_FMT.format(Instant.ofEpochMilli(publishedAt));
        String key = "media/" + date + "/" + videoId + ".mp3";

        LOG.info("Uploading {} to s3://{}/{}", audioFile.getFileName(), bucket, key);
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType("audio/mpeg")
                .build();
        return Retry.withRetries(UPLOAD_MAX_ATTEMPTS, UPLOAD_RETRY_DELAY_MS, () -> {
            s3.putObject(request, RequestBody.fromFile(audioFile));
            LOG.info("Uploaded s3://{}/{}", bucket, key);
            return key;
        });
    }
}
