package co.kuznetsov.mediapipe.model;

import java.time.Instant;

public final class ChannelVideo {

    private final String videoId;
    private final String title;
    private final Instant publishedAt;
    private final long durationSeconds;

    public ChannelVideo(String videoId, String title, Instant publishedAt, long durationSeconds) {
        this.videoId = videoId;
        this.title = title;
        this.publishedAt = publishedAt;
        this.durationSeconds = durationSeconds;
    }

    public String getVideoId() {
        return videoId;
    }

    public String getTitle() {
        return title;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public long getDurationSeconds() {
        return durationSeconds;
    }

    @Override
    public String toString() {
        return "ChannelVideo{"
            + "videoId='" + videoId + '\''
            + ", title='" + title + '\''
            + ", publishedAt=" + publishedAt
            + ", durationSeconds=" + durationSeconds
            + '}';
    }
}
