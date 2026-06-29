package co.kuznetsov.mediapipe;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import co.kuznetsov.medialib.util.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;

import java.io.IOException;
import java.nio.file.Files;

/**
 * Generates a narration from a transcript using Claude.
 *
 * <p>Prompt template resolution order:
 * <ol>
 *   <li>{@link MediaOptimizationJob#promptOverride()} — used as Thymeleaf inline template</li>
 *   <li>{@link Config#narrationPromptFile()} — external file Thymeleaf template</li>
 * </ol>
 *
 * <p>If the job has {@link MediaOptimizationJob#promptExtra()}, it is appended
 * to the resolved base prompt before the transcript.
 *
 * <p>Template variables available in Thymeleaf templates:
 * <ul>
 *   <li>{@code wordTarget} — computed word count target</li>
 *   <li>{@code transcript} — the transcript text</li>
 *   <li>{@code promptExtra} — optional channel-specific instructions (may be null)</li>
 *   <li>{@code channelName} — channel name</li>
 *   <li>{@code date} — formatted as "MMMM d, yyyy"</li>
 *   <li>{@code timeOfDay} — optional time-of-day label e.g. "morning" or "evening" (may be null)</li>
 * </ul>
 */
public class Narrator {

    private static final Logger LOG = LoggerFactory.getLogger(Narrator.class);

    private static final int CLAUDE_MAX_ATTEMPTS = 10;
    private static final long CLAUDE_RETRY_DELAY_MS = 2 * 60 * 1000L;
    // 1.5 tokens/word is conservative for English prose; 2x headroom since output can exceed input
    // (e.g. digest adds editorial framing). maxTokens is a safety cap only — length is prompt-driven.
    private static final double TOKENS_PER_WORD = 1.5;
    private static final double TOKENS_HEADROOM = 2.0;

    private final Config config;
    private final AnthropicClient client;

    public Narrator(Config config) {
        this.config = config;
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(config.anthropicApiKey())
                .build();
    }

    /**
     * Generate narration from transcript for the given job.
     *
     * @param transcript plain text transcript
     * @param job        the optimization job (used for prompt overrides and target length)
     * @return narration text with headline and optional &lt;em&gt; tags
     * @throws Exception if template files cannot be read or Claude API fails after all retries
     */
    public String narrate(String transcript, MediaOptimizationJob job) throws Exception {
        transcript = truncateToMaxWords(transcript, config.maxInputWords());
        int transcriptWordCount = transcript.split("\\s+").length;
        int wordTarget = job.targetWordCount(transcriptWordCount);
        LOG.info("Transcript: {} words, compression: {}x, target: {} words",
                transcriptWordCount, job.compressionFactor(), wordTarget);
        String prompt = resolvePrompt(transcript, job, wordTarget);

        String model = config.narratorModel();
        int maxTokens = (int) Math.ceil(transcriptWordCount * TOKENS_PER_WORD * TOKENS_HEADROOM);
        LOG.info("Sending to Claude ({} chars prompt, model: {}, maxTokens: {})...",
                prompt.length(), model, maxTokens);

        Message message = Retry.withRetries(CLAUDE_MAX_ATTEMPTS, CLAUDE_RETRY_DELAY_MS, () ->
                client.messages().create(
                        MessageCreateParams.builder()
                                .model(Model.of(model))
                                .maxTokens(maxTokens)
                                .addUserMessage(prompt)
                                .build()
                )
        );

        return extractText(message);
    }

    /**
     * Generate narration from text for the given text optimization job.
     *
     * @param job the text optimization job (provides text, prompt overrides and target length)
     * @return narration text with headline and optional &lt;em&gt; tags
     * @throws Exception if template files cannot be read or Claude API fails after all retries
     */
    public String narrate(TextOptimizationJob job) throws Exception {
        String text = truncateToMaxWords(job.text(), config.maxInputWords());
        int wordCount = text.split("\\s+").length;
        int wordTarget = job.targetWordCount(wordCount);
        LOG.info("Input text: {} words, compression: {}x, target: {} words",
                wordCount, job.compressionFactor(), wordTarget);
        String prompt = resolvePrompt(text, job, wordTarget);

        String model = config.narratorModel();
        int maxTokens = (int) Math.ceil(wordCount * TOKENS_PER_WORD * TOKENS_HEADROOM);
        LOG.info("Sending to Claude ({} chars prompt, model: {}, maxTokens: {})...",
                prompt.length(), model, maxTokens);

        Message message = Retry.withRetries(CLAUDE_MAX_ATTEMPTS, CLAUDE_RETRY_DELAY_MS, () ->
                client.messages().create(
                        MessageCreateParams.builder()
                                .model(Model.of(model))
                                .maxTokens(maxTokens)
                                .addUserMessage(prompt)
                                .build()
                )
        );

        return extractText(message);
    }

