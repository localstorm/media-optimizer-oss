package co.kuznetsov.mediapipe.model;

public final class ApiKeys {

    private static final ApiKeys INSTANCE = new ApiKeys();

    private final String youtubeApiKey;
    private final String anthropicApiKey;
    private final String googleTtsApiKey;
    private final String telegramApiCredentials;

    private ApiKeys() {
        this.youtubeApiKey = System.getenv("YOUTUBE_API_KEY");
        this.anthropicApiKey = System.getenv("ANTHROPIC_API_KEY");
        this.googleTtsApiKey = System.getenv("GOOGLE_TTS_API_KEY");
        this.telegramApiCredentials = System.getenv("TELEGRAM_API_CREDENTIALS");
    }

    public static ApiKeys get() {
        return INSTANCE;
    }

    public String youtubeApiKey() {
        return youtubeApiKey;
    }

    public String anthropicApiKey() {
        return anthropicApiKey;
    }

    public String googleTtsApiKey() {
        return googleTtsApiKey;
    }

    public String telegramApiCredentials() {
        return telegramApiCredentials;
    }
}
