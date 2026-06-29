package co.kuznetsov.medialib.index;

/**
 * Package-private mutable bean for DynamoDB StaticTableSchema mapping of the channels table.
 */
class ChannelItem {

    private String channelId;
    private String channelName;
    private String lastVideoId;
    private String processingStatus;
    private String lastError;
    private String skipReason;
    private String lastVideoPublishedAt;
    private String lastVideoPublishedAtFormatted;

    ChannelItem() {
    }

    public String getChannelId() {
        return channelId;
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    public String getChannelName() {
        return channelName;
    }

    public void setChannelName(String channelName) {
        this.channelName = channelName;
    }

    public String getLastVideoId() {
        return lastVideoId;
    }

    public void setLastVideoId(String lastVideoId) {
        this.lastVideoId = lastVideoId;
    }

    public String getProcessingStatus() {
        return processingStatus;
    }

    public void setProcessingStatus(String processingStatus) {
        this.processingStatus = processingStatus;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public String getSkipReason() {
        return skipReason;
    }

    public void setSkipReason(String skipReason) {
        this.skipReason = skipReason;
    }

    public String getLastVideoPublishedAt() {
        return lastVideoPublishedAt;
    }

    public void setLastVideoPublishedAt(String lastVideoPublishedAt) {
        this.lastVideoPublishedAt = lastVideoPublishedAt;
    }

    public String getLastVideoPublishedAtFormatted() {
        return lastVideoPublishedAtFormatted;
    }

    public void setLastVideoPublishedAtFormatted(String lastVideoPublishedAtFormatted) {
        this.lastVideoPublishedAtFormatted = lastVideoPublishedAtFormatted;
    }
}
