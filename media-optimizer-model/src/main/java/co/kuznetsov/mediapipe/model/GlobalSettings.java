package co.kuznetsov.mediapipe.model;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

@JacksonXmlRootElement(localName = "settings")
public final class GlobalSettings {

    @JacksonXmlProperty(localName = "compression_factor")
    private int compressionFactor;

    @JacksonXmlProperty(localName = "minimum_narration_length_seconds")
    private int minimumNarrationLengthSeconds;

    @JacksonXmlProperty(localName = "whisper_model")
    private String whisperModel;

    @JacksonXmlProperty(localName = "whisper_cpu_pct")
    private int whisperCpuPct;

    @JacksonXmlProperty(localName = "feed_retention_days")
    private int feedRetentionDays;

    @JacksonXmlProperty(localName = "fetch_period_seconds")
    private int fetchPeriodSeconds;

    @JacksonXmlProperty(localName = "processing_period_seconds")
    private int processingPeriodSeconds;

    @JacksonXmlProperty(localName = "retain_processed_seconds")
    private int retainProcessedSeconds;

    @JacksonXmlProperty(localName = "narrator_model")
    private String narratorModel;

    @JacksonXmlProperty(localName = "digest_compression_factor")
    private int digestCompressionFactor;

    @JacksonXmlProperty(localName = "max_input_words")
    private int maxInputWords;

    @JacksonXmlProperty(localName = "morning_digest_time")
    private String morningDigestTime;

    @JacksonXmlProperty(localName = "evening_digest_time")
    private String eveningDigestTime;

    @JacksonXmlProperty(localName = "timezone")
    private String timezone;

    @JacksonXmlProperty(localName = "default_video_submission_channel")
    private String defaultVideoSubmissionChannel;

    @JacksonXmlProperty(localName = "title_prompt_ref")
    private String titlePromptRef;

    public GlobalSettings() {
    }

    public int getCompressionFactor() {
        return compressionFactor;
    }

    public void setCompressionFactor(int compressionFactor) {
        this.compressionFactor = compressionFactor;
    }

    public int getMinimumNarrationLengthSeconds() {
        return minimumNarrationLengthSeconds;
    }

    public void setMinimumNarrationLengthSeconds(int minimumNarrationLengthSeconds) {
        this.minimumNarrationLengthSeconds = minimumNarrationLengthSeconds;
    }

    public String getWhisperModel() {
        return whisperModel;
    }

    public void setWhisperModel(String whisperModel) {
        this.whisperModel = whisperModel;
    }

    public int getWhisperCpuPct() {
        return whisperCpuPct;
    }

    public void setWhisperCpuPct(int whisperCpuPct) {
        this.whisperCpuPct = whisperCpuPct;
    }

    public int getFeedRetentionDays() {
        return feedRetentionDays;
    }

    public void setFeedRetentionDays(int feedRetentionDays) {
        this.feedRetentionDays = feedRetentionDays;
    }

    public int getFetchPeriodSeconds() {
        return fetchPeriodSeconds;
    }

    public void setFetchPeriodSeconds(int fetchPeriodSeconds) {
        this.fetchPeriodSeconds = fetchPeriodSeconds;
    }

    public int getProcessingPeriodSeconds() {
        return processingPeriodSeconds;
    }

    public void setProcessingPeriodSeconds(int processingPeriodSeconds) {
        this.processingPeriodSeconds = processingPeriodSeconds;
    }

    public int getRetainProcessedSeconds() {
        return retainProcessedSeconds;
    }

    public void setRetainProcessedSeconds(int retainProcessedSeconds) {
        this.retainProcessedSeconds = retainProcessedSeconds;
    }

    public String getNarratorModel() {
        return narratorModel;
    }

    public void setNarratorModel(String narratorModel) {
        this.narratorModel = narratorModel;
    }

    public int getDigestCompressionFactor() {
        return digestCompressionFactor;
    }

    public void setDigestCompressionFactor(int digestCompressionFactor) {
        this.digestCompressionFactor = digestCompressionFactor;
    }

    public int getMaxInputWords() {
        return maxInputWords > 0 ? maxInputWords : 128000;
    }

    public void setMaxInputWords(int maxInputWords) {
        this.maxInputWords = maxInputWords;
    }

    public String getMorningDigestTime() {
        return morningDigestTime;
    }

    public void setMorningDigestTime(String morningDigestTime) {
        this.morningDigestTime = morningDigestTime;
    }

    public String getEveningDigestTime() {
        return eveningDigestTime;
    }

    public void setEveningDigestTime(String eveningDigestTime) {
        this.eveningDigestTime = eveningDigestTime;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public String getDefaultVideoSubmissionChannel() {
        return defaultVideoSubmissionChannel;
    }

    public void setDefaultVideoSubmissionChannel(String defaultVideoSubmissionChannel) {
        this.defaultVideoSubmissionChannel = defaultVideoSubmissionChannel;
    }

    public String getTitlePromptRef() {
        return titlePromptRef;
    }

    public void setTitlePromptRef(String titlePromptRef) {
        this.titlePromptRef = titlePromptRef;
    }

}
