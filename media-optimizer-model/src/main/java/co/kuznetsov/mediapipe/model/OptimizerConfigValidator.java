package co.kuznetsov.mediapipe.model;

import java.net.URI;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class OptimizerConfigValidator {

    private OptimizerConfigValidator() {
    }

    /**
     * Validates the config and throws {@link IllegalArgumentException} listing all violations.
     */
    static void validate(OptimizerConfig config) {
        List<String> errors = new ArrayList<>();

        validateSettings(config.getSettings(), errors);
        Set<String> promptNames = validatePrompts(config.getPrompts(), errors);
        Map<String, String> sourceUrls = validateChannelSources(config.getChannelSources(), errors);
        validateSubscriptions(config.getSubscriptions(), promptNames, sourceUrls, errors);
        if (config.getSettings() != null) {
            validateDefaultSubmissionChannel(config.getSettings(), config.getSubscriptions(), errors);
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(
                "Invalid subscriptions config (" + errors.size() + " error(s)):\n  - "
                    + String.join("\n  - ", errors));
        }
    }

    private static void validateDefaultSubmissionChannel(
            GlobalSettings s, List<ChannelSubscription> subscriptions, List<String> errors) {
        String channel = s.getDefaultVideoSubmissionChannel();
        if (channel == null || channel.isBlank()) {
            errors.add("settings.default_video_submission_channel is required");
            return;
        }
        if (subscriptions == null) {
            errors.add("settings.default_video_submission_channel '" + channel
                + "' does not match any subscription channel name");
            return;
        }
        boolean found = subscriptions.stream()
            .anyMatch(sub -> channel.equals(sub.getName()));
        if (!found) {
            errors.add("settings.default_video_submission_channel '" + channel
                + "' does not match any subscription channel name");
        }
    }

    private static void validateSettings(GlobalSettings s, List<String> errors) {
        if (s == null) {
            errors.add("settings block is missing");
            return;
        }
        if (s.getFetchPeriodSeconds() <= 0) {
            errors.add("settings.fetch_period_seconds must be > 0");
        }
        if (s.getProcessingPeriodSeconds() <= 0) {
            errors.add("settings.processing_period_seconds must be > 0");
        }
        if (s.getRetainProcessedSeconds() <= 0) {
            errors.add("settings.retain_processed_seconds must be > 0");
        }
        if (s.getCompressionFactor() <= 0) {
            errors.add("settings.compression_factor must be > 0");
        }
        if (s.getFeedRetentionDays() <= 0) {
            errors.add("settings.feed_retention_days must be > 0");
        }
        if (s.getWhisperModel() == null || s.getWhisperModel().isBlank()) {
            errors.add("settings.whisper_model is required");
        }
        if (s.getNarratorModel() == null || s.getNarratorModel().isBlank()) {
            errors.add("settings.narrator_model is required");
        }
        if (s.getDigestCompressionFactor() <= 0) {
            errors.add("settings.digest_compression_factor must be > 0");
        }
        validateDigestTime("settings.morning_digest_time", s.getMorningDigestTime(), errors);
        validateDigestTime("settings.evening_digest_time", s.getEveningDigestTime(), errors);
        if (s.getTimezone() != null) {
            try {
                ZoneId.of(s.getTimezone());
            } catch (Exception e) {
                errors.add("settings.timezone is not a valid timezone ID: " + s.getTimezone());
            }
        }
    }

    private static void validateDigestTime(String field, String value, List<String> errors) {
        if (value != null && !value.matches("^([01]\\d|2[0-3]):[0-5]\\d$")) {
            errors.add(field + " must be in HH:MM format (00:00-23:59), got: " + value);
        }
    }

    private static Set<String> validatePrompts(List<NamedPrompt> prompts, List<String> errors) {
        Set<String> names = new HashSet<>();
        if (prompts == null) {
            return names;
        }
        for (int i = 0; i < prompts.size(); i++) {
            NamedPrompt p = prompts.get(i);
            String loc = "prompts[" + i + "]";
            if (p.getName() == null || p.getName().isBlank()) {
                errors.add(loc + ": name attribute is required");
            } else if (!names.add(p.getName())) {
                errors.add(loc + ": duplicate prompt name '" + p.getName() + "'");
            }
            if (p.getText() == null || p.getText().isBlank()) {
                errors.add(loc + " (name='" + p.getName() + "'): text is empty");
            }
        }
        return names;
    }

    private static Map<String, String> validateChannelSources(
            List<ChannelSource> sources, List<String> errors) {
        Map<String, String> urlByChannelId = new HashMap<>();
        if (sources == null) {
            return urlByChannelId;
        }
        for (int i = 0; i < sources.size(); i++) {
            ChannelSource cs = sources.get(i);
            String loc = "channelSources[" + i + "]";
            if (cs.getChannelId() == null || cs.getChannelId().isBlank()) {
                errors.add(loc + ": channelId attribute is required");
                continue;
            }
            if (cs.getUrl() == null || cs.getUrl().isBlank()) {
                errors.add(loc + " (channelId='" + cs.getChannelId() + "'): URL is required");
                continue;
            }
            try {
                URI uri = URI.create(cs.getUrl().trim());
                String scheme = uri.getScheme();
                if (!"http".equals(scheme) && !"https".equals(scheme)) {
                    errors.add(loc + " (channelId='" + cs.getChannelId() + "'): URL must use http or https scheme: " + cs.getUrl());
                } else {
                    urlByChannelId.put(cs.getChannelId(), cs.getUrl().trim());
                }
            } catch (IllegalArgumentException e) {
                errors.add(loc + " (channelId='" + cs.getChannelId() + "'): invalid URL: " + cs.getUrl());
            }
        }
        return urlByChannelId;
    }

    private static void validateSubscriptions(
            List<ChannelSubscription> subscriptions,
            Set<String> promptNames,
            Map<String, String> sourceUrls,
            List<String> errors) {
        if (subscriptions == null || subscriptions.isEmpty()) {
            errors.add("at least one subscription is required");
            return;
        }
        Set<String> channelIds = new HashSet<>();
        Set<String> virtualChannelIds = new HashSet<>();
        for (ChannelSubscription s : subscriptions) {
            if (s.getChannelId() != null && s.getSourceType() == SourceType.VIRTUAL) {
                virtualChannelIds.add(s.getChannelId());
            }
        }
        for (int i = 0; i < subscriptions.size(); i++) {
            ChannelSubscription s = subscriptions.get(i);
            String loc = "subscriptions[" + i + "]";
            if (s.getChannelId() == null || s.getChannelId().isBlank()) {
                errors.add(loc + ": channel_id is required");
            } else if (!channelIds.add(s.getChannelId())) {
                errors.add(loc + ": duplicate channel_id '" + s.getChannelId() + "'");
            }
            if (s.getName() == null || s.getName().isBlank()) {
                errors.add(loc + " (channel='" + s.getChannelId() + "'): name is required");
            }
            if (s.getSourceType() != null && s.getSourceType().isRequiresSourceUrl()
                    && s.getChannelId() != null && !sourceUrls.containsKey(s.getChannelId())) {
                errors.add(loc + " (channel='" + s.getChannelId() + "'): source_type "
                    + s.getSourceType() + " requires a channelSource entry with a valid URL");
            }
            boolean isVirtual = s.getSourceType() == SourceType.VIRTUAL;
            if (isVirtual) {
                if (!s.isDigestMode()) {
                    errors.add(loc + " (channel='" + s.getChannelId() + "'): digest_mode must be true for VIRTUAL channels");
                }
                if (s.getDigestPromptRef() == null || s.getDigestPromptRef().isBlank()) {
                    errors.add(loc + " (channel='" + s.getChannelId() + "'): digest_prompt_ref is required for VIRTUAL channels");
                } else if (!promptNames.contains(s.getDigestPromptRef())) {
                    errors.add(loc + " (channel='" + s.getChannelId() + "'): digest_prompt_ref '"
                        + s.getDigestPromptRef() + "' does not match any defined prompt");
                }
                if (s.getFeedsInto() != null) {
                    errors.add(loc + " (channel='" + s.getChannelId() + "'): VIRTUAL channels cannot have feeds_into");
                }
                if (s.getCompressionFactor() <= 0) {
                    errors.add(loc + " (channel='" + s.getChannelId() + "'): compression_factor must be > 0 for VIRTUAL channels");
                }
            } else {
                if (s.getFeedsInto() == null && s.getCompressionFactor() <= 0) {
                    errors.add(loc + " (channel='" + s.getChannelId() + "'): compression_factor must be > 0");
                }
                if (s.getPromptExtraRef() != null && !promptNames.contains(s.getPromptExtraRef())) {
                    errors.add(loc + " (channel='" + s.getChannelId() + "'): prompt_extra_ref '"
                        + s.getPromptExtraRef() + "' does not match any defined prompt");
                }
                if (s.getPromptRef() != null && !promptNames.contains(s.getPromptRef())) {
                    errors.add(loc + " (channel='" + s.getChannelId() + "'): prompt_ref '"
                        + s.getPromptRef() + "' does not match any defined prompt");
                }
                if (s.isDigestMode() && s.getFeedsInto() == null) {
                    if (s.getDigestPromptRef() == null || s.getDigestPromptRef().isBlank()) {
                        errors.add(loc + " (channel='" + s.getChannelId() + "'): digest_prompt_ref is required when digest_mode=true");
                    } else if (!promptNames.contains(s.getDigestPromptRef())) {
                        errors.add(loc + " (channel='" + s.getChannelId() + "'): digest_prompt_ref '"
                            + s.getDigestPromptRef() + "' does not match any defined prompt");
                    }
                }
                if (s.getFeedsInto() != null) {
                    if (!virtualChannelIds.contains(s.getFeedsInto())) {
                        errors.add(loc + " (channel='" + s.getChannelId() + "'): feeds_into '"
                            + s.getFeedsInto() + "' does not reference a VIRTUAL channel");
                    }
                    if (s.getCompressionFactor() > 0) {
                        errors.add(loc + " (channel='" + s.getChannelId() + "'): compression_factor must not be set when feeds_into is set");
                    }
                    if (s.getPromptRef() != null) {
                        errors.add(loc + " (channel='" + s.getChannelId() + "'): prompt_ref must not be set when feeds_into is set");
                    }
                    if (s.getPromptExtraRef() != null) {
                        errors.add(loc + " (channel='" + s.getChannelId() + "'): prompt_extra_ref must not be set when feeds_into is set");
                    }
                    if (s.isDigestMode()) {
                        errors.add(loc + " (channel='" + s.getChannelId() + "'): digest_mode must not be set when feeds_into is set");
                    }
                    if (s.getDigestPromptRef() != null) {
                        errors.add(loc + " (channel='" + s.getChannelId() + "'): digest_prompt_ref must not be set when feeds_into is set");
                    }
                }
            }
        }
    }
}
