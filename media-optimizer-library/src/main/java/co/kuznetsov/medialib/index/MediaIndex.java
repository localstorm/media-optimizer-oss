package co.kuznetsov.medialib.index;

import co.kuznetsov.medialib.util.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.IgnoreNullsMode;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactPutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactDeleteItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactWriteItemsEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.UpdateItemEnhancedRequest;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DynamoDB-backed data access layer for the media index.
 *
 * <p>Provides upsert and point-lookup operations for two tables:
 * {@code media-optimizer.channels} and {@code media-optimizer.processed-media}.
 *
 * <p>Use {@link Builder} to construct instances:
 * <pre>{@code
 * MediaIndex index = MediaIndex.builder().region("us-east-2").build();
 * }</pre>
 */
public final class MediaIndex {

    private static final Logger LOG = LoggerFactory.getLogger(MediaIndex.class);

    private static final long DYNAMO_RETRY_DELAY_MS = 1_000L;
    private static final String FEED_PARTITION_VALUE = "ALL";
    private static final String FEED_PARTITION_INDEX = "feedPartition-createdAt-index";

    // Feed query projection — excludes summaryText/transcriptText to reduce read size
    private static final List<String> FEED_PROJECTION = List.of(
        "channelId", "channelName", "videoId", "publishedAt", "publishedAtFormatted",
        "processingStatus", "sourceType", "inputTitle", "outputTitle", "inputTimeSeconds", "outputTime",
        "inputFilePath", "outputAudioUrl", "downloadTime", "uploadTime", "processingTime",
        "createdAt", "createdAtFormatted", "isPlayed", "ttl"
    );

    private final DynamoDbEnhancedClient enhanced;
    private final DynamoDbTable<ChannelItem> channelTable;
    private final DynamoDbTable<MediaItem> mediaTable;
    private final DynamoDbTable<MediaItem> feedTable;
    private final int feedRetentionDays;
    private final AtomicBoolean feedSubmitRetrying = new AtomicBoolean(false);

    private MediaIndex(Builder builder) {
        DynamoDbClient ddb = DynamoDbClient.builder()
            .region(Region.of(builder.region))
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .build();
        this.enhanced = DynamoDbEnhancedClient.builder()
            .dynamoDbClient(ddb)
            .build();
        this.channelTable = enhanced.table(builder.channelsTable, MediaIndexSchemas.CHANNEL_SCHEMA);
        this.mediaTable = enhanced.table(builder.mediaTable, MediaIndexSchemas.MEDIA_SCHEMA);
        this.feedTable = enhanced.table(builder.feedTable, MediaIndexSchemas.MEDIA_SCHEMA);
        this.feedRetentionDays = builder.feedRetentionDays;
    }

