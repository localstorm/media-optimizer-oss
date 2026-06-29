# DynamoDB Tables

This document describes the three DynamoDB tables used by the Media Optimizer application.
Table definitions live in `~/Admin/aws-infra/src/main/java/co/kuznetsov/infra/MediaOptimizerStack.java`.
Application access is via the AWS SDK v2 Enhanced Client in `media-optimizer-library`.

---

## Table 1: `media-optimizer.channels`

Tracks YouTube channels and their processing state.

| Property       | Value              |
|----------------|--------------------|
| Billing mode   | Provisioned 1R/1W  |
| Partition key  | `channelId` (S)    |
| Sort key       | —                  |
| GSIs           | —                  |
| TTL            | —                  |

### Attributes

| Attribute                      | Type | Notes                                      |
|--------------------------------|------|--------------------------------------------|
| `channelId`                    | S    | Partition key. YouTube channel ID.         |
| `channelName`                  | S    |                                            |
| `lastVideoId`                  | S    | Most recently seen video ID.               |
| `processingStatus`             | S    |                                            |
| `lastError`                    | S    | Set when processing fails.                 |
| `skipReason`                   | S    | Set when a video is intentionally skipped. |
| `lastVideoPublishedAt`         | S    | ISO-8601 timestamp.                        |
| `lastVideoPublishedAtFormatted`| S    | Human-readable variant.                    |

---

## Table 2: `media-optimizer.processed-media`

Stores media items that have been processed and are awaiting promotion to the feed.

| Property       | Value                   |
|----------------|-------------------------|
| Billing mode   | Provisioned 1R/1W       |
| Partition key  | `channelId` (S)         |
| Sort key       | `videoId` (S)           |
| GSIs           | —                       |
| TTL            | —                       |

### Attributes

| Attribute              | Type | Notes                                         |
|------------------------|------|-----------------------------------------------|
| `channelId`            | S    | Partition key.                                |
| `videoId`              | S    | Sort key.                                     |
| `channelName`          | S    |                                               |
| `publishedAt`          | N    | Unix epoch seconds.                           |
| `publishedAtFormatted` | S    | Human-readable variant.                       |
| `processingStatus`     | S    |                                               |
| `sourceType`           | S    | e.g. `YOUTUBE`.                               |
| `contentType`          | S    | e.g. `VIDEO`, `PODCAST`.                      |
| `inputTitle`           | S    | Original video title.                         |
| `outputTitle`          | S    | Generated narration headline.                 |
| `inputTimeSeconds`     | N    | Original video duration.                      |
| `outputTime`           | S    | Formatted narration duration.                 |
| `transcriptText`       | S    | Full transcript.                              |
| `summaryText`          | S    | Generated narration body.                     |
| `inputFilePath`        | S    | Local path of downloaded audio/video.         |
| `outputAudioUrl`       | S    | S3 or CDN URL of the generated MP3.           |
| `downloadTime`         | S    | Duration of download step.                    |
| `uploadTime`           | S    | Duration of upload step.                      |
| `processingTime`       | S    | Total processing duration.                    |
| `createdAt`            | N    | Unix epoch millis, when the record was written.|
| `createdAtFormatted`   | S    | Human-readable variant.                       |
| `isPlayed`             | BOOL |                                               |
| `compressionFactor`    | N    | Compression ratio used for narration.         |

---

## Table 3: `media-optimizer.media-feed`

Published media items surfaced to listeners. Supports cross-channel feed queries via a GSI and optional TTL-based expiry.

| Property       | Value                            |
|----------------|----------------------------------|
| Billing mode   | Provisioned 1R/1W                |
| Partition key  | `channelId` (S)                  |
| Sort key       | `videoId` (S)                    |
| GSIs           | `feedPartition-createdAt-index`  |
| TTL attribute  | `ttl` (N, optional)              |

### GSI: `feedPartition-createdAt-index`

Used by `MediaIndex.queryRecentFeed()` to retrieve recent items across all channels.

| Property        | Value                       |
|-----------------|-----------------------------|
| Partition key   | `feedPartition` (S)         |
| Sort key        | `createdAt` (N)             |
| Projection type | INCLUDE (see below)         |
| Capacity        | 1R/1W                       |

Projected attributes: `channelId`, `channelName`, `videoId`, `publishedAt`, `publishedAtFormatted`, `processingStatus`, `sourceType`, `inputTitle`, `outputTitle`, `inputTimeSeconds`, `outputTime`, `outputAudioUrl`, `isPlayed`.

`transcriptText` and `summaryText` are intentionally excluded from the projection to minimize read cost.

### Attributes

Same as `processed-media`, plus:

| Attribute       | Type | Notes                                                       |
|-----------------|------|-------------------------------------------------------------|
| `feedPartition` | S    | GSI partition key. A fixed bucket value for cross-channel queries. |
| `ttl`           | N    | Unix epoch seconds. Optional. Enables DynamoDB TTL expiry.  |

---

## Access Patterns

| Pattern                              | Table / Index                              | Operation        |
|--------------------------------------|--------------------------------------------|------------------|
| Look up a channel by ID              | `channels` (primary)                       | GetItem          |
| List all channels                    | `channels` (primary)                       | Scan             |
| Look up a processed item             | `processed-media` (primary)                | GetItem          |
| List items for a channel             | `processed-media` (primary)                | Query on PK      |
| Promote item to feed (atomic)        | `processed-media` + `media-feed`           | TransactWriteItems |
| Get recent feed items (all channels) | `media-feed` / `feedPartition-createdAt-index` | Query on GSI  |

---

## Notes

- All table names are configurable via `MediaIndex.Builder`; the values above are the defaults.
- Default AWS region: `us-east-2`.
- Items are moved from `processed-media` to `media-feed` atomically via a DynamoDB transaction (`moveToFeed` in `MediaIndex`).
- The application uses `IgnoreNullsMode.SCALAR_ONLY` on updates so that absent optional fields do not overwrite existing values with null.
