package co.kuznetsov.mediapipe.worker;

import co.kuznetsov.mediapipe.model.ChannelSubscription;
import co.kuznetsov.mediapipe.model.ChannelText;

import java.util.List;
import java.util.Optional;

public interface ChannelTextFetcher {

    /**
     * Fetches text items published after the given timestamp.
     *
     * @param subscription the channel to fetch content for
     * @param since        epoch millis; only items published after this time are returned
     * @return ordered list of text items (oldest first)
     */
    List<ChannelText> fetchNewTexts(ChannelSubscription subscription, long since);

    /**
     * Fetches a single text item by its ID.
     *
     * @param textId the text ID to look up
     * @return the text item, or empty if not found or fetch failed
     */
    Optional<ChannelText> fetchTextById(String textId);
}
