package co.kuznetsov.medialib.index;

import java.util.List;

/**
 * A page of media records returned by MediaIndex.listMediaForChannel.
 * nextCursor is null when no more pages remain.
 * The number of items may be less than the requested limit.
 * The items list is unmodifiable.
 */
public record MediaPage(
        List<MediaRecord> items,
        String nextCursor
) {
    /**
     * Compact constructor that defensively copies the items list.
     *
     * @param items      list of media records (copied to an unmodifiable list)
     * @param nextCursor opaque pagination cursor, or {@code null} if no more pages
     */
    public MediaPage {
        items = List.copyOf(items);
    }
}
