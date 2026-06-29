package co.kuznetsov.medialib.index;

/**
 * Package-private mutable bean for DynamoDB StaticTableSchema mapping of the processed-media table.
 */
class MediaItem {

    private String channelId;
    private String channelName;
    private String videoId;
    private Long publishedAt;
    private String publishedAtFormatted;
    private String processingStatus;
    private String sourceType;
    private String contentType;
    private String inputTitle;
    private String outputTitle;
    private Long inputTimeSeconds;
    private String outputTime;
    private String transcriptText;
    private String summaryText;
    private String inputFilePath;
    private String outputAudioUrl;
    private String downloadTime;
    private String uploadTime;
    private String processingTime;
    private Long createdAt;
    private String createdAtFormatted;
    private Boolean isPlayed;
    private Long ttl;
    private String feedPartition;
    private Integer compressionFactor;

    MediaItem() {
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

    public String getVideoId() {
        return videoId;
    }

    public void setVideoId(String videoId) {
        this.videoId = videoId;
    }

    public Long getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Long publishedAt) {
        this.publishedAt = publishedAt;
    }

    public String getPublishedAtFormatted() {
        return publishedAtFormatted;
    }

    public void setPublishedAtFormatted(String publishedAtFormatted) {
        this.publishedAtFormatted = publishedAtFormatted;
    }

    public String getProcessingStatus() {
        return processingStatus;
    }

    public void setProcessingStatus(String processingStatus) {
        this.processingStatus = processingStatus;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getInputTitle() {
        return inputTitle;
    }

    public void setInputTitle(String inputTitle) {
        this.inputTitle = inputTitle;
    }

    public String getOutputTitle() {
        return outputTitle;
    }

    public void setOutputTitle(String outputTitle) {
        this.outputTitle = outputTitle;
    }

    public Long getInputTimeSeconds() {
        return inputTimeSeconds;
    }

    public void setInputTimeSeconds(Long inputTimeSeconds) {
        this.inputTimeSeconds = inputTimeSeconds;
    }

    public String getOutputTime() {
        return outputTime;
    }

    public void setOutputTime(String outputTime) {
        this.outputTime = outputTime;
    }

    public String getTranscriptText() {
        return transcriptText;
    }

    public void setTranscriptText(String transcriptText) {
        this.transcriptText = transcriptText;
    }

    public String getSummaryText() {
        return summaryText;
    }

    public void setSummaryText(String summaryText) {
        this.summaryText = summaryText;
    }

    public String getInputFilePath() {
        return inputFilePath;
    }

    public void setInputFilePath(String inputFilePath) {
        this.inputFilePath = inputFilePath;
    }

    public String getOutputAudioUrl() {
        return outputAudioUrl;
    }

    public void setOutputAudioUrl(String outputAudioUrl) {
        this.outputAudioUrl = outputAudioUrl;
    }

    public String getDownloadTime() {
        return downloadTime;
    }

    public void setDownloadTime(String downloadTime) {
        this.downloadTime = downloadTime;
    }

    public String getUploadTime() {
        return uploadTime;
    }

    public void setUploadTime(String uploadTime) {
        this.uploadTime = uploadTime;
    }

    public String getProcessingTime() {
        return processingTime;
    }

    public void setProcessingTime(String processingTime) {
        this.processingTime = processingTime;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public String getCreatedAtFormatted() {
        return createdAtFormatted;
    }

    public void setCreatedAtFormatted(String createdAtFormatted) {
        this.createdAtFormatted = createdAtFormatted;
    }

    public Boolean getIsPlayed() {
        return isPlayed;
    }

    public void setIsPlayed(Boolean isPlayed) {
        this.isPlayed = isPlayed;
    }

    public Long getTtl() {
        return ttl;
    }

    public void setTtl(Long ttl) {
        this.ttl = ttl;
    }

    public String getFeedPartition() {
        return feedPartition;
    }

    public void setFeedPartition(String feedPartition) {
        this.feedPartition = feedPartition;
    }

    public Integer getCompressionFactor() {
        return compressionFactor;
    }

    public void setCompressionFactor(Integer compressionFactor) {
        this.compressionFactor = compressionFactor;
    }
}