    /**
     * Returns a new {@link Builder} for constructing a {@code MediaIndex}.
     *
     * @return new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the channel record for the given channel ID, or empty if not found.
     *
     * @param channelId channel partition key
     * @return optional channel record
     */
    public Optional<ChannelRecord> getChannel(String channelId) {
        try {
            return Retry.untilSuccess(DYNAMO_RETRY_DELAY_MS, () -> {
                ChannelItem item = channelTable.getItem(Key.builder()
                    .partitionValue(channelId)
                    .build());
                return Optional.ofNullable(item).map(MediaIndex::toChannelRecord);
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for DynamoDB", e);
        }
    }

    /**
     * Writes or updates a channel record in the channels table.
     *
     * <p>Uses {@code UpdateItem} with {@code IgnoreNullsMode.SCALAR_ONLY} so that
     * null fields in the record do not delete existing DynamoDB attributes.
     *
     * @param record channel state to upsert
     */
    public void upsertChannel(ChannelRecord record) {
        try {
            Retry.untilSuccess(DYNAMO_RETRY_DELAY_MS, () -> {
                ChannelItem item = toChannelItem(record);
                channelTable.updateItem(UpdateItemEnhancedRequest.builder(ChannelItem.class)
                    .item(item)
                    .ignoreNullsMode(IgnoreNullsMode.SCALAR_ONLY)
                    .build());
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for DynamoDB", e);
        }
        LOG.debug("upsertChannel: channelId={}", record.channelId());
    }

    /**
     * Writes or updates a media record in the processed-media table.
     *
     * <p>Uses {@code UpdateItem} with {@code IgnoreNullsMode.SCALAR_ONLY} so that
     * null fields in the record do not delete existing DynamoDB attributes.
     *
     * @param record media record to upsert
     */
    public void upsertMedia(MediaRecord record) {
        try {
            Retry.untilSuccess(DYNAMO_RETRY_DELAY_MS, () -> {
                MediaItem item = toMediaItem(record);
                mediaTable.updateItem(UpdateItemEnhancedRequest.builder(MediaItem.class)
                    .item(item)
                    .ignoreNullsMode(IgnoreNullsMode.SCALAR_ONLY)
                    .build());
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for DynamoDB", e);
        }
        LOG.debug("upsertMedia: channelId={} videoId={}", record.channelId(), record.videoId());
    }

    /**
     * Returns the media record for the given channel and video, or empty if not found.
     *
     * @param channelId channel partition key
     * @param videoId   video sort key
     * @return optional media record
     */
    public Optional<MediaRecord> getMedia(String channelId, String videoId) {
        try {
            return Retry.untilSuccess(DYNAMO_RETRY_DELAY_MS, () -> {
                MediaItem item = mediaTable.getItem(Key.builder()
                    .partitionValue(channelId)
                    .sortValue(videoId)
                    .build());
                return Optional.ofNullable(item).map(MediaIndex::toMediaRecord);
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for DynamoDB", e);
        }
    }

    /**
     * Returns all media records for the given channel.
     *
     * @param channelId channel partition key
     * @return list of all media records for the channel
     */
    public List<MediaRecord> queryMediaForChannel(String channelId) {
        try {
            return Retry.untilSuccess(DYNAMO_RETRY_DELAY_MS, () -> {
                List<MediaRecord> results = new ArrayList<>();
                mediaTable.query(QueryConditional.keyEqualTo(
                        Key.builder().partitionValue(channelId).build()))
                    .stream()
                    .flatMap(page -> page.items().stream())
                    .map(MediaIndex::toMediaRecord)
                    .forEach(results::add);
                return results;
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for DynamoDB", e);
        }
    }

    /**
     * Deletes a media record by channel and video ID.
     *
     * @param channelId channel partition key
     * @param videoId   video sort key
     */
    public void deleteMedia(String channelId, String videoId) {
        try {
            Retry.untilSuccess(DYNAMO_RETRY_DELAY_MS, () -> {
                mediaTable.deleteItem(Key.builder()
                    .partitionValue(channelId)
                    .sortValue(videoId)
                    .build());
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for DynamoDB", e);
        }
        LOG.debug("deleteMedia: channelId={} videoId={}", channelId, videoId);
    }

    /**
     * Atomically writes the record to the feed table and deletes it from the processed-media table.
     * Retries indefinitely with a 1-second backoff. While retrying, {@link #isMediaFeedHealthy()}
     * returns {@code false}.
     *
     * @param record media record to promote to feed
     */
    public void moveToFeed(MediaRecord record) {
        MediaItem item = toFeedItem(record, feedRetentionDays);
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                enhanced.transactWriteItems(TransactWriteItemsEnhancedRequest.builder()
                    .addPutItem(feedTable, TransactPutItemEnhancedRequest.builder(MediaItem.class)
                        .item(item)
                        .build())
                    .addDeleteItem(mediaTable, TransactDeleteItemEnhancedRequest.builder()
                        .key(Key.builder()
                            .partitionValue(record.channelId())
                            .sortValue(record.videoId())
                            .build())
                        .build())
                    .build());
                feedSubmitRetrying.set(false);
                break;
            } catch (Exception e) {
                feedSubmitRetrying.set(true);
                LOG.warn("moveToFeed attempt {} failed for channelId={} videoId={}: {} — retrying in {}ms",
                    attempt, record.channelId(), record.videoId(), e.getMessage(), DYNAMO_RETRY_DELAY_MS);
                try {
                    Thread.sleep(DYNAMO_RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    feedSubmitRetrying.set(false);
                    throw new RuntimeException("Interrupted waiting for DynamoDB", ie);
                }
            }
        }
        LOG.debug("moveToFeed: channelId={} videoId={}", record.channelId(), record.videoId());
    }

    /**
     * Returns {@code false} if {@link #moveToFeed} is currently stuck in a retry loop,
     * indicating that the media feed DynamoDB table is unreachable.
     *
     * @return {@code true} if the feed submission is healthy (not retrying)
     */
    public boolean isMediaFeedHealthy() {
        return !feedSubmitRetrying.get();
    }

    /**
     * Returns all feed records for the given channel, sorted by publishedAt descending.
     *
     * @param channelId channel partition key
     * @return list of feed records for the channel
     */
    public List<MediaRecord> queryFeedForChannel(String channelId) {
        try {
            return Retry.untilSuccess(DYNAMO_RETRY_DELAY_MS, () -> {
                List<MediaRecord> results = new ArrayList<>();
                feedTable.query(QueryEnhancedRequest.builder()
                        .queryConditional(QueryConditional.keyEqualTo(
                            Key.builder().partitionValue(channelId).build()))
                        .attributesToProject(FEED_PROJECTION)
                        .build())
                    .stream()
                    .flatMap(page -> page.items().stream())
                    .map(MediaIndex::toMediaRecord)
                    .forEach(results::add);
                results.sort((a, b) -> {
                    long pa = a.publishedAt() != null ? a.publishedAt() : 0L;
                    long pb = b.publishedAt() != null ? b.publishedAt() : 0L;
                    return Long.compare(pb, pa);
                });
                return results;
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for DynamoDB", e);
        }
    }

    /**
     * Returns all feed records published within the given number of days, sorted by publishedAt descending.
     * Uses the feedPartition-publishedAt-index GSI for a single cross-channel query.
     *
     * @param days how many days back to query
     * @return list of feed records across all channels
     */
    public List<MediaRecord> queryRecentFeed(int days) {
        long since = Instant.now().minus(days, ChronoUnit.DAYS).toEpochMilli();
        try {
            return Retry.untilSuccess(DYNAMO_RETRY_DELAY_MS, () -> {
                DynamoDbIndex<MediaItem> index = feedTable.index(FEED_PARTITION_INDEX);
                List<MediaRecord> results = new ArrayList<>();
                index.query(QueryEnhancedRequest.builder()
                        .queryConditional(QueryConditional.sortGreaterThanOrEqualTo(
                            Key.builder()
                                .partitionValue(FEED_PARTITION_VALUE)
                                .sortValue(since)
                                .build()))
                        .scanIndexForward(false)
                        .build())
                    .stream()
                    .flatMap(page -> page.items().stream())
                    .map(MediaIndex::toMediaRecord)
                    .forEach(results::add);
                results.sort((a, b) -> Long.compare(b.createdAt(), a.createdAt()));
                return results;
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for DynamoDB", e);
        }
    }

    /**
     * Writes a record directly into the feed table (no corresponding delete from processed-media).
     *
     * @param record media record to insert into feed
     */
    public void insertFeed(MediaRecord record) {
        MediaItem item = toFeedItem(record, feedRetentionDays);
        try {
            Retry.untilSuccess(DYNAMO_RETRY_DELAY_MS, () -> {
                feedTable.updateItem(UpdateItemEnhancedRequest.builder(MediaItem.class)
                    .item(item)
                    .ignoreNullsMode(IgnoreNullsMode.SCALAR_ONLY)
                    .build());
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for DynamoDB", e);
        }
        LOG.debug("insertFeed: channelId={} videoId={}", record.channelId(), record.videoId());
    }

    /**
     * Sets isPlayed=true on the feed record for the given channel and video.
     *
     * @param channelId channel partition key
     * @param videoId   video sort key
     */
    public void markFeedPlayed(String channelId, String videoId) {
        MediaItem item = new MediaItem();
        item.setChannelId(channelId);
        item.setVideoId(videoId);
        item.setIsPlayed(Boolean.TRUE);
        try {
            Retry.untilSuccess(DYNAMO_RETRY_DELAY_MS, () -> {
                feedTable.updateItem(UpdateItemEnhancedRequest.builder(MediaItem.class)
                    .item(item)
                    .ignoreNullsMode(IgnoreNullsMode.SCALAR_ONLY)
                    .build());
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for DynamoDB", e);
        }
        LOG.info("markFeedPlayed: channelId={} videoId={}", channelId, videoId);
    }

    private static ChannelItem toChannelItem(ChannelRecord r) {
        ChannelItem item = new ChannelItem();
        item.setChannelId(r.channelId());
        item.setChannelName(r.channelName());
        item.setLastVideoId(r.lastVideoId());
        item.setProcessingStatus(r.processingStatus() == null ? null : r.processingStatus().name());
        item.setLastError(r.lastError());
        item.setSkipReason(r.skipReason());
        item.setLastVideoPublishedAt(r.lastVideoPublishedAt());
        item.setLastVideoPublishedAtFormatted(toIso(r.lastVideoPublishedAt()));
        return item;
    }

    private static ChannelRecord toChannelRecord(ChannelItem i) {
        return new ChannelRecord(
            i.getChannelId(),
            i.getChannelName(),
            i.getLastVideoId(),
            i.getProcessingStatus() == null ? null : ProcessingStatus.valueOf(i.getProcessingStatus()),
            i.getLastError(),
            i.getSkipReason(),
            i.getLastVideoPublishedAt()
        );
    }

    private static MediaItem toFeedItem(MediaRecord r, int retentionDays) {
        MediaItem item = toMediaItem(r);
        item.setFeedPartition(FEED_PARTITION_VALUE);
        if (retentionDays > 0) {
            item.setTtl(Instant.now().plus(retentionDays, ChronoUnit.DAYS).getEpochSecond());
        }
        return item;
    }

    private static MediaItem toMediaItem(MediaRecord r) {
        MediaItem item = new MediaItem();
        item.setChannelId(r.channelId());
        item.setChannelName(r.channelName());
        item.setVideoId(r.videoId());
        item.setPublishedAt(r.publishedAt());
        item.setPublishedAtFormatted(r.publishedAt() != null
            ? Instant.ofEpochMilli(r.publishedAt()).atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            : null);
        item.setProcessingStatus(r.processingStatus() == null ? null : r.processingStatus().name());
        item.setSourceType(r.sourceType());
        item.setContentType(r.contentType());
        item.setInputTitle(r.inputTitle());
        item.setOutputTitle(r.outputTitle());
        item.setInputTimeSeconds(r.inputTimeSeconds());
        item.setOutputTime(r.outputTime());
        item.setTranscriptText(r.transcriptText());
        item.setSummaryText(r.summaryText());
        item.setInputFilePath(r.inputFilePath());
        item.setOutputAudioUrl(r.outputAudioUrl());
        item.setDownloadTime(r.downloadTime());
        item.setUploadTime(r.uploadTime());
        item.setProcessingTime(r.processingTime());
        item.setIsPlayed(r.isPlayed() ? Boolean.TRUE : null);
        long createdAt = r.createdAt() > 0 ? r.createdAt() : Instant.now().toEpochMilli();
        item.setCreatedAt(createdAt);
        item.setCreatedAtFormatted(Instant.ofEpochMilli(createdAt).atOffset(ZoneOffset.UTC)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        item.setCompressionFactor(r.compressionFactor());
        return item;
    }

    private static MediaRecord toMediaRecord(MediaItem i) {
        return new MediaRecord(
            i.getChannelId(),
            i.getChannelName(),
            i.getVideoId(),
            i.getPublishedAt(),
            i.getPublishedAtFormatted(),
            i.getProcessingStatus() == null ? null : ProcessingStatus.valueOf(i.getProcessingStatus()),
            i.getSourceType(),
            i.getContentType(),
            i.getInputTitle(),
            i.getOutputTitle(),
            i.getInputTimeSeconds(),
            i.getOutputTime(),
            i.getTranscriptText(),
            i.getSummaryText(),
            i.getInputFilePath(),
            i.getOutputAudioUrl(),
            i.getDownloadTime(),
            i.getUploadTime(),
            i.getProcessingTime(),
            i.getCreatedAt() != null ? i.getCreatedAt() : 0L,
            i.getCreatedAtFormatted(),
            Boolean.TRUE.equals(i.getIsPlayed()),
            i.getCompressionFactor()
        );
    }

    private static String toIso(String epochMillisStr) {
        if (epochMillisStr == null) {
            return null;
        }
        long millis = Long.parseLong(epochMillisStr);
        return Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /**
     * Builder for {@link MediaIndex}.
     */
    public static final class Builder {

        private String region = "us-east-2";
        private String channelsTable = "media-optimizer.channels";
        private String mediaTable = "media-optimizer.processed-media";
        private String feedTable = "media-optimizer.media-feed";
        private int feedRetentionDays;

        private Builder() {
        }

        /**
         * Sets the AWS region. Default: {@code us-east-2}.
         *
         * @param region AWS region string
         * @return this builder
         */
        public Builder region(String region) {
            this.region = region;
            return this;
        }

        /**
         * Sets the DynamoDB table name for the channels table.
         * Default: {@code media-optimizer.channels}.
         *
         * @param name table name
         * @return this builder
         */
        public Builder channelsTable(String name) {
            this.channelsTable = name;
            return this;
        }

        /**
         * Sets the DynamoDB table name for the processed-media table.
         * Default: {@code media-optimizer.processed-media}.
         *
         * @param name table name
         * @return this builder
         */
        public Builder mediaTable(String name) {
            this.mediaTable = name;
            return this;
        }

        /**
         * Sets the DynamoDB table name for the media-feed table.
         * Default: {@code media-optimizer.media-feed}.
         *
         * @param name table name
         * @return this builder
         */
        public Builder feedTable(String name) {
            this.feedTable = name;
            return this;
        }

        /**
         * Sets the number of days feed records are retained before DynamoDB TTL expires them.
         * A value of 0 or less disables TTL (no expiry attribute is written).
         * Default: 0 (no TTL).
         *
         * @param days retention days
         * @return this builder
         */
        public Builder feedRetentionDays(int days) {
            this.feedRetentionDays = days;
            return this;
        }

        /**
         * Constructs a {@link MediaIndex} with the current builder settings.
         *
         * @return new MediaIndex instance
         */
        public MediaIndex build() {
            return new MediaIndex(this);
        }
    }
}
