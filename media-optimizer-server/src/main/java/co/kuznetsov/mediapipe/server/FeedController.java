package co.kuznetsov.mediapipe.server;

import co.kuznetsov.medialib.index.MediaIndex;
import co.kuznetsov.medialib.index.MediaRecord;
import co.kuznetsov.mediapipe.model.ChannelSubscription;
import co.kuznetsov.mediapipe.model.SourceType;
import co.kuznetsov.mediapipe.worker.ConfigHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@Controller
public class FeedController {

    private static final Logger LOG = LoggerFactory.getLogger(FeedController.class);
    private static final Duration PRESIGN_TTL = Duration.ofHours(12);
    private static final int FEED_WINDOW_DAYS = 7;

    private final MediaIndex mediaIndex;
    private final S3Presigner presigner;
    private final String s3Bucket;
    private final ConfigHolder configHolder;

    public FeedController(
            MediaIndex mediaIndex,
            S3Presigner presigner,
            ConfigHolder configHolder,
            @Value("${optimizer.s3.bucket}") String s3Bucket) {
        this.mediaIndex = mediaIndex;
        this.presigner = presigner;
        this.configHolder = configHolder;
        this.s3Bucket = s3Bucket;
    }

    @GetMapping("/")
    public String feed(Model model) {
        List<FeedItem> items = loadFeedItems();

        List<ChannelSubscription> subscriptions = configHolder.get().getSubscriptions();
        Set<String> pinnedNames = subscriptions == null ? Set.of() : subscriptions.stream()
            .filter(ChannelSubscription::isPinned)
            .map(ChannelSubscription::getName)
            .collect(java.util.stream.Collectors.toSet());

        Set<String> allChannelNames = items.stream()
            .map(FeedItem::channelName)
            .filter(n -> n != null && !n.isBlank())
            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));

        List<String> channels = Stream.concat(
            allChannelNames.stream().filter(pinnedNames::contains).sorted(),
            allChannelNames.stream().filter(n -> !pinnedNames.contains(n)).sorted()
        ).toList();

        List<String> submissionChannels = subscriptions == null ? List.of() : Stream.concat(
            subscriptions.stream()
                .filter(s -> s.getSourceType() == SourceType.SUBMISSIONS && s.isPinned())
                .map(ChannelSubscription::getName).sorted(),
            subscriptions.stream()
                .filter(s -> s.getSourceType() == SourceType.SUBMISSIONS && !s.isPinned())
                .map(ChannelSubscription::getName).sorted()
        ).toList();

        model.addAttribute("items", items);
        model.addAttribute("channels", channels);
        model.addAttribute("submissionChannels", submissionChannels);
        return "feed";
    }

    @GetMapping(value = "/api/feed", produces = "application/json")
    @ResponseBody
    public List<FeedItem> feedJson() {
        return loadFeedItems();
    }

    @PostMapping("/api/feed/{channelId}/{videoId}/played")
    @ResponseBody
    public ResponseEntity<Void> markPlayed(
            @PathVariable("channelId") String channelId,
            @PathVariable("videoId") String videoId) {
        LOG.info("markPlayed: channelId={} videoId={}", channelId, videoId);
        mediaIndex.markFeedPlayed(channelId, videoId);
        return ResponseEntity.ok().build();
    }

    private List<FeedItem> loadFeedItems() {
        List<MediaRecord> records = mediaIndex.queryRecentFeed(FEED_WINDOW_DAYS);
        List<FeedItem> items = records.stream()
            .map(record -> {
                String audioUrl = null;
                if (record.outputAudioUrl() != null && !record.outputAudioUrl().isBlank()) {
                    audioUrl = presign(record.outputAudioUrl());
                }
                return new FeedItem(
                    record.channelName(),
                    record.outputTitle() != null ? record.outputTitle() : record.inputTitle(),
                    record.summaryText(),
                    record.outputTime(),
                    record.publishedAtFormatted(),
                    record.publishedAt(),
                    record.createdAt() > 0 ? record.createdAt()
                        : (record.publishedAt() != null ? record.publishedAt() : 0L),
                    audioUrl,
                    record.channelId(),
                    record.videoId(),
                    originalUrl(record.sourceType(), record.videoId()),
                    record.isPlayed()
                );
            })
            .sorted(Comparator.comparingLong(FeedItem::createdAtMillis).reversed())
            .toList();
        return items;
    }

    private static String originalUrl(String sourceType, String videoId) {
        if ("YOUTUBE".equals(sourceType) && videoId != null && !videoId.isBlank()) {
            return "https://www.youtube.com/watch?v=" + videoId;
        }
        return null;
    }

    private String presign(String s3Key) {
        GetObjectRequest getRequest = GetObjectRequest.builder()
            .bucket(s3Bucket)
            .key(s3Key)
            .build();
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
            .signatureDuration(PRESIGN_TTL)
            .getObjectRequest(getRequest)
            .build())
            .url()
            .toString();
    }

    public record FeedItem(
        String channelName,
        String title,
        String summary,
        String duration,
        String publishedAt,
        Long publishedAtMillis,
        long createdAtMillis,
        String audioUrl,
        String channelId,
        String videoId,
        String originalUrl,
        boolean isPlayed
    ) { }
}
