package co.kuznetsov.mediapipe.model;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

@JacksonXmlRootElement(localName = "subscription")
public final class ChannelSubscription {

    @JacksonXmlProperty(localName = "source_type")
    private SourceType sourceType;

    @JacksonXmlProperty(localName = "channel_id")
    private String channelId;

    @JacksonXmlProperty(localName = "name")
    private String name;

    @JacksonXmlProperty(localName = "min_video_length_seconds")
    private int minVideoLengthSeconds;

    @JacksonXmlProperty(localName = "compression_factor")
    private int compressionFactor;

    @JacksonXmlProperty(localName = "prompt_extra_ref")
    private String promptExtraRef;

    @JacksonXmlProperty(localName = "prompt_ref")
    private String promptRef;

    @JacksonXmlProperty(localName = "digest_mode")
    private boolean digestMode;

    @JacksonXmlProperty(localName = "digest_prompt_ref")
    private String digestPromptRef;

    @JacksonXmlProperty(localName = "feeds_into")
    private String feedsInto;

    @JacksonXmlProperty(localName = "digest_delivery_time")
    private DigestDeliveryTime digestDeliveryTime;

    @JacksonXmlProperty(localName = "pinned")
    private boolean pinned;

    public ChannelSubscription() {
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(SourceType sourceType) {
        this.sourceType = sourceType;
    }

    public String getChannelId() {
        return channelId;
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getMinVideoLengthSeconds() {
        return minVideoLengthSeconds;
    }

    public void setMinVideoLengthSeconds(int minVideoLengthSeconds) {
        this.minVideoLengthSeconds = minVideoLengthSeconds;
    }

    public int getCompressionFactor() {
        return compressionFactor;
    }

    public void setCompressionFactor(int compressionFactor) {
        this.compressionFactor = compressionFactor;
    }

    public String getPromptExtraRef() {
        return promptExtraRef;
    }

    public void setPromptExtraRef(String promptExtraRef) {
        this.promptExtraRef = promptExtraRef;
    }

    public String getPromptRef() {
        return promptRef;
    }

    public void setPromptRef(String promptRef) {
        this.promptRef = promptRef;
    }

    public boolean isDigestMode() {
        return digestMode;
    }

    public void setDigestMode(boolean digestMode) {
        this.digestMode = digestMode;
    }

    public String getDigestPromptRef() {
        return digestPromptRef;
    }

    public void setDigestPromptRef(String digestPromptRef) {
        this.digestPromptRef = digestPromptRef;
    }

    public String getFeedsInto() {
        return feedsInto;
    }

    public void setFeedsInto(String feedsInto) {
        this.feedsInto = feedsInto;
    }

    public DigestDeliveryTime getDigestDeliveryTime() {
        return digestDeliveryTime;
    }

    public void setDigestDeliveryTime(DigestDeliveryTime digestDeliveryTime) {
        this.digestDeliveryTime = digestDeliveryTime;
    }

    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }
}
