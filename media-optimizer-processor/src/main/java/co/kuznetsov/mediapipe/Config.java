package co.kuznetsov.mediapipe;

import java.nio.file.Path;

/**
 * Immutable configuration for the media optimization pipeline.
 *
 * <p>Use {@link Builder} to construct instances.
 */
public final class Config {

    private static final int DEFAULT_CPU_PCT = 50;
    private static final int DEFAULT_MAX_INPUT_WORDS = 128000;

    private final String anthropicApiKey;
    private final String googleApiKey;
    private final String narratorModel;
    private final Path outputDir;
    private final Path modelsDir;
    private final Path narrationPromptFile;
    private final int whisperCpuPct;
    private final String whisperModel;
    private final String s3Bucket;
    private final String awsRegion;
    private final int maxInputWords;

    private Config(Builder builder) {
        this.anthropicApiKey = builder.anthropicApiKey;
        this.googleApiKey = builder.googleApiKey;
        this.narratorModel = builder.narratorModel;
        this.outputDir = builder.outputDir;
        this.modelsDir = builder.modelsDir;
        this.narrationPromptFile = builder.narrationPromptFile;
        this.whisperCpuPct = builder.whisperCpuPct;
        this.whisperModel = builder.whisperModel;
        this.s3Bucket = builder.s3Bucket;
        this.awsRegion = builder.awsRegion;
        this.maxInputWords = builder.maxInputWords;
    }

    public String anthropicApiKey() {
        return anthropicApiKey;
    }

    public String googleApiKey() {
        return googleApiKey;
    }

    public String narratorModel() {
        return narratorModel;
    }

    public Path outputDir() {
        return outputDir;
    }

    public Path modelsDir() {
        return modelsDir;
    }

    public Path narrationPromptFile() {
        return narrationPromptFile;
    }

    public int whisperCpuPct() {
        return whisperCpuPct;
    }

    public String whisperModel() {
        return whisperModel;
    }

    public String s3Bucket() {
        return s3Bucket;
    }

    public String awsRegion() {
        return awsRegion;
    }

    public int maxInputWords() {
        return maxInputWords;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link Config}.
     */
    public static final class Builder {

        private String anthropicApiKey;
        private String googleApiKey;
        private String narratorModel = "claude-sonnet-4-6";
        private Path outputDir = Path.of("./output");
        private Path modelsDir = Path.of("./models");
        private Path narrationPromptFile;
        private int whisperCpuPct = DEFAULT_CPU_PCT;
        private String whisperModel = "tiny";
        private String s3Bucket;
        private String awsRegion = "us-east-2";
        private int maxInputWords = DEFAULT_MAX_INPUT_WORDS;

        private Builder() {
        }

        public Builder anthropicApiKey(String key) {
            this.anthropicApiKey = key;
            return this;
        }

        public Builder googleApiKey(String key) {
            this.googleApiKey = key;
            return this;
        }

        public Builder narratorModel(String model) {
            this.narratorModel = model;
            return this;
        }

        public Builder outputDir(Path dir) {
            this.outputDir = dir;
            return this;
        }

        public Builder modelsDir(Path dir) {
            this.modelsDir = dir;
            return this;
        }

        public Builder narrationPromptFile(Path file) {
            this.narrationPromptFile = file;
            return this;
        }

        public Builder whisperCpuPct(int pct) {
            this.whisperCpuPct = pct;
            return this;
        }

        public Builder whisperModel(String model) {
            this.whisperModel = model;
            return this;
        }

        public Builder s3Bucket(String bucket) {
            this.s3Bucket = bucket;
            return this;
        }

        public Builder awsRegion(String region) {
            this.awsRegion = region;
            return this;
        }

        public Builder maxInputWords(int words) {
            this.maxInputWords = words;
            return this;
        }

        public Config build() {
            if (anthropicApiKey == null || anthropicApiKey.isBlank()) {
                throw new IllegalStateException("anthropicApiKey is required");
            }
            if (googleApiKey == null || googleApiKey.isBlank()) {
                throw new IllegalStateException("googleApiKey is required");
            }
            return new Config(this);
        }
    }
}