    /**
     * Generate a short title for the given summary text using the provided prompt template.
     * The prompt template receives a single variable: {@code summary}.
     */
    public String generateTitle(String titlePrompt, String summaryText) throws Exception {
        TemplateEngine engine = new TemplateEngine();
        org.thymeleaf.templateresolver.StringTemplateResolver resolver =
                new org.thymeleaf.templateresolver.StringTemplateResolver();
        resolver.setTemplateMode(TemplateMode.TEXT);
        engine.setTemplateResolver(resolver);
        Context ctx = new Context();
        ctx.setVariable("summary", summaryText);
        String prompt = engine.process(titlePrompt, ctx);

        String model = config.narratorModel();
        LOG.info("Generating title with model={}, prompt chars={}", model, prompt.length());
        Message message = Retry.withRetries(CLAUDE_MAX_ATTEMPTS, CLAUDE_RETRY_DELAY_MS, () ->
                client.messages().create(
                        MessageCreateParams.builder()
                                .model(Model.of(model))
                                .maxTokens(100)
                                .addUserMessage(prompt)
                                .build()
                )
        );
        return extractText(message);
    }

    private String resolvePrompt(String transcript, MediaOptimizationJob job, int wordTarget) throws IOException {
        if (job.hasPromptOverride()) {
            LOG.info("Using prompt_override from job");
            return renderInline(job.promptOverride(), transcript, job, wordTarget);
        }

        if (config.narrationPromptFile() != null) {
            LOG.info("Loading prompt from file: {}", config.narrationPromptFile());
            String template = Files.readString(config.narrationPromptFile());
            return renderInline(template, transcript, job, wordTarget);
        }

        throw new IllegalStateException("No prompt configured: set prompt_ref on the subscription");
    }

    private String resolvePrompt(String text, TextOptimizationJob job, int wordTarget) throws IOException {
        if (job.hasPromptOverride()) {
            LOG.info("Using prompt_override from job");
            return renderInline(job.promptOverride(), text, job, wordTarget);
        }

        if (config.narrationPromptFile() != null) {
            LOG.info("Loading prompt from file: {}", config.narrationPromptFile());
            String template = java.nio.file.Files.readString(config.narrationPromptFile());
            return renderInline(template, text, job, wordTarget);
        }

        throw new IllegalStateException("No prompt configured: set prompt_ref on the subscription");
    }

    private String renderInline(String templateContent, String text, TextOptimizationJob job, int wordTarget) {
        TemplateEngine engine = new TemplateEngine();
        org.thymeleaf.templateresolver.StringTemplateResolver resolver =
                new org.thymeleaf.templateresolver.StringTemplateResolver();
        resolver.setTemplateMode(TemplateMode.TEXT);
        engine.setTemplateResolver(resolver);

        Context ctx = buildContext(text, job, wordTarget);
        return engine.process(templateContent, ctx);
    }

    private Context buildContext(String text, TextOptimizationJob job, int wordTarget) {
        Context ctx = new Context();
        ctx.setVariable("wordTarget", wordTarget);
        ctx.setVariable("transcript", text);
        ctx.setVariable("promptExtra", job.promptExtra());
        ctx.setVariable("channelName", job.channelName());
        ctx.setVariable("timeOfDay", capitalizeFirst(job.timeOfDay()));
        ctx.setVariable("date", java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy")));
        return ctx;
    }

    private String renderInline(String templateContent, String transcript, MediaOptimizationJob job, int wordTarget) {
        TemplateEngine engine = new TemplateEngine();
        org.thymeleaf.templateresolver.StringTemplateResolver resolver =
                new org.thymeleaf.templateresolver.StringTemplateResolver();
        resolver.setTemplateMode(TemplateMode.TEXT);
        engine.setTemplateResolver(resolver);

        Context ctx = buildContext(transcript, job, wordTarget);
        return engine.process(templateContent, ctx);
    }

    private Context buildContext(String transcript, MediaOptimizationJob job, int wordTarget) {
        Context ctx = new Context();
        ctx.setVariable("wordTarget", wordTarget);
        ctx.setVariable("transcript", transcript);
        ctx.setVariable("promptExtra", job.promptExtra());
        ctx.setVariable("channelName", job.channelName());
        ctx.setVariable("timeOfDay", capitalizeFirst(job.timeOfDay()));
        ctx.setVariable("date", java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy")));
        return ctx;
    }

    private static String capitalizeFirst(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String truncateToMaxWords(String text, int maxWords) {
        String[] words = text.split("\\s+");
        if (words.length <= maxWords) {
            return text;
        }
        LOG.warn("Transcript exceeds maxInputWords limit ({} > {}), truncating", words.length, maxWords);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxWords; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(words[i]);
        }
        return sb.toString();
    }

    private String extractText(Message message) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : message.content()) {
            if (block.isText()) {
                sb.append(block.asText().text());
            }
        }
        return sb.toString().trim();
    }
}
