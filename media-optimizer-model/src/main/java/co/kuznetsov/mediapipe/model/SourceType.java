package co.kuznetsov.mediapipe.model;

public enum SourceType {
    YOUTUBE(true, false),
    ANY(false, false),
    VIRTUAL(false, false),
    TELEGRAM(true, false),
    SUBMISSIONS(false, false),
    RSS_ATOM(true, true);

    private final boolean contentPlatform;
    private final boolean requiresSourceUrl;

    SourceType(boolean contentPlatform, boolean requiresSourceUrl) {
        this.contentPlatform = contentPlatform;
        this.requiresSourceUrl = requiresSourceUrl;
    }

    public boolean isContentPlatform() {
        return contentPlatform;
    }

    public boolean isRequiresSourceUrl() {
        return requiresSourceUrl;
    }

    public ContentType getContentType() {
        return switch (this) {
            case YOUTUBE -> ContentType.VIDEO;
            case ANY, VIRTUAL -> ContentType.ANY;
            case TELEGRAM, SUBMISSIONS -> ContentType.TEXT;
            case RSS_ATOM -> ContentType.TEXT;
        };
    }
}
