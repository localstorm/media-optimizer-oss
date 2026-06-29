package co.kuznetsov.mediapipe.model;

public enum DigestDeliveryTime {
    MORNING,
    EVENING,
    ALL;

    /** Returns true if this channel should be digested for the given job run type. */
    public boolean matches(DigestDeliveryTime jobDeliveryTime) {
        return this == ALL || this == jobDeliveryTime;
    }

    /** Returns true if the channel's delivery time (null = ALL) is compatible with the job's delivery time. */
    public static boolean channelMatches(DigestDeliveryTime channelDeliveryTime, DigestDeliveryTime jobDeliveryTime) {
        if (channelDeliveryTime == null) {
            return true;
        }
        return channelDeliveryTime.matches(jobDeliveryTime);
    }
}
