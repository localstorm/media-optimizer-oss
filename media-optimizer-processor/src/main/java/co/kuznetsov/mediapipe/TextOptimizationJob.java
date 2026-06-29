package co.kuznetsov.mediapipe;

import java.nio.file.Path;

/**
 * Describes a single text optimization job.
 *
 * <p>Input is raw text (e.g. a Telegram post) rather than a video URL.
 * The pipeline skips transcription and goes straight to narration and TTS.
 *
 * <p>Use {@link Builder} to construct instances.
 */
public final class TextOptimizationJob {

    private final String mediaId;
    private final String text;
    private final String channelName;
    private final int compressionFactor;
    private final int minimumNarrationLengthSeconds;
    private final String promptExtra;
    private final String promptOverride;
    private final Path outputDir;
    private final boolean narrateOnly;
    private final String timeOfDay;

    private TextOptimizationJob(Builder builder) {
        this.mediaId = builder.mediaId;
        this.text = builder.text;
        this.channelName = builder.channelName;
        this.compressionFactor = builder.compressionFactor;
        this.minimumNarrationLengthSeconds = builder.minimumNarrationLengthSeconds;
        this.promptExtra = builder.promptExtra;
        this.promptOverride = builder.promptOverride;
        this.outputDir = builder.outputDir;
        this.narrateOnly = builder.narrateOnly;
        this.timeOfDay = builder.timeOfDay;
    }

    public String mediaId() {
        return mediaId;
    }

    public String text() {
        return text;
    }

    public String channelName() {
        return channelName;
    }

    public int compressionFactor() {
        return compressionFactor;
    }

    public int minimumNarrationLengthSeconds() {
        return minimumNarrationLengthSeconds;
    }

    /**
     * Compute the target word count given the input text word count.
     *
     * @param inputWordCount number of words in the input text
     * @return target word count for the narration
     */
    public int targetWordCount(int inputWordCount) {
        int wordsPerMinute = 150;
        int fromCompression = inputWordCount / compressionFactor;
        int fromMinimum = (minimumNarrationLengthSeconds * wordsPerMinute) / 60;
        return Math.max(fromCompression, fromMinimum);
    }

    /** Optional extra instructions appended to the base prompt. */
    public String promptExtra() {
        return promptExtra;
    }

    /** Optional full prompt override — replaces the base prompt entirely. */
    public String promptOverride() {
        return promptOverride;
    }

    public Path outputDir() {
        return outputDir;
    }

    /** When true, skip TTS and S3 upload — narration text only. */
    public boolean narrateOnly() {
        return narrateOnly;
    }

    /** Optional time-of-day label (e.g. "morning", "evening") exposed to prompt templates as {@code timeOfDay}. */
    public String timeOfDay() {
        return timeOfDay;
    }

    public boolean hasPromptOverride() {
        return promptOverride != null && !promptOverride.isBlank();
    }

    public boolean hasPromptExtra() {
        return promptExtra != null && !promptExtra.isBlank();
    }

    public static Builder builder(String mediaId, String text) {
        return new Builder(mediaId, text);
    }

    /**
     * Builder for {@link TextOptimizationJob}.
     */
    public static final class Builder {

        private final String mediaId;
        private final String text;
        private String channelName = "unknown";
        private int compressionFactor = 10;
        private int minimumNarrationLengthSeconds = 120;
        private String promptExtra;
        private String promptOverride;
        private Path outputDir = Path.of("./output");
        private boolean narrateOnly = false;
        private String timeOfDay;

        private Builder(String mediaId, String text) {
            if (mediaId == null || mediaId.isBlank()) {
                throw new IllegalArgumentException("mediaId is required");
            }
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("text is required");
            }
            this.mediaId = mediaId;
            this.text = text;
        }

        public Builder channelName(String name) {
            this.channelName = name;
            return this;
        }

        public Builder compressionFactor(int factor) {
            this.compressionFactor = factor;
            return this;
        }

        public Builder minimumNarrationLengthSeconds(int seconds) {
            this.minimumNarrationLengthSeconds = seconds;
            return this;
        }

        public Builder promptExtra(String extra) {
            this.promptExtra = extra;
            return this;
        }

        public Builder promptOverride(String override) {
            this.promptOverride = override;
            return this;
        }

        public Builder outputDir(Path dir) {
            this.outputDir = dir;
            return this;
        }

        public Builder narrateOnly(boolean value) {
            this.narrateOnly = value;
            return this;
        }

        public Builder timeOfDay(String value) {
            this.timeOfDay = value;
            return this;
        }

        public TextOptimizationJob build() {
            return new TextOptimizationJob(this);
        }
    }
}
