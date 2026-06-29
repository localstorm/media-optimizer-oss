package co.kuznetsov.mediapipe.worker;

import co.kuznetsov.mediapipe.model.ChannelSubscription;
import co.kuznetsov.mediapipe.model.ChannelVideo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public final class YouTubeChannelVideoFetcher implements ChannelVideoFetcher {

    private static final Logger LOG = LoggerFactory.getLogger(YouTubeChannelVideoFetcher.class);
    private static final String CHANNELS_BASE = "https://www.googleapis.com/youtube/v3/channels";
    private static final String PLAYLIST_ITEMS_BASE = "https://www.googleapis.com/youtube/v3/playlistItems";
    private static final String VIDEOS_BASE = "https://www.googleapis.com/youtube/v3/videos";
    private static final int PAGE_LIMIT = 50;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiKey;
    private final HttpClient httpClient;

    public YouTubeChannelVideoFetcher(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public List<ChannelVideo> fetchNewVideos(ChannelSubscription subscription, long since) {
        String channelId = subscription.getChannelId();

        // Phase 1: resolve uploads playlist ID from channel
        String uploadsPlaylistId = resolveUploadsPlaylistId(channelId);
        if (uploadsPlaylistId == null) {
            LOG.error("Could not resolve uploads playlist for channel {}", channelId);
            return Collections.emptyList();
        }

        // Phase 2: collect video IDs published after `since`, filtering client-side
        List<String> videoIds = collectVideoIds(uploadsPlaylistId, since, channelId);
        if (videoIds.isEmpty()) {
            return Collections.emptyList();
        }

        // Phase 3: fetch full metadata (duration, live status) in batches via videos.list
        List<ChannelVideo> videos = fetchVideoDetails(videoIds);
        LOG.info("Fetched {} new video(s) for channel {} since epoch {}", videos.size(), subscription.getName(), since);
        return Collections.unmodifiableList(videos);
    }

    @Override
    public Optional<ChannelVideo> fetchVideoById(String videoId) {
        List<ChannelVideo> results = fetchVideoDetails(List.of(videoId));
        if (results.isEmpty()) {
            LOG.warn("fetchVideoById returned no result for videoId={}", videoId);
            return Optional.empty();
        }
        return Optional.of(results.get(0));
    }

    private String resolveUploadsPlaylistId(String channelId) {
        String url = CHANNELS_BASE
            + "?part=contentDetails"
            + "&id=" + encode(channelId)
            + "&key=" + encode(apiKey);
        LOG.debug("YouTube channels.list: channelId={}", channelId);

        JsonNode response = get(url);
        if (response == null) {
            return null;
        }

        JsonNode items = response.path("items");
        if (!items.isArray() || items.isEmpty()) {
            LOG.error("channels.list returned no items for channelId={}", channelId);
            return null;
        }

        String uploadsId = items.get(0)
            .path("contentDetails")
            .path("relatedPlaylists")
            .path("uploads")
            .asText(null);

        if (uploadsId == null || uploadsId.isEmpty()) {
            LOG.error("No uploads playlist found for channelId={}", channelId);
        }
        return uploadsId;
    }

    private List<String> collectVideoIds(String uploadsPlaylistId, long since, String channelId) {
        // playlistItems.list returns newest-first; stop paging once we pass the `since` threshold.
        // Collected in reverse (newest-first) then reversed at the end to produce oldest-first.
        List<String> ids = new ArrayList<>();
        String pageToken = null;
        boolean reachedOldItems = false;

        do {
            String url = buildPlaylistItemsUrl(uploadsPlaylistId, pageToken);
            LOG.debug("YouTube playlistItems.list: playlist={} page={}", uploadsPlaylistId, pageToken);

            JsonNode response = get(url);
            if (response == null) {
                break;
            }

            for (JsonNode item : response.path("items")) {
                String videoId = item.path("snippet").path("resourceId").path("videoId").asText(null);
                String publishedAtRaw = item.path("snippet").path("publishedAt").asText(null);

                if (videoId == null || videoId.isEmpty() || publishedAtRaw == null) {
                    continue;
                }

                long publishedMillis = Instant.parse(publishedAtRaw).toEpochMilli();
                if (publishedMillis <= since) {
                    reachedOldItems = true;
                    break;
                }
                ids.add(videoId);
            }

            if (reachedOldItems) {
                break;
            }

            JsonNode nextToken = response.path("nextPageToken");
            pageToken = nextToken.isMissingNode() ? null : nextToken.asText(null);
        } while (pageToken != null);

        LOG.debug("Collected {} candidate video IDs for channel {}", ids.size(), channelId);
        Collections.reverse(ids); // oldest-first
        return ids;
    }

    private List<ChannelVideo> fetchVideoDetails(List<String> videoIds) {
        Map<String, ChannelVideo> byId = new LinkedHashMap<>();
        for (int i = 0; i < videoIds.size(); i += PAGE_LIMIT) {
            List<String> batch = videoIds.subList(i, Math.min(i + PAGE_LIMIT, videoIds.size()));
            String url = buildVideosUrl(batch);
            LOG.debug("YouTube videos.list batch of {}", batch.size());

            JsonNode response = get(url);
            if (response == null) {
                continue;
            }

            for (JsonNode item : response.path("items")) {
                ChannelVideo video = parseVideo(item);
                if (video != null) {
                    byId.put(video.getVideoId(), video);
                }
            }
        }

        return videoIds.stream()
            .filter(byId::containsKey)
            .map(byId::get)
            .collect(Collectors.toList());
    }

    private ChannelVideo parseVideo(JsonNode item) {
        String videoId = item.path("id").asText(null);
        if (videoId == null || videoId.isEmpty()) {
            return null;
        }

        JsonNode snippet = item.path("snippet");
        String title = snippet.path("title").asText("");
        String publishedAtRaw = snippet.path("publishedAt").asText(null);
        Instant publishedAt = publishedAtRaw != null ? Instant.parse(publishedAtRaw) : Instant.EPOCH;

        String liveBroadcastContent = snippet.path("liveBroadcastContent").asText("none");
        if (!"none".equals(liveBroadcastContent)) {
            LOG.debug("Skipping video {} ('{}') — liveBroadcastContent={}", videoId, title, liveBroadcastContent);
            return null;
        }

        String isoDuration = item.path("contentDetails").path("duration").asText("PT0S");
        long durationSeconds = parseIsoDuration(isoDuration);

        return new ChannelVideo(videoId, title, publishedAt, durationSeconds);
    }

    private static long parseIsoDuration(String iso) {
        try {
            return Duration.parse(iso).getSeconds();
        } catch (Exception e) {
            LOG.warn("Could not parse ISO duration '{}': {}", iso, e.getMessage());
            return 0L;
        }
    }

    private String buildPlaylistItemsUrl(String playlistId, String pageToken) {
        StringBuilder sb = new StringBuilder(PLAYLIST_ITEMS_BASE)
            .append("?part=snippet")
            .append("&maxResults=").append(PAGE_LIMIT)
            .append("&playlistId=").append(encode(playlistId))
            .append("&key=").append(encode(apiKey));
        if (pageToken != null) {
            sb.append("&pageToken=").append(encode(pageToken));
        }
        return sb.toString();
    }

    private String buildVideosUrl(List<String> ids) {
        String joined = ids.stream().map(this::encode).collect(Collectors.joining("%2C"));
        return VIDEOS_BASE
            + "?part=snippet%2CcontentDetails"
            + "&id=" + joined
            + "&key=" + encode(apiKey);
    }

    private JsonNode get(String url) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/json")
            .GET()
            .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.error("YouTube API returned HTTP {}: {}", response.statusCode(), response.body());
                return null;
            }
            return MAPPER.readTree(response.body());
        } catch (IOException | InterruptedException e) {
            LOG.error("Failed to call YouTube API", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
