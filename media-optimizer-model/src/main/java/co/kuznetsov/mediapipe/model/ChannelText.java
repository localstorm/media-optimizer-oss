package co.kuznetsov.mediapipe.model;

import java.time.Instant;

public final class ChannelText {

    private static final int WORDS_PER_MINUTE = 150;

    private final String textId;
    private final String title;
    private final Instant publishedAt;
    private final String content;

    public ChannelText(String textId, String title, Instant publishedAt, String content) {
        this.textId = textId;
        this.title = title;
        this.publishedAt = publishedAt;
        this.content = content;
    }

    public String getTextId() {
        return textId;
    }

    public String getTitle() {
        return title;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public String getContent() {
        return content;
    }

    public long getDurationSeconds() {
        int wordCount = content.isBlank() ? 0 : content.trim().split("\\s+").length;
        return (long) Math.ceil(wordCount * 60.0 / WORDS_PER_MINUTE);
    }

    @Override
    public String toString() {
        return "ChannelText{"
            + "textId='" + textId + '\''
            + ", title='" + title + '\''
            + ", publishedAt=" + publishedAt
            + ", durationSeconds=" + getDurationSeconds()
            + '}';
    }
}
