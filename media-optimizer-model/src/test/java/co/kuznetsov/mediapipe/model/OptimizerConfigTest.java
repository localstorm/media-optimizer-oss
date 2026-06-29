package co.kuznetsov.mediapipe.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptimizerConfigTest {

    private static final String SAMPLE_XML = "<config>"
        + "<settings>"
        + "<compression_factor>10</compression_factor>"
        + "<minimum_narration_length_seconds>120</minimum_narration_length_seconds>"
        + "<whisper_model>tiny</whisper_model>"
        + "<whisper_cpu_pct>50</whisper_cpu_pct>"
        + "<feed_retention_days>100</feed_retention_days>"
        + "<fetch_period_seconds>3600</fetch_period_seconds>"
        + "<processing_period_seconds>119</processing_period_seconds>"
        + "<retain_processed_seconds>172800</retain_processed_seconds>"
        + "<narrator_model>claude-sonnet-4-6</narrator_model>"
        + "<digest_compression_factor>5</digest_compression_factor>"
        + "<default_video_submission_channel>NetworkChuck</default_video_submission_channel>"
        + "</settings>"
        + "<subscriptions>"
        + "<subscription>"
        + "<channel_id>UCJgGc8pQO1lv04VXrBxA_Hg</channel_id>"
        + "<name>NetworkChuck</name>"
        + "<min_video_length_seconds>300</min_video_length_seconds>"
        + "<compression_factor>8</compression_factor>"
        + "<prompt_extra_ref>skip-coffee</prompt_extra_ref>"
        + "</subscription>"
        + "<subscription>"
        + "<channel_id>UCXuqSBlHAE6Xw-yeJA0Tunw</channel_id>"
        + "<name>Linus Tech Tips</name>"
        + "<min_video_length_seconds>600</min_video_length_seconds>"
        + "<compression_factor>15</compression_factor>"
        + "<prompt_ref>one-para</prompt_ref>"
        + "</subscription>"
        + "<subscription>"
        + "<channel_id>UCddiUEpeqJcYeBxX1IVBKvQ</channel_id>"
        + "<name>Fireship</name>"
        + "<min_video_length_seconds>120</min_video_length_seconds>"
        + "<compression_factor>10</compression_factor>"
        + "</subscription>"
        + "<subscription>"
        + "<channel_id>UCXXXexample</channel_id>"
        + "<name>SomeHighVolumeChannel</name>"
        + "<min_video_length_seconds>60</min_video_length_seconds>"
        + "<compression_factor>10</compression_factor>"
        + "<digest_mode>true</digest_mode>"
        + "<digest_prompt_ref>digest-summary</digest_prompt_ref>"
        + "</subscription>"
        + "</subscriptions>"
        + "<prompts>"
        + "<prompt name=\"skip-coffee\">Skip extended coffee analogies.</prompt>"
        + "<prompt name=\"one-para\">Summarize in one paragraph.</prompt>"
        + "<prompt name=\"default\">Summarize the key points concisely.</prompt>"
        + "<prompt name=\"tech\">Focus on technical details and code examples.</prompt>"
        + "<prompt name=\"digest-summary\">Combine these summaries into a single digest.</prompt>"
        + "</prompts>"
        + "</config>";

    @Test
    void deserializesSettingsCorrectly() throws Exception {
        OptimizerConfig config = OptimizerConfig.fromXml(SAMPLE_XML);

        GlobalSettings s = config.getSettings();
        assertNotNull(s);
        assertEquals(10, s.getCompressionFactor());
        assertEquals(120, s.getMinimumNarrationLengthSeconds());
        assertEquals("tiny", s.getWhisperModel());
        assertEquals(50, s.getWhisperCpuPct());
        assertEquals(100, s.getFeedRetentionDays());
        assertEquals(3600, s.getFetchPeriodSeconds());
    }

    @Test
    void deserializesSubscriptionsCorrectly() throws Exception {
        OptimizerConfig config = OptimizerConfig.fromXml(SAMPLE_XML);

        assertEquals(4, config.getSubscriptions().size());

        ChannelSubscription networkChuck = config.getSubscriptions().get(0);
        assertEquals("UCJgGc8pQO1lv04VXrBxA_Hg", networkChuck.getChannelId());
        assertEquals("NetworkChuck", networkChuck.getName());
        assertEquals(300, networkChuck.getMinVideoLengthSeconds());
        assertEquals(8, networkChuck.getCompressionFactor());
        assertEquals("skip-coffee", networkChuck.getPromptExtraRef());
        assertNull(networkChuck.getPromptRef());
        assertFalse(networkChuck.isDigestMode());

        ChannelSubscription ltt = config.getSubscriptions().get(1);
        assertEquals(15, ltt.getCompressionFactor());
        assertEquals("one-para", ltt.getPromptRef());
        assertNull(ltt.getPromptExtraRef());

        ChannelSubscription fireship = config.getSubscriptions().get(2);
        assertEquals(10, fireship.getCompressionFactor());

        ChannelSubscription highVolume = config.getSubscriptions().get(3);
        assertTrue(highVolume.isDigestMode());
    }

    @Test
    void deserializesPromptsCorrectly() throws Exception {
        OptimizerConfig config = OptimizerConfig.fromXml(SAMPLE_XML);

        assertEquals(5, config.getPrompts().size());

        assertTrue(config.getPromptText("default").isPresent());
        assertEquals("Summarize the key points concisely.", config.getPromptText("default").get());
        assertEquals("Focus on technical details and code examples.", config.getPromptText("tech").get());
        assertFalse(config.getPromptText("missing").isPresent());
    }

    @Test
    void roundTripsToXmlAndBack() throws Exception {
        OptimizerConfig original = OptimizerConfig.fromXml(SAMPLE_XML);
        String serialized = original.toXml();
        OptimizerConfig roundTripped = OptimizerConfig.fromXml(serialized);

        assertEquals(
            original.getSettings().getCompressionFactor(),
            roundTripped.getSettings().getCompressionFactor()
        );
        assertEquals(
            original.getSubscriptions().size(),
            roundTripped.getSubscriptions().size()
        );
        assertEquals(
            original.getSubscriptions().get(0).getChannelId(),
            roundTripped.getSubscriptions().get(0).getChannelId()
        );
    }

    @Test
    void rejectsUnresolvedPromptRef() {
        String xml = SAMPLE_XML.replace(
            "<prompt_extra_ref>skip-coffee</prompt_extra_ref>",
            "<prompt_extra_ref>nonexistent</prompt_extra_ref>");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> OptimizerConfig.fromXml(xml));
        assertTrue(ex.getMessage().contains("nonexistent"));
    }

    @Test
    void rejectsDuplicateChannelId() {
        String xml = SAMPLE_XML.replace(
            "<channel_id>UCXuqSBlHAE6Xw-yeJA0Tunw</channel_id>",
            "<channel_id>UCJgGc8pQO1lv04VXrBxA_Hg</channel_id>");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> OptimizerConfig.fromXml(xml));
        assertTrue(ex.getMessage().contains("UCJgGc8pQO1lv04VXrBxA_Hg"));
    }

    @Test
    void rejectsDuplicatePromptName() {
        String xml = SAMPLE_XML.replace(
            "<prompt name=\"one-para\">Summarize in one paragraph.</prompt>",
            "<prompt name=\"skip-coffee\">Summarize in one paragraph.</prompt>");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> OptimizerConfig.fromXml(xml));
        assertTrue(ex.getMessage().contains("skip-coffee"));
    }

    @Test
    void rejectsMissingSettings() {
        String xml = "<config>"
            + "<subscriptions>"
            + "<subscription>"
            + "<channel_id>UCfoo</channel_id>"
            + "<name>Foo</name>"
            + "<compression_factor>10</compression_factor>"
            + "</subscription>"
            + "</subscriptions>"
            + "</config>";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> OptimizerConfig.fromXml(xml));
        assertTrue(ex.getMessage().contains("settings block is missing"));
    }

    @Test
    void deserializesDefaultVideoSubmissionChannelCorrectly() throws Exception {
        OptimizerConfig config = OptimizerConfig.fromXml(SAMPLE_XML);
        assertEquals("NetworkChuck", config.getSettings().getDefaultVideoSubmissionChannel());
    }

    @Test
    void rejectsMissingDefaultVideoSubmissionChannel() {
        String xml = SAMPLE_XML.replace(
            "<default_video_submission_channel>NetworkChuck</default_video_submission_channel>", "");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> OptimizerConfig.fromXml(xml));
        assertTrue(ex.getMessage().contains("default_video_submission_channel is required"));
    }

    @Test
    void rejectsDefaultVideoSubmissionChannelNotInSubscriptions() {
        String xml = SAMPLE_XML.replace(
            "<default_video_submission_channel>NetworkChuck</default_video_submission_channel>",
            "<default_video_submission_channel>NONEXISTENT</default_video_submission_channel>");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> OptimizerConfig.fromXml(xml));
        assertTrue(ex.getMessage().contains("NONEXISTENT"));
    }

    @Test
    void deserializesTimezoneCorrectly() throws Exception {
        String xml = SAMPLE_XML.replace(
            "</settings>",
            "<timezone>America/Toronto</timezone></settings>");
        OptimizerConfig config = OptimizerConfig.fromXml(xml);
        assertEquals("America/Toronto", config.getSettings().getTimezone());
    }

    @Test
    void rejectsInvalidTimezone() {
        String xml = SAMPLE_XML.replace(
            "</settings>",
            "<timezone>NotATimezone</timezone></settings>");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> OptimizerConfig.fromXml(xml));
        assertTrue(ex.getMessage().contains("settings.timezone"));
    }

    @Test
    void rejectsInvalidSettingsFields() {
        String xml = SAMPLE_XML
            .replace("<fetch_period_seconds>3600</fetch_period_seconds>",
                "<fetch_period_seconds>0</fetch_period_seconds>")
            .replace("<processing_period_seconds>119</processing_period_seconds>",
                "<processing_period_seconds>0</processing_period_seconds>");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> OptimizerConfig.fromXml(xml));
        assertTrue(ex.getMessage().contains("fetch_period_seconds"));
        assertTrue(ex.getMessage().contains("processing_period_seconds"));
    }
}
