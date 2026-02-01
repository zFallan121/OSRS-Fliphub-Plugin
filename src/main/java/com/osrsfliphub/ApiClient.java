package com.osrsfliphub;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ApiClient {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String API_BASE_URL = "https://osrs-fliphub-production.up.railway.app";

    private final OkHttpClient httpClient;
    private final Gson gson;
    private final PluginConfig config;

    public ApiClient(OkHttpClient httpClient, Gson gson, PluginConfig config) {
        this.httpClient = httpClient;
        this.gson = gson;
        this.config = config;
    }

    public LinkResponse linkDevice(String licenseKey, String deviceId, String deviceName, String pluginVersion) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("license_key", licenseKey);
        body.put("code", licenseKey);
        body.put("device_id", deviceId);
        body.put("device_name", deviceName);
        body.put("plugin_version", pluginVersion);

        String json = gson.toJson(body);
        Request request = new Request.Builder()
            .url(API_BASE_URL + "/api/plugin/link")
            .post(RequestBody.create(JSON, json))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IllegalStateException("Link failed: " + response.code());
            }
            String responseBody = response.body().string();
            return gson.fromJson(responseBody, LinkResponse.class);
        }
    }

    public LinkResponse refreshSession(String sessionToken) throws Exception {
        Request request = new Request.Builder()
            .url(API_BASE_URL + "/api/plugin/refresh")
            .post(RequestBody.create(JSON, "{}"))
            .addHeader("X-Plugin-Token", sessionToken)
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IllegalStateException("Refresh failed: " + response.code());
            }
            String responseBody = response.body().string();
            return gson.fromJson(responseBody, LinkResponse.class);
        }
    }

    public int sendEvents(String sessionToken, String signingSecret, List<GeEvent> events) throws Exception {
        if (events == null || events.isEmpty()) {
            return 0;
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("schema_version", 1);
        payload.put("sent_at_ms", System.currentTimeMillis());
        payload.put("events", events);

        String json = gson.toJson(payload);
        byte[] bodyBytes = json.getBytes(StandardCharsets.UTF_8);

        String nonce = UUID.randomUUID().toString().replace("-", "");
        String timestamp = String.valueOf(System.currentTimeMillis());
        String bodyHash = Signer.sha256Hex(bodyBytes);

        String canonical = "POST" + "\n" +
            "/api/plugin/events" + "\n" +
            timestamp + "\n" +
            nonce + "\n" +
            bodyHash;

        String signature = Signer.hmacBase64(signingSecret, canonical);

        Request request = new Request.Builder()
            .url(API_BASE_URL + "/api/plugin/events")
            .post(RequestBody.create(JSON, json))
            .addHeader("X-Plugin-Token", sessionToken)
            .addHeader("X-Nonce", nonce)
            .addHeader("X-Timestamp", timestamp)
            .addHeader("X-Signature", signature)
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return response.code();
            }
        }
        return 200;
    }

    public ItemsResponse fetchItems(String sessionToken, String query, int page, int pageSize) throws Exception {
        StringBuilder urlBuilder = new StringBuilder();
        urlBuilder.append(API_BASE_URL).append("/api/plugin/items");
        urlBuilder.append("?page=").append(page).append("&page_size=").append(pageSize);
        if (query != null && !query.trim().isEmpty()) {
            urlBuilder.append("&q=").append(URLEncoder.encode(query.trim(), StandardCharsets.UTF_8));
        }

        Request request = new Request.Builder()
            .url(urlBuilder.toString())
            .get()
            .addHeader("X-Plugin-Token", sessionToken)
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new ApiException("Fetch items failed", response.code());
            }
            String responseBody = response.body().string();
            return gson.fromJson(responseBody, ItemsResponse.class);
        }
    }

    public ItemResponse fetchItem(String sessionToken, int itemId) throws Exception {
        String url = API_BASE_URL + "/api/plugin/item?item_id=" + itemId;
        Request request = new Request.Builder()
            .url(url)
            .get()
            .addHeader("X-Plugin-Token", sessionToken)
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new ApiException("Fetch item failed", response.code());
            }
            String responseBody = response.body().string();
            return gson.fromJson(responseBody, ItemResponse.class);
        }
    }

    public StatsSummaryResponse fetchStatsSummary(String sessionToken, Long sinceMs, Long untilMs) throws Exception {
        StringBuilder urlBuilder = new StringBuilder();
        urlBuilder.append(API_BASE_URL).append("/api/plugin/stats/summary");
        appendStatsQuery(urlBuilder, sinceMs, untilMs);

        Request request = new Request.Builder()
            .url(urlBuilder.toString())
            .get()
            .addHeader("X-Plugin-Token", sessionToken)
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new ApiException("Fetch stats summary failed", response.code());
            }
            String responseBody = response.body().string();
            return gson.fromJson(responseBody, StatsSummaryResponse.class);
        }
    }

    public StatsItemsResponse fetchStatsItems(String sessionToken, Long sinceMs, Long untilMs, Integer limit, StatsItemSort sort) throws Exception {
        StringBuilder urlBuilder = new StringBuilder();
        urlBuilder.append(API_BASE_URL).append("/api/plugin/stats/items");
        boolean hasQuery = appendStatsQuery(urlBuilder, sinceMs, untilMs);
        if (limit != null) {
            urlBuilder.append(hasQuery ? "&" : "?").append("limit=").append(limit);
            hasQuery = true;
        }
        if (sort != null) {
            urlBuilder.append(hasQuery ? "&" : "?").append("sort=").append(sort.getApiValue());
            hasQuery = true;
        }

        Request request = new Request.Builder()
            .url(urlBuilder.toString())
            .get()
            .addHeader("X-Plugin-Token", sessionToken)
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new ApiException("Fetch stats items failed", response.code());
            }
            String responseBody = response.body().string();
            return gson.fromJson(responseBody, StatsItemsResponse.class);
        }
    }

    private boolean appendStatsQuery(StringBuilder builder, Long sinceMs, Long untilMs) {
        boolean hasQuery = false;
        if (sinceMs != null && sinceMs > 0) {
            builder.append("?since_ms=").append(sinceMs);
            hasQuery = true;
        }
        if (untilMs != null && untilMs > 0) {
            builder.append(hasQuery ? "&" : "?").append("until_ms=").append(untilMs);
            hasQuery = true;
        }
        return hasQuery;
    }

    public static class LinkResponse {
        public String session_token;
        public String session_expires_at;
        public String signing_secret;
    }

    public static class ItemsResponse {
        public List<FlipHubItem> items;
        public int page;
        public int page_size;
        public int total_items;
        public int total_pages;
        public long as_of_ms;
        public Long price_cache_ms;
    }

    public static class ItemResponse {
        public FlipHubItem item;
        public long as_of_ms;
        public Long price_cache_ms;
    }

    public static class StatsSummaryResponse {
        public long as_of_ms;
        public StatsSummary summary;
    }

    public static class StatsItemsResponse {
        public long as_of_ms;
        public List<StatsItem> items;
    }

    public static class ApiException extends Exception {
        public final int statusCode;

        public ApiException(String message, int statusCode) {
            super(message + ": " + statusCode);
            this.statusCode = statusCode;
        }
    }
}
