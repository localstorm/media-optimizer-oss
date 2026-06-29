package co.kuznetsov.mediapipe.worker;

import co.kuznetsov.mediapipe.model.ChannelSubscription;
import co.kuznetsov.mediapipe.model.ChannelVideo;

import java.util.List;

public interface ChannelVideoFetcher {

    /**
     * Fetches videos published after the given timestamp.
     *
     * @param subscription the channel to fetch content for
     * @param since        epoch millis; only items published after this time are returned
     * @return ordered list of videos (oldest first)
     */
    List<ChannelVideo> fetchNewVideos(ChannelSubscription subscription, long since);

    /**
     * Fetches metadata for a single video by its ID.
     *
     * @param videoId the video ID to look up
     * @return the video metadata, or empty if not found or fetch failed
     */
    java.util.Optional<ChannelVideo> fetchVideoById(String videoId);
}
