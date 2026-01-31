package com.osrsfliphub;

public enum StatsRange {
    SESSION("Session", RangeType.SESSION, 0),
    LAST_HOUR("Last 1h", RangeType.RELATIVE, 1),
    LAST_4H("Last 4h", RangeType.RELATIVE, 4),
    LAST_24H("Last 24h", RangeType.RELATIVE, 24),
    LAST_7D("Last 7d", RangeType.RELATIVE, 168),
    ALL_TIME("All time", RangeType.ALL_TIME, 0);

    private enum RangeType {
        SESSION,
        RELATIVE,
        ALL_TIME
    }

    private final String label;
    private final RangeType type;
    private final int hours;

    StatsRange(String label, RangeType type, int hours) {
        this.label = label;
        this.type = type;
        this.hours = hours;
    }

    public Long getSinceMs(long sessionStartMs, long nowMs) {
        if (type == RangeType.ALL_TIME) {
            return null;
        }
        if (type == RangeType.SESSION) {
            return sessionStartMs > 0 ? sessionStartMs : null;
        }
        if (hours <= 0) {
            return null;
        }
        long deltaMs = hours * 60L * 60L * 1000L;
        return Math.max(0L, nowMs - deltaMs);
    }

    @Override
    public String toString() {
        return label;
    }
}
