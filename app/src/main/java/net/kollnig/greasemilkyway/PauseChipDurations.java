package net.kollnig.greasemilkyway;

final class PauseChipDurations {
    static final long SECOND_MILLIS = 1_000L;
    static final long MINUTE_MILLIS = 60 * SECOND_MILLIS;

    private PauseChipDurations() {
    }

    static long[] aroundDefault(int defaultMinutes) {
        if (defaultMinutes <= 0) {
            throw new IllegalArgumentException("Default pause duration must be positive");
        }
        long middle = Math.multiplyExact(defaultMinutes, MINUTE_MILLIS);
        long shorter = defaultMinutes == 1
                ? 30 * SECOND_MILLIS : (defaultMinutes / 2L) * MINUTE_MILLIS;
        long longer = Math.multiplyExact(middle, 2);
        return new long[]{shorter, middle, longer};
    }
}
