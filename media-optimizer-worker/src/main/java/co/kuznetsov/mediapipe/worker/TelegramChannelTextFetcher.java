package co.kuznetsov.mediapipe.worker;

import co.kuznetsov.mediapipe.model.ChannelSubscription;
import co.kuznetsov.mediapipe.model.ChannelText;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public final class TelegramChannelTextFetcher implements ChannelTextFetcher {

    private static final Logger LOG = LoggerFactory.getLogger(TelegramChannelTextFetcher.class);
    private static final long MAX_LOOKBACK_MILLIS = 24 * 60 * 60 * 1000L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public List<ChannelText> fetchNewTexts(ChannelSubscription subscription, long since) {
        String username = subscription.getChannelId();
        long effectiveSince = Math.max(since, System.currentTimeMillis() - MAX_LOOKBACK_MILLIS);
        long sinceEpochSeconds = effectiveSince / 1000;

        File outputFile;
        try {
            outputFile = File.createTempFile("tdl-export-", ".json");
            outputFile.deleteOnExit();
        } catch (IOException e) {
            LOG.error("Failed to create temp file for tdl export: {}", e.getMessage());
            return Collections.emptyList();
        }

        try {
            List<String> cmd = List.of(
                "tdl", "chat", "export",
                "-c", username,
                "-T", "time",
                "-i", String.valueOf(sinceEpochSeconds),
                "--all", "--raw",
                "-o", outputFile.getAbsolutePath()
            );

            Process process = new ProcessBuilder(cmd)
                .redirectErrorStream(false)
                .start();

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                LOG.error("tdl exited with code {} for channel @{}", exitCode, username);
                return Collections.emptyList();
            }

            return parseOutput(outputFile, username, effectiveSince);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.error("Failed to run tdl for channel @{}: {}", username, e.getMessage());
            return Collections.emptyList();
        } finally {
            if (!outputFile.delete()) {
                LOG.warn("Failed to delete temp file: {}", outputFile.getAbsolutePath());
            }
        }
    }

    @Override
    public Optional<ChannelText> fetchTextById(String textId) {
        // Not supported with tdl subprocess approach — textId is channel:messageId
        LOG.warn("fetchTextById not supported in tdl-based fetcher, textId={}", textId);
        return Optional.empty();
    }

    private List<ChannelText> parseOutput(File file, String username, long effectiveSinceMillis) {
        JsonNode root;
        try {
            root = MAPPER.readTree(file);
        } catch (IOException e) {
            LOG.error("Failed to parse tdl JSON output for @{}: {}", username, e.getMessage());
            return Collections.emptyList();
        }

        JsonNode messages = root.path("messages");
        if (!messages.isArray()) {
            LOG.warn("No messages array in tdl output for @{}", username);
            return Collections.emptyList();
        }

        long chatId = root.path("id").asLong(0);
        List<ChannelText> result = new ArrayList<>();

        for (JsonNode msg : messages) {
            JsonNode raw = msg.path("raw");
            if (raw.isMissingNode()) {
                continue;
            }

            long dateSeconds = raw.path("Date").asLong(0);
            if (dateSeconds == 0) {
                continue;
            }
            long publishedMillis = dateSeconds * 1000;
            if (publishedMillis <= effectiveSinceMillis) {
                continue;
            }

            String content = ChannelFetchJob.stripTitle(raw.path("Message").asText("").strip());
            if (content.isEmpty()) {
                continue;
            }

            long messageId = raw.path("ID").asLong(msg.path("id").asLong(0));
            String textId = chatId + ":" + messageId;
            String title = extractTitle(content);
            Instant publishedAt = Instant.ofEpochSecond(dateSeconds);

            result.add(new ChannelText(textId, title, publishedAt, content));
        }

        LOG.info("Fetched {} text item(s) for Telegram channel @{} since epoch {}",
            result.size(), username, effectiveSinceMillis);
        return Collections.unmodifiableList(result);
    }

    private String extractTitle(String content) {
        String firstLine = content.lines().findFirst().orElse("").strip();
        String raw = firstLine.isEmpty()
            ? (content.length() > 80 ? content.substring(0, 80) + "..." : content)
            : (firstLine.length() > 80 ? firstLine.substring(0, 80) + "..." : firstLine);
        return ChannelFetchJob.stripTitle(raw);
    }
}
