package co.kuznetsov.mediapipe;

import co.kuznetsov.mediapipe.model.OptimizerConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import java.io.FileInputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NarratorTemplateTest {

    private static final String SUBSCRIPTIONS_XML =
            "/home/owner/Admin/aws-infra/src/main/resources/localstorm-media-optimizer/subscriptions.xml";

    private static final String TRANSCRIPT = "This is a test transcript with some words in it.";
    private static final int WORD_TARGET = 50;

    private static OptimizerConfig config;
    private static String defaultPrompt;

    @BeforeAll
    static void loadConfig() throws Exception {
        try (InputStream in = new FileInputStream(SUBSCRIPTIONS_XML)) {
            config = OptimizerConfig.fromXml(in);
            defaultPrompt = config.getPromptText("default")
                    .orElseThrow(() -> new IllegalStateException("'default' prompt not found in subscriptions.xml"));
        }
    }

    // --- Config parsing ---

    @Test
    void config_parsesSubscriptions() {
        assertNotNull(config.getSubscriptions());
        assertFalse(config.getSubscriptions().isEmpty());
    }

    @Test
    void config_parsesGlobalSettings() {
        assertNotNull(config.getSettings());
        assertTrue(config.getSettings().getCompressionFactor() > 0);
        assertNotNull(config.getSettings().getNarratorModel());
    }

    @Test
    void config_defaultPromptExists() {
        assertTrue(config.getPromptText("default").isPresent());
        assertFalse(defaultPrompt.isBlank());
    }

    // --- Template rendering ---

    private String render(String transcript, String promptExtra) {
        TemplateEngine engine = new TemplateEngine();
        StringTemplateResolver resolver = new StringTemplateResolver();
        resolver.setTemplateMode(TemplateMode.TEXT);
        engine.setTemplateResolver(resolver);

        Context ctx = new Context();
        ctx.setVariable("wordTarget", WORD_TARGET);
        ctx.setVariable("transcript", transcript);
        ctx.setVariable("promptExtra", promptExtra);
        return engine.process(defaultPrompt, ctx);
    }

    @Test
    void defaultPrompt_rendersWithoutError_noPromptExtra() {
        String result = assertDoesNotThrow(() -> render(TRANSCRIPT, null));
        assertTrue(result.contains(TRANSCRIPT));
        assertTrue(result.contains(String.valueOf(WORD_TARGET)));
    }

    @Test
    void defaultPrompt_rendersWithoutError_withPromptExtra() {
        String extra = "Focus on security implications.";
        String result = assertDoesNotThrow(() -> render(TRANSCRIPT, extra));
        assertTrue(result.contains(extra));
        assertTrue(result.contains(TRANSCRIPT));
    }

    @Test
    void defaultPrompt_noArtifacts_whenPromptExtraNull() {
        String result = render(TRANSCRIPT, null);
        assertFalse(result.contains("promptExtra"));
        assertFalse(result.contains("[(${"));
    }

    @Test
    void defaultPrompt_noArtifacts_whenPromptExtraEmpty() {
        String result = render(TRANSCRIPT, "");
        assertFalse(result.contains("promptExtra"));
        assertFalse(result.contains("[(${"));
    }

    @Test
    void defaultPrompt_wordTarget_substituted() {
        String result = render(TRANSCRIPT, null);
        assertTrue(result.contains("50 words"));
    }

    // --- Transcript escaping ---
    // [(${...})] is unescaped output — special chars must reach Claude as-is

    @Test
    void transcript_angleAndAmpersandPassedThrough() {
        String tricky = "Version <1.0> & format: foo=\"bar\"";
        String result = render(tricky, null);
        assertTrue(result.contains(tricky),
                "Transcript with special chars should appear verbatim in prompt");
        assertFalse(result.contains("&lt;"), "< must not be HTML-escaped");
        assertFalse(result.contains("&amp;"), "& must not be HTML-escaped");
    }

    @Test
    void promptExtra_angleAndAmpersandPassedThrough() {
        String extra = "Focus on tokens > 100 & rate < 0.5";
        String result = render(TRANSCRIPT, extra);
        assertTrue(result.contains(extra),
                "promptExtra with special chars should appear verbatim");
        assertFalse(result.contains("&lt;"));
        assertFalse(result.contains("&amp;"));
    }

    @Test
    void transcript_thymeleafExpressionsNotEvaluated() {
        // Malicious/accidental Thymeleaf expressions in transcript must not be evaluated
        String tricky = "See [(${wordTarget})] and [[${wordTarget}]] in text";
        String result = render(tricky, null);
        // The raw expression text should survive, not resolve to the wordTarget value
        // (Thymeleaf TEXT mode only evaluates top-level inline expressions, not nested ones in variable values)
        assertTrue(result.contains(tricky),
                "Thymeleaf-like syntax inside transcript variable must not be re-evaluated");
    }
}
