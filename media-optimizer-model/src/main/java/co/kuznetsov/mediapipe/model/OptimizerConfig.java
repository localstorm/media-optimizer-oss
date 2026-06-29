package co.kuznetsov.mediapipe.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Optional;

@JacksonXmlRootElement(localName = "config")
public final class OptimizerConfig {

    private static final XmlMapper MAPPER = XmlMapper.builder()
        .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
        .serializationInclusion(JsonInclude.Include.NON_NULL)
        .build();

    @JacksonXmlProperty(localName = "settings")
    private GlobalSettings settings;

    @JacksonXmlElementWrapper(localName = "subscriptions")
    @JacksonXmlProperty(localName = "subscription")
    private List<ChannelSubscription> subscriptions;

    @JacksonXmlElementWrapper(localName = "prompts")
    @JacksonXmlProperty(localName = "prompt")
    private List<NamedPrompt> prompts;

    @JacksonXmlElementWrapper(localName = "channelSources")
    @JacksonXmlProperty(localName = "channelSource")
    private List<ChannelSource> channelSources;

    public OptimizerConfig() {
    }

    public static OptimizerConfig fromXml(InputStream in) throws IOException {
        OptimizerConfig config = MAPPER.readValue(in, OptimizerConfig.class);
        OptimizerConfigValidator.validate(config);
        return config;
    }

    public static OptimizerConfig fromXml(String xml) throws IOException {
        OptimizerConfig config = MAPPER.readValue(xml, OptimizerConfig.class);
        OptimizerConfigValidator.validate(config);
        return config;
    }

    public void toXml(OutputStream out) throws IOException {
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(out, this);
    }

    public String toXml() throws IOException {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(this);
    }

    public GlobalSettings getSettings() {
        return settings;
    }

    public void setSettings(GlobalSettings settings) {
        this.settings = settings;
    }

    public List<ChannelSubscription> getSubscriptions() {
        return subscriptions;
    }

    public void setSubscriptions(List<ChannelSubscription> subscriptions) {
        this.subscriptions = subscriptions;
    }

    public List<NamedPrompt> getPrompts() {
        return prompts;
    }

    public void setPrompts(List<NamedPrompt> prompts) {
        this.prompts = prompts;
    }

    public Optional<ChannelSubscription> findSubscriptionByName(String name) {
        if (subscriptions == null) {
            return Optional.empty();
        }
        return subscriptions.stream()
            .filter(s -> name.equals(s.getName()))
            .findFirst();
    }

    public Optional<ChannelSubscription> findSubscriptionById(String channelId) {
        if (subscriptions == null) {
            return Optional.empty();
        }
        return subscriptions.stream()
            .filter(s -> channelId.equals(s.getChannelId()))
            .findFirst();
    }

    public Optional<String> getPromptText(String name) {
        if (prompts == null) {
            return Optional.empty();
        }
        return prompts.stream()
            .filter(p -> name.equals(p.getName()))
            .map(NamedPrompt::getText)
            .findFirst();
    }

    public List<ChannelSource> getChannelSources() {
        return channelSources;
    }

    public void setChannelSources(List<ChannelSource> channelSources) {
        this.channelSources = channelSources;
    }

    public Optional<String> findSourceUrl(String channelId) {
        if (channelSources == null) {
            return Optional.empty();
        }
        return channelSources.stream()
            .filter(s -> channelId.equals(s.getChannelId()))
            .map(ChannelSource::getUrl)
            .findFirst();
    }
}
