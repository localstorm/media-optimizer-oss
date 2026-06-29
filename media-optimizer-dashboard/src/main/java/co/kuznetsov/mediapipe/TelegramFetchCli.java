package co.kuznetsov.mediapipe;

import co.kuznetsov.mediapipe.model.ChannelSubscription;
import co.kuznetsov.mediapipe.model.ChannelText;
import co.kuznetsov.mediapipe.model.SourceType;
import co.kuznetsov.mediapipe.worker.TelegramChannelTextFetcher;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * CLI tool: fetches the last 24 hours of posts from a Telegram channel and prints them to stdout.
 *
 * Usage: TelegramFetchCli <channel_username>
 *
 * Example: TelegramFetchCli mychannel
 */
public final class TelegramFetchCli {

    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private TelegramFetchCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: TelegramFetchCli <channel_username>");
            System.exit(1);
        }

        String username = args[0];

        TelegramChannelTextFetcher fetcher = new TelegramChannelTextFetcher();

        ChannelSubscription subscription = new ChannelSubscription();
        subscription.setChannelId(username);
        subscription.setName(username);
        subscription.setSourceType(SourceType.TELEGRAM);

        long since = Instant.now().minusSeconds(24 * 60 * 60).toEpochMilli();
        List<ChannelText> texts = fetcher.fetchNewTexts(subscription, since);

        if (texts.isEmpty()) {
            System.out.println("No posts in the last 24 hours for @" + username);
            return;
        }

        System.out.println("=== @" + username + " — last 24h (" + texts.size() + " posts) ===");
        System.out.println();
        for (ChannelText text : texts) {
            System.out.println("--- " + FMT.format(text.getPublishedAt()) + " UTC ---");
            System.out.println(text.getContent());
            System.out.println();
        }
    }
}
