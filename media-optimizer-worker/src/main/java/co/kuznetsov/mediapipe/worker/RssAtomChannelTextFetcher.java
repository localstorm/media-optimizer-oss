package co.kuznetsov.mediapipe.worker;

import co.kuznetsov.mediapipe.model.ChannelSubscription;
import co.kuznetsov.mediapipe.model.ChannelText;
import co.kuznetsov.mediapipe.model.OptimizerConfig;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import io.github.furstenheim.CopyDown;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;

public final class RssAtomChannelTextFetcher implements ChannelTextFetcher {

    private static final Logger LOG = LoggerFactory.getLogger(RssAtomChannelTextFetcher.class);

    private final OptimizerConfig config;
    private final CopyDown converter = new CopyDown();

    public RssAtomChannelTextFetcher(OptimizerConfig config) {
        this.config = config;
    }

    @Override
    public List<ChannelText> fetchNewTexts(ChannelSubscription subscription, long since) {
        String channelId = subscription.getChannelId();

        Optional<String> urlOpt = config.findSourceUrl(channelId);
        if (urlOpt.isEmpty()) {
            LOG.error("No source URL configured for RSS_ATOM channel '{}', skipping", subscription.getName());
            return Collections.emptyList();
        }

        String feedUrl = urlOpt.get();
        SyndFeed feed;
        try {
            URL url = URI.create(feedUrl).toURL();
            SyndFeedInput input = new SyndFeedInput();
            input.setAllowDoctypes(true);
            feed = input.build(new XmlReader(url.openStream(), true));
        } catch (IOException | FeedException e) {
            LOG.error("Failed to fetch RSS/Atom feed for channel '{}' at {}: {}", subscription.getName(), feedUrl, e.getMessage());
            return Collections.emptyList();
        }

        List<ChannelText> result = new ArrayList<>();
        for (SyndEntry entry : feed.getEntries()) {
            String textId = resolveId(entry);
            if (textId == null || textId.isBlank()) {
                LOG.warn("Skipping entry with no URI or link in feed for channel '{}'", subscription.getName());
                continue;
            }

            Instant publishedAt = resolvePublishedAt(entry);
            if (publishedAt == null) {
                LOG.warn("Skipping entry '{}' with no publish date in feed for channel '{}'", textId, subscription.getName());
                continue;
            }

            if (publishedAt.toEpochMilli() <= since) {
                continue;
            }

            String title = entry.getTitle() != null ? entry.getTitle().strip() : "";
            String content = resolveContent(entry);
            if (content.isBlank()) {
                LOG.debug("Skipping entry '{}' with empty content for channel '{}'", textId, subscription.getName());
                continue;
            }

            result.add(new ChannelText(textId, title, publishedAt, content));
        }

        // Rome returns entries newest-first; reverse so caller gets oldest-first
        Collections.reverse(result);

        LOG.info("Fetched {} entry(s) for RSS/Atom channel '{}' since epoch {}", result.size(), subscription.getName(), since);
        return Collections.unmodifiableList(result);
    }

    @Override
    public Optional<ChannelText> fetchTextById(String textId) {
        LOG.warn("fetchTextById not supported for RSS/Atom fetcher, textId={}", textId);
        return Optional.empty();
    }

    private String resolveId(SyndEntry entry) {
        if (entry.getUri() != null && !entry.getUri().isBlank()) {
            return entry.getUri().strip();
        }
        if (entry.getLink() != null && !entry.getLink().isBlank()) {
            return entry.getLink().strip();
        }
        return null;
    }

    private Instant resolvePublishedAt(SyndEntry entry) {
        Date date = entry.getPublishedDate() != null ? entry.getPublishedDate() : entry.getUpdatedDate();
        return date != null ? date.toInstant() : null;
    }

    private String resolveContent(SyndEntry entry) {
        String html = null;
        if (entry.getContents() != null && !entry.getContents().isEmpty()) {
            String value = entry.getContents().get(0).getValue();
            if (value != null && !value.isBlank()) {
                html = value;
            }
        }
        if (html == null && entry.getDescription() != null) {
            String value = entry.getDescription().getValue();
            if (value != null && !value.isBlank()) {
                html = value;
            }
        }
        if (html == null) {
            return "";
        }
        return converter.convert(html).strip();
    }
}
