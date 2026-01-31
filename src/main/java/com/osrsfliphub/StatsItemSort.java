package com.osrsfliphub;

public enum StatsItemSort {
    COMPLETION("Completion", "completion"),
    PROFIT("Profit", "profit"),
    ROI("ROI", "roi");

    private final String label;
    private final String apiValue;

    StatsItemSort(String label, String apiValue) {
        this.label = label;
        this.apiValue = apiValue;
    }

    public String getApiValue() {
        return apiValue;
    }

    @Override
    public String toString() {
        return label;
    }
}
