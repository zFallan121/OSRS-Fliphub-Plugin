package com.osrsfliphub;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.awt.Color;
import java.lang.reflect.Type;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.FontID;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Point;
import net.runelite.api.ScriptID;
import net.runelite.api.VarPlayer;
import net.runelite.api.events.PostClientTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.http.api.item.ItemPrice;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
    name = "FlipHub OSRS (Dev)",
    description = "Read-only GE offer lifecycle capture for FlipHub",
    configName = "fliphub_dev",
    tags = {"ge", "flipping", "analytics"},
    hidden = false,
    developerPlugin = false
)
public class GeLifecyclePlugin extends Plugin {
    private static final int MAX_BATCH_SIZE = 200;
    private static final int DEFAULT_ITEMS_PAGE_SIZE = 8;
    private static final int BOOKMARK_ITEMS_PAGE_SIZE = 200;
    private static final Color PRICE_LABEL_COLOR = new Color(91, 159, 237);
    private static final String WIKI_LATEST_URL = "https://prices.runescape.wiki/api/v1/osrs/latest";
    private static final String WIKI_USER_AGENT = "FlipHub OSRS Plugin (contact: support@fliphub.app)";
    private static final long WIKI_CACHE_TTL_MS = 2 * 60 * 1000;
    private static final long WIKI_MIN_REFRESH_MS = 60_000L;
    private static final long LOGIN_GRACE_MS = 60_000L;
    private static final long SUGGESTION_UPDATE_INTERVAL_MS = 250L;
    private static final String PRICE_SUGGESTION_WIDGET_NAME = "FlipHub Current Price";
    private static final String LIMIT_SUGGESTION_WIDGET_NAME = "FlipHub Remaining Limit";
    private static final long OFFER_POLL_INTERVAL_MS = 250L;
    private static final long LOCAL_TRADES_LOAD_RETRY_MS = 1000L;
    private static final long PROFILE_WATCH_DEBOUNCE_MS = 1000L;
    private static final long ACCOUNTWIDE_KEY = 0L;
    private static final String ACCOUNTWIDE_KEY_STRING = "accountwide";
    private static final String PROFILE_SELECTION_MODE_KEY = "profileSelectionMode";
    private static final String PROFILE_SELECTED_KEY = "selectedProfileKey";
    private static final String PROFILE_DIR_NAME = "fliphub";
    private static final String LEGACY_PROFILE_DIR_NAME = "fliphub";
    private static final String LEGACY_CONFIG_GROUP = "fliphub";
    private static final String[] OFFER_STATUS_MARKERS = new String[] {
        "offer status",
        "you have bought",
        "you have sold",
        "bought a total",
        "sold a total"
    };
    private static final Logger log = LoggerFactory.getLogger(GeLifecyclePlugin.class);

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private PluginConfig config;

    @Inject
    private ConfigManager configManager;

    @Inject
    private OkHttpClient httpClient;

    @Inject
    private Gson gson;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private ItemManager itemManager;

    @Inject
    private OverlayManager overlayManager;

    private ApiClient apiClient;
    private ScheduledExecutorService scheduler;
    private final Queue<GeEvent> eventQueue = new ConcurrentLinkedQueue<>();
    private final Map<Integer, OfferSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<Integer, OfferUpdateStamp> offerUpdateStamps = new ConcurrentHashMap<>();
    private final Set<Integer> bookmarkedItems = ConcurrentHashMap.newKeySet();
    private final Set<Integer> hiddenItems = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> itemNameLookupCache = new ConcurrentHashMap<>();
    private volatile Integer offerPreviewItemId;
    private volatile String offerPreviewName;
    private volatile FlipHubItem offerPreviewItem;
    private Widget priceSuggestionWidget;
    private Widget limitSuggestionWidget;
    private Widget cachedPricePromptWidget;
    private Widget cachedQuantityPromptWidget;
    private Integer lastSuggestedPrice;
    private Boolean lastSuggestedIsBuy;
    private Integer lastSuggestedLimit;
    private volatile boolean suggestionDirty;
    private long lastSuggestionUpdateMs;
    private Integer newOfferTypeBuyValue;
    private Integer newOfferTypeSellValue;
    private volatile long lastLoginMs;
    private FlipHubPanel panel;
    private NavigationButton navButton;
    private final AtomicBoolean refreshInFlight = new AtomicBoolean(false);
    private final AtomicBoolean refreshQueued = new AtomicBoolean(false);
    private final Set<Long> loadedProfiles = ConcurrentHashMap.newKeySet();
    private final Map<Long, Long> loadedProfileFileMs = new ConcurrentHashMap<>();
    private final Map<Long, String> profileDisplayNames = new ConcurrentHashMap<>();
    private final AtomicBoolean accountwideDirty = new AtomicBoolean(true);
    private final Map<Long, LocalStatsCache> statsCacheByAccount = new ConcurrentHashMap<>();
    private volatile String currentQuery = "";
    private volatile int currentPage = 1;
    private volatile boolean bookmarkFilterEnabled = false;
    private volatile boolean panelVisible;
    private final AtomicBoolean statsRefreshInFlight = new AtomicBoolean(false);
    private volatile StatsRange currentStatsRange = StatsRange.SESSION;
    private volatile StatsItemSort currentStatsSort = StatsItemSort.COMPLETION;
    private final Object localStatsLock = new Object();
    private final Map<Long, List<LocalTradeDelta>> localTradeDeltasByAccount = new HashMap<>();
    private final Map<Long, Long> localSessionStartByAccount = new HashMap<>();
    private final Map<Integer, String> itemNameCache = new ConcurrentHashMap<>();
    private boolean localTradesLoadedThisLogin = false;
    private boolean localTradesPendingNameKey = false;
    private long localTradesLastLoadAttemptMs = 0L;
    private long lastMergedAccountHash = -1L;
    private long lastMergedNameKey = -1L;
    private volatile String selectedProfileKey = ACCOUNTWIDE_KEY_STRING;
    private volatile boolean manualProfileSelection = false;
    private static final int MAX_LOCAL_TRADES = 5000;
    private static final long LOCAL_EVENT_BUCKET_MS = 600L;
    private static final String[] OFFER_SETUP_BLOCKERS = new String[] {
        "choose an item",
        "click the icon",
        "select an offer slot",
        "set up or view an offer"
    };
    private static final String[] OFFER_TEXT_SKIP_PREFIX = new String[] {
        "buy offer",
        "sell offer"
    };
    private static final String[] ITEM_NAME_EXCLUDES = new String[] {
        "offer status",
        "buy offer",
        "sell offer",
        "quantity",
        "price per item",
        "coins",
        "history",
        "you have"
    };
    private final Object geLimitLock = new Object();
    private final Map<Integer, Integer> geLimitCache = new HashMap<>();
    private final Set<Integer> geLimitPending = new HashSet<>();
    private final Object wikiPriceLock = new Object();
    private final Map<Integer, WikiPriceEntry> wikiLatestCache = new HashMap<>();
    private volatile long wikiLatestFetchedMs;
    private final AtomicBoolean wikiFetchInFlight = new AtomicBoolean(false);
    private final AtomicLong wikiLastAttemptMs = new AtomicLong(0L);
    private ScheduledFuture<?> wikiFetchTask;
    private WatchService profileWatchService;
    private Thread profileWatchThread;
    private final AtomicBoolean profileWatchRunning = new AtomicBoolean(false);
    private final Map<Long, ScheduledFuture<?>> pendingProfileReloads = new ConcurrentHashMap<>();
    private final Map<WatchKey, Path> profileWatchRoots = new ConcurrentHashMap<>();
    private final AtomicBoolean legacyProfilesMigrated = new AtomicBoolean(false);
    private final Object legacyLocalTradesLock = new Object();
    private volatile Map<String, String> legacyLocalTradesCache;
    private final Map<Long, String> legacyNameKeysByHash = new ConcurrentHashMap<>();
    private GeOfferTimerOverlay offerTimerOverlay;

    @Provides
    PluginConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(PluginConfig.class);
    }

    @Override
    protected void startUp() {
        log.info("FlipHub OSRS plugin loaded");
        loadBookmarks();
        loadHiddenItems();
        loadOfferUpdateTimes();
        loadProfileSelectionState();
        ensureProfileLoaded(ACCOUNTWIDE_KEY);
        if (client != null && client.getGameState() == GameState.LOGGED_IN) {
            lastLoginMs = System.currentTimeMillis();
        }
        panel = new FlipHubPanel(itemManager, new FlipHubPanel.PanelListener() {
            @Override
            public void onSearchChanged(String query) {
                currentQuery = query == null ? "" : query;
                currentPage = 1;
                refreshPanelData();
            }

            @Override
            public void onPageChanged(int page) {
                currentPage = Math.max(1, page);
                refreshPanelData();
            }

            @Override
            public void onBookmarkFilterChanged(boolean enabled) {
                bookmarkFilterEnabled = enabled;
                currentPage = 1;
                refreshPanelData();
            }

            @Override
            public void onStatsRangeChanged(StatsRange range) {
                currentStatsRange = range != null ? range : StatsRange.SESSION;
                refreshStatsData();
            }

            @Override
            public void onStatsSortChanged(StatsItemSort sort) {
                currentStatsSort = sort != null ? sort : StatsItemSort.COMPLETION;
                refreshStatsData();
            }

            @Override
            public void onProfileSelected(String profileKey) {
                if (profileKey == null || profileKey.trim().isEmpty()) {
                    return;
                }
                manualProfileSelection = true;
                selectedProfileKey = profileKey.trim();
                persistProfileSelectionState();
                ensureSelectedProfileLoaded();
                updateProfileOptionsUI();
                updateProfileHeader();
                triggerPanelRefresh();
                triggerStatsRefresh();
            }
        }, new FlipHubPanel.BookmarkStore() {
            @Override
            public boolean isBookmarked(int itemId) {
                return bookmarkedItems.contains(itemId);
            }

            @Override
            public void toggleBookmark(int itemId) {
                if (bookmarkedItems.contains(itemId)) {
                    bookmarkedItems.remove(itemId);
                } else {
                    bookmarkedItems.add(itemId);
                }
                persistBookmarks();
            }
        }, new FlipHubPanel.HiddenItemStore() {
            @Override
            public boolean isHidden(int itemId) {
                return hiddenItems.contains(itemId);
            }

            @Override
            public void hideItem(int itemId) {
                if (itemId <= 0) {
                    return;
                }
                if (hiddenItems.add(itemId)) {
                    persistHiddenItems();
                }
            }
        }, config);
        updateProfileOptionsUI();
        updateProfileHeader();
        BufferedImage icon = panel.buildNavIcon();
        navButton = NavigationButton.builder()
            .tooltip("FlipHub OSRS")
            .icon(icon)
            .panel(panel)
            .priority(6)
            .build();
        clientToolbar.addNavigation(navButton);
        offerTimerOverlay = new GeOfferTimerOverlay(client, config, this);
        overlayManager.add(offerTimerOverlay);

        apiClient = new ApiClient(httpClient, gson, config);
        ensureDeviceId();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::flushEvents, 2, 2, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::refreshPanelData, 5, 60, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::refreshStatsData, 5, 60, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::pollOfferSetupItem, 1, OFFER_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        scheduler.execute(this::refreshPanelData);
        scheduler.execute(this::refreshStatsData);
        startWikiFetcher();
        startProfileWatcher();

        String linkInput = getLinkInput();
        if (linkInput != null && !linkInput.trim().isEmpty()) {
            attemptLink(linkInput.trim());
        }
    }

    @Override
    protected void shutDown() {
        if (navButton != null) {
            clientToolbar.removeNavigation(navButton);
        }
        if (offerTimerOverlay != null) {
            overlayManager.remove(offerTimerOverlay);
            offerTimerOverlay = null;
        }
        if (clientThread != null) {
            clientThread.invokeLater(this::clearPriceSuggestion);
        }
        stopWikiFetcher();
        stopProfileWatcher();
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        flushEvents();
        snapshots.clear();
        persistOfferUpdateTimes();
        offerUpdateStamps.clear();
        eventQueue.clear();
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!"fliphub_dev".equals(event.getGroup())) {
            return;
        }
        if ("licenseKey".equals(event.getKey()) || "linkCode".equals(event.getKey())) {
            String linkInput = getLinkInput();
            if (linkInput != null && !linkInput.trim().isEmpty()) {
                attemptLink(linkInput.trim());
            }
        }
        if ("unlinkNow".equals(event.getKey())) {
            if (!config.unlinkNow()) {
                return;
            }
            configManager.setConfiguration("fliphub_dev", "sessionToken", "");
            configManager.setConfiguration("fliphub_dev", "signingSecret", "");
            configManager.setConfiguration("fliphub_dev", "licenseKey", "");
            configManager.setConfiguration("fliphub_dev", "linkCode", "");
            configManager.setConfiguration("fliphub_dev", "unlinkNow", false);
            if (panel != null) {
                updateProfileHeader();
            }
            triggerStatsRefresh();
        }
        if ("bookmarks".equals(event.getKey())) {
            loadBookmarks();
            if (panel != null) {
                panel.refreshBookmarks();
            }
        }
        if ("hiddenItems".equals(event.getKey())) {
            loadHiddenItems();
            if (panel != null) {
                panel.refreshBookmarks();
            }
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() != GameState.LOGGED_IN) {
            if (panel != null) {
                updateProfileOptionsUI();
                updateProfileHeader();
            }
            return;
        }
        lastLoginMs = System.currentTimeMillis();
        updateLocalAccountSessionStart();
        updateProfileForLogin();
        primeOfferSnapshots();
        if (config.sessionToken() == null || config.sessionToken().isEmpty()) {
            localTradesLoadedThisLogin = false;
            localTradesPendingNameKey = false;
            localTradesLastLoadAttemptMs = 0L;
            scheduleLocalTradesLoad();
            refreshWikiLatestPrices();
            requestGeLimits(collectKnownItemIds());
        }
        String linkInput = getLinkInput();
        if (linkInput != null && !linkInput.trim().isEmpty()) {
            attemptLink(linkInput.trim());
        }
        boolean visible = isPanelVisible();
        panelVisible = visible;
        if (visible) {
            triggerPanelRefresh();
            triggerStatsRefresh();
        }
    }

    @Subscribe
    public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event) {
        GrandExchangeOffer offer = event.getOffer();
        int slot = event.getSlot();
        int offerItemId = offer.getItemId();
        if (offerItemId > 0 && hiddenItems.remove(offerItemId)) {
            persistHiddenItems();
        }

        OfferSnapshot prev = snapshots.get(slot);
        OfferSnapshot next = OfferSnapshot.fromOffer(slot, offer, prev);
        snapshots.put(slot, next);
        trackOfferUpdate(slot, prev, next);
        if (config.sessionToken() == null || config.sessionToken().isEmpty()) {
            long accountKey = resolveAccountHash();
            if (accountKey > 0) {
                ensureLocalTradesLoaded(accountKey);
            }
        }

        if (next.state.equals(GrandExchangeOfferState.EMPTY.name())) {
            return;
        }

        OfferUpdateStamp stamp = offerUpdateStamps.get(slot);
        boolean prevIsBaseline = prev == null
            || prev.itemId <= 0
            || GrandExchangeOfferState.EMPTY.name().equals(prev.state);
        boolean usedBaseline = false;
        int deltaQty;
        long deltaGp;
        if (prevIsBaseline && stamp != null && stampMatchesSnapshot(stamp, next)) {
            usedBaseline = true;
            deltaQty = Math.max(0, next.filledQty - stamp.filledQty);
            deltaGp = computeDeltaGpFromBaseline(next, stamp.spentGp, deltaQty);
            if (deltaQty == 0 && deltaGp == 0) {
                return;
            }
        } else {
            deltaQty = prev != null ? Math.max(0, next.filledQty - prev.filledQty) : Math.max(0, next.filledQty);
            deltaGp = computeDeltaGp(next, prev, deltaQty);
        }

        String eventType = determineEventType(prev, next);
        boolean forcedCompletionFromEmpty = false;
        OfferSnapshot eventSnapshot = next;
        if (eventType == null) {
            if (prev != null
                && GrandExchangeOfferState.EMPTY.name().equals(next.state)
                && (GrandExchangeOfferState.BOUGHT.name().equals(prev.state)
                    || GrandExchangeOfferState.SOLD.name().equals(prev.state))) {
                eventType = "OFFER_COMPLETED";
                forcedCompletionFromEmpty = true;
                eventSnapshot = prev;
            } else {
                return;
            }
        }
        if (usedBaseline && "OFFER_PLACED".equals(eventType)) {
            eventType = "OFFER_UPDATED";
        }

        // Some completion events report no delta; infer remaining qty from total.
        if ("OFFER_COMPLETED".equals(eventType) && deltaQty == 0) {
            int prevFilled = forcedCompletionFromEmpty ? 0 : (prev != null ? prev.filledQty : 0);
            int remaining = 0;
            if (eventSnapshot.totalQty > 0) {
                remaining = Math.max(0, eventSnapshot.totalQty - prevFilled);
            }
            if (remaining == 0 && eventSnapshot.filledQty > 0) {
                remaining = eventSnapshot.filledQty;
            }
            if (remaining > 0) {
                deltaQty = remaining;
                if (deltaGp == 0) {
                    long total = eventSnapshot.spentGp > 0 ? eventSnapshot.spentGp
                        : (long) eventSnapshot.price * (long) deltaQty;
                    if (!eventSnapshot.isBuy) {
                        long tax = total / 50; // 2% GE tax fallback
                        deltaGp = Math.max(0L, total - tax);
                    } else {
                        deltaGp = Math.max(0L, total);
                    }
                }
            }
        }

        boolean baselineDelta = prevIsBaseline
            && (deltaQty > 0 || deltaGp > 0)
            && (config.sessionToken() == null || config.sessionToken().isEmpty());
        boolean baselineSynthetic = baselineDelta && !usedBaseline;
        long baselineTimestamp = baselineDelta ? resolveBaselineTradeTimestamp(stamp) : 0L;
        if (baselineDelta && baselineTimestamp <= 0L) {
            if (isWithinLoginGrace()) {
                baselineTimestamp = System.currentTimeMillis();
                baselineSynthetic = true;
            } else if ("OFFER_COMPLETED".equals(eventType)) {
                if (lastLoginMs > 0) {
                    baselineTimestamp = Math.max(1L, lastLoginMs - 1L);
                    baselineSynthetic = true;
                }
            } else if (localTradesLoadedThisLogin) {
                return;
            } else if (lastLoginMs > 0) {
                baselineTimestamp = Math.max(1L, lastLoginMs - 1L);
                baselineSynthetic = true;
            }
        }
        if (baselineDelta && localTradesLoadedThisLogin && next.isBuy && !baselineSynthetic) {
            long accountKey = resolveAccountHash();
            if (accountKey > 0 && hasRecentLocalBuy(accountKey, next.itemId, System.currentTimeMillis())) {
                baselineSynthetic = true;
            }
        }
        if (!baselineSynthetic && baselineDelta && stamp != null && lastLoginMs > 0 && stamp.firstSeenMs > 0
            && stamp.firstSeenMs < lastLoginMs && isWithinLoginGrace()) {
            baselineSynthetic = true;
        }

        GeEvent geEvent = GeEvent.createBase(eventSnapshot, prev, eventType);
        geEvent.world = client.getWorld();

        geEvent.delta_qty = deltaQty;
        geEvent.delta_gp = deltaGp;
        if (baselineTimestamp > 0L) {
            geEvent.ts_client_ms = baselineTimestamp;
        }

        eventQueue.offer(geEvent);
        recordLocalTradeDelta(geEvent, baselineSynthetic);

        if (geEvent.delta_qty > 0 || "OFFER_COMPLETED".equals(eventType)) {
            scheduleRefreshSoon();
        }
    }

    @Subscribe
    public void onPostClientTick(PostClientTick event) {
        updateChatboxSuggestions();
        boolean visible = isPanelVisible();
        if (visible && !panelVisible) {
            panelVisible = true;
            triggerPanelRefresh();
            triggerStatsRefresh();
        } else if (!visible && panelVisible) {
            panelVisible = false;
        }
        // local profile loads are handled on login/selection
    }

    @Subscribe
    public void onScriptPostFired(ScriptPostFired event) {
        int scriptId = event.getScriptId();
        if (scriptId == ScriptID.CHAT_TEXT_INPUT_REBUILD ||
            scriptId == ScriptID.CHAT_PROMPT_INIT ||
            scriptId == ScriptID.MESSAGE_LAYER_OPEN) {
            suggestionDirty = true;
        }
    }

    private String determineEventType(OfferSnapshot prev, OfferSnapshot next) {
        if (prev == null) {
            if (next.state.equals(GrandExchangeOfferState.CANCELLED_BUY.name()) ||
                next.state.equals(GrandExchangeOfferState.CANCELLED_SELL.name())) {
                return "OFFER_ABORTED";
            }
            if (next.state.equals(GrandExchangeOfferState.BOUGHT.name()) ||
                next.state.equals(GrandExchangeOfferState.SOLD.name())) {
                return "OFFER_COMPLETED";
            }
            return "OFFER_PLACED";
        }

        if (!prev.state.equals(next.state)) {
            if (next.state.equals(GrandExchangeOfferState.CANCELLED_BUY.name()) ||
                next.state.equals(GrandExchangeOfferState.CANCELLED_SELL.name())) {
                return "OFFER_ABORTED";
            }
            if (next.state.equals(GrandExchangeOfferState.BOUGHT.name()) ||
                next.state.equals(GrandExchangeOfferState.SOLD.name())) {
                return "OFFER_COMPLETED";
            }
        }

        int deltaQty = Math.max(0, next.filledQty - prev.filledQty);
        long deltaGp = Math.max(0, next.spentGp - prev.spentGp);
        if (deltaQty > 0 || deltaGp > 0) {
            return "OFFER_UPDATED";
        }

        return null;
    }

    private void updatePriceSuggestion(Widget promptWidget, Boolean isBuy) {
        if (client == null || client.getGameState() != GameState.LOGGED_IN) {
            clearPriceSuggestion();
            return;
        }
        if (promptWidget == null) {
            clearPriceSuggestion();
            return;
        }
        Widget container = getChatboxContainer();
        if (container == null || container.isHidden()) {
            clearPriceSuggestion();
            return;
        }
        if (isBuy == null) {
            clearPriceSuggestion();
            return;
        }
        if (offerPreviewItem == null || offerPreviewItemId == null ||
            offerPreviewItem.item_id != offerPreviewItemId) {
            clearPriceSuggestion();
            return;
        }

        Integer price = isBuy ? offerPreviewItem.instabuy_price : offerPreviewItem.instasell_price;
        if (price == null || price <= 0) {
            clearPriceSuggestion();
            return;
        }

        Widget suggestion = ensurePriceSuggestionWidget(container, promptWidget, isBuy);
        if (lastSuggestedPrice == null || !lastSuggestedPrice.equals(price) ||
            lastSuggestedIsBuy == null || !lastSuggestedIsBuy.equals(isBuy)) {
            String label = "Current Price:";
            String coloredLabel = ColorUtil.wrapWithColorTag(label, PRICE_LABEL_COLOR);
            suggestion.setText(coloredLabel + " " + formatPrice(price));
            lastSuggestedPrice = price;
            lastSuggestedIsBuy = isBuy;
        }
        suggestion.setHidden(false);
        suggestion.revalidate();
    }

    private void updateLimitSuggestion(Widget promptWidget, Boolean isBuy) {
        if (client == null || client.getGameState() != GameState.LOGGED_IN) {
            clearLimitSuggestion();
            return;
        }
        if (promptWidget == null) {
            clearLimitSuggestion();
            return;
        }
        Widget container = getChatboxContainer();
        if (container == null || container.isHidden()) {
            clearLimitSuggestion();
            return;
        }
        if (isBuy == null || !isBuy) {
            clearLimitSuggestion();
            return;
        }
        if (offerPreviewItem == null || offerPreviewItemId == null ||
            offerPreviewItem.item_id != offerPreviewItemId) {
            clearLimitSuggestion();
            return;
        }
        Integer remaining = offerPreviewItem.ge_limit_remaining;
        if (remaining == null || remaining <= 0) {
            remaining = computeRemainingLimitSuggestion(offerPreviewItemId);
        }
        if (remaining == null || remaining <= 0) {
            clearLimitSuggestion();
            return;
        }

        Widget suggestion = ensureLimitSuggestionWidget(container, promptWidget);
        if (lastSuggestedLimit == null || !lastSuggestedLimit.equals(remaining)) {
            String coloredLabel = ColorUtil.wrapWithColorTag("Remaining GE limit:", PRICE_LABEL_COLOR);
            suggestion.setText(coloredLabel + " " + formatPrice(remaining));
            lastSuggestedLimit = remaining;
        }
        suggestion.setHidden(false);
        suggestion.revalidate();
    }

    private Integer computeRemainingLimitSuggestion(int itemId) {
        if (itemId <= 0) {
            return null;
        }
        long accountKey = resolveLocalAccountKey();
        if (accountKey <= 0) {
            accountKey = resolveSelectedProfileKey();
        }
        if (accountKey < 0) {
            return null;
        }
        ensureProfileLoaded(accountKey);
        requestGeLimits(Collections.singleton(itemId));
        Integer geLimit = getCachedGeLimit(itemId);
        if ((geLimit == null || geLimit <= 0) && itemManager != null) {
            try {
                net.runelite.client.game.ItemStats stats = itemManager.getItemStats(itemId);
                if (stats != null && stats.getGeLimit() > 0) {
                    geLimit = stats.getGeLimit();
                }
            } catch (Exception ignored) {
            }
        }
        if (geLimit == null || geLimit <= 0) {
            return null;
        }
        Map<Integer, LocalLimitInfo> limitInfo = buildLocalLimitInfo(accountKey, System.currentTimeMillis());
        LocalLimitInfo info = limitInfo.get(itemId);
        int remaining = geLimit;
        if (info != null && info.buyQty > 0) {
            remaining = (int) Math.max(0L, geLimit - info.buyQty);
        }
        if (offerPreviewItem != null && offerPreviewItem.item_id == itemId) {
            offerPreviewItem.ge_limit_total = geLimit;
            offerPreviewItem.ge_limit_remaining = remaining;
            if (info != null && info.firstBuyTs != null) {
                long resetAt = info.firstBuyTs + (4 * 60 * 60 * 1000L);
                offerPreviewItem.ge_limit_reset_ms = Math.max(0L, resetAt - System.currentTimeMillis());
            }
        }
        return remaining;
    }

    private void updateChatboxSuggestions() {
        if (client == null || client.getGameState() != GameState.LOGGED_IN) {
            clearPriceSuggestion();
            clearLimitSuggestion();
            suggestionDirty = false;
            return;
        }
        if (!isChatboxInputVisible()) {
            clearPriceSuggestion();
            clearLimitSuggestion();
            cachedPricePromptWidget = null;
            cachedQuantityPromptWidget = null;
            suggestionDirty = false;
            return;
        }
        long now = System.currentTimeMillis();
        if (!suggestionDirty && now - lastSuggestionUpdateMs < SUGGESTION_UPDATE_INTERVAL_MS) {
            return;
        }
        lastSuggestionUpdateMs = now;
        suggestionDirty = false;
        Widget pricePrompt = getPricePromptWidget();
        Widget quantityPrompt = getQuantityPromptWidget();
        if (pricePrompt == null && quantityPrompt == null) {
            clearPriceSuggestion();
            clearLimitSuggestion();
            return;
        }
        Widget geRoot = client.getWidget(ComponentID.GRAND_EXCHANGE_WINDOW_CONTAINER);
        if (geRoot == null || geRoot.isHidden()) {
            clearPriceSuggestion();
            clearLimitSuggestion();
            return;
        }
        Boolean isBuy = null;
        if (pricePrompt != null || quantityPrompt != null) {
            isBuy = isBuyOfferSetup();
        }
        updatePriceSuggestion(pricePrompt, isBuy);
        updateLimitSuggestion(quantityPrompt, isBuy);
    }

    private Widget ensurePriceSuggestionWidget(Widget container, Widget promptWidget, boolean isBuy) {
        if (!isSuggestionWidgetValid(container)) {
            priceSuggestionWidget = findSuggestionWidget(container);
        }
        if (!isSuggestionWidgetValid(container)) {
            Widget widget = container.createChild(-1, WidgetType.TEXT);
            widget.setTextColor(0xFFFFFF);
            widget.setTextShadowed(true);
            widget.setFontId(FontID.PLAIN_12);
            widget.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
            widget.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
            widget.setOriginalX(8);
            widget.setOriginalWidth(16);
            widget.setWidthMode(WidgetSizeMode.MINUS);
            widget.setOriginalHeight(16);
            widget.setXTextAlignment(WidgetTextAlignment.LEFT);
            widget.setYTextAlignment(WidgetTextAlignment.CENTER);
            widget.setName(PRICE_SUGGESTION_WIDGET_NAME);
            widget.setAction(0, "Select");
            widget.setOnOpListener((JavaScriptCallback) ev -> applySuggestedPriceToChat());
            widget.setHasListener(true);
            widget.revalidate();
            priceSuggestionWidget = widget;
        }
        int y = computeSuggestionY(container, promptWidget);
        priceSuggestionWidget.setOriginalY(y);
        priceSuggestionWidget.setName(PRICE_SUGGESTION_WIDGET_NAME);
        return priceSuggestionWidget;
    }

    private Widget ensureLimitSuggestionWidget(Widget container, Widget promptWidget) {
        if (!isLimitWidgetValid(container)) {
            limitSuggestionWidget = findLimitSuggestionWidget(container);
        }
        if (!isLimitWidgetValid(container)) {
            Widget widget = container.createChild(-1, WidgetType.TEXT);
            widget.setTextColor(0xFFFFFF);
            widget.setTextShadowed(true);
            widget.setFontId(FontID.PLAIN_12);
            widget.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
            widget.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
            widget.setOriginalX(8);
            widget.setOriginalWidth(16);
            widget.setWidthMode(WidgetSizeMode.MINUS);
            widget.setOriginalHeight(16);
            widget.setXTextAlignment(WidgetTextAlignment.LEFT);
            widget.setYTextAlignment(WidgetTextAlignment.CENTER);
            widget.setName(LIMIT_SUGGESTION_WIDGET_NAME);
            widget.setAction(0, "Select");
            widget.setOnOpListener((JavaScriptCallback) ev -> applySuggestedLimitToChat());
            widget.setHasListener(true);
            widget.revalidate();
            limitSuggestionWidget = widget;
        }
        int y = computeSuggestionY(container, promptWidget);
        limitSuggestionWidget.setOriginalY(y);
        limitSuggestionWidget.setName(LIMIT_SUGGESTION_WIDGET_NAME);
        return limitSuggestionWidget;
    }

    private void clearPriceSuggestion() {
        if (priceSuggestionWidget != null) {
            priceSuggestionWidget.setHidden(true);
            priceSuggestionWidget.revalidate();
            if (!isSuggestionWidgetValid(getChatboxContainer())) {
                priceSuggestionWidget = null;
            }
        }
        lastSuggestedPrice = null;
        lastSuggestedIsBuy = null;
    }

    private void clearLimitSuggestion() {
        if (limitSuggestionWidget != null) {
            limitSuggestionWidget.setHidden(true);
            limitSuggestionWidget.revalidate();
            if (!isLimitWidgetValid(getChatboxContainer())) {
                limitSuggestionWidget = null;
            }
        }
        lastSuggestedLimit = null;
    }

    private Widget getPricePromptWidget() {
        if (isPromptWidgetValid(cachedPricePromptWidget, true)) {
            return cachedPricePromptWidget;
        }
        Widget fullInput = client.getWidget(ComponentID.CHATBOX_FULL_INPUT);
        if (isPricePromptWidget(fullInput)) {
            cachedPricePromptWidget = fullInput;
            return fullInput;
        }
        Widget title = client.getWidget(ComponentID.CHATBOX_TITLE);
        if (isPricePromptWidget(title)) {
            cachedPricePromptWidget = title;
            return title;
        }
        Widget firstLine = client.getWidget(ComponentID.CHATBOX_FIRST_MESSAGE);
        if (isPricePromptWidget(firstLine)) {
            cachedPricePromptWidget = firstLine;
            return firstLine;
        }
        Widget messageLines = client.getWidget(ComponentID.CHATBOX_MESSAGE_LINES);
        Widget found = findPricePromptWidget(messageLines);
        if (found != null) {
            cachedPricePromptWidget = found;
            return found;
        }
        Widget container = client.getWidget(ComponentID.CHATBOX_CONTAINER);
        found = findPricePromptWidget(container);
        cachedPricePromptWidget = found;
        return found;
    }

    private Widget getQuantityPromptWidget() {
        if (isPromptWidgetValid(cachedQuantityPromptWidget, false)) {
            return cachedQuantityPromptWidget;
        }
        Widget fullInput = client.getWidget(ComponentID.CHATBOX_FULL_INPUT);
        if (isQuantityPromptWidget(fullInput)) {
            cachedQuantityPromptWidget = fullInput;
            return fullInput;
        }
        Widget title = client.getWidget(ComponentID.CHATBOX_TITLE);
        if (isQuantityPromptWidget(title)) {
            cachedQuantityPromptWidget = title;
            return title;
        }
        Widget firstLine = client.getWidget(ComponentID.CHATBOX_FIRST_MESSAGE);
        if (isQuantityPromptWidget(firstLine)) {
            cachedQuantityPromptWidget = firstLine;
            return firstLine;
        }
        Widget messageLines = client.getWidget(ComponentID.CHATBOX_MESSAGE_LINES);
        Widget found = findQuantityPromptWidget(messageLines);
        if (found != null) {
            cachedQuantityPromptWidget = found;
            return found;
        }
        Widget container = client.getWidget(ComponentID.CHATBOX_CONTAINER);
        found = findQuantityPromptWidget(container);
        cachedQuantityPromptWidget = found;
        return found;
    }

    private boolean isPromptWidgetValid(Widget widget, boolean pricePrompt) {
        if (widget == null || widget.isHidden()) {
            return false;
        }
        return pricePrompt ? isPricePromptWidget(widget) : isQuantityPromptWidget(widget);
    }

    private boolean isChatboxInputVisible() {
        if (client == null) {
            return false;
        }
        Widget fullInput = client.getWidget(ComponentID.CHATBOX_FULL_INPUT);
        if (isWidgetVisible(fullInput)) {
            return true;
        }
        Widget input = client.getWidget(ComponentID.CHATBOX_INPUT);
        if (isWidgetVisible(input)) {
            return true;
        }
        Widget title = client.getWidget(ComponentID.CHATBOX_TITLE);
        if (isWidgetVisible(title) && (isPricePromptWidget(title) || isQuantityPromptWidget(title))) {
            return true;
        }
        Widget firstLine = client.getWidget(ComponentID.CHATBOX_FIRST_MESSAGE);
        return isWidgetVisible(firstLine) && (isPricePromptWidget(firstLine) || isQuantityPromptWidget(firstLine));
    }

    private boolean isWidgetVisible(Widget widget) {
        return widget != null && !widget.isHidden();
    }

    private Widget getChatboxContainer() {
        Widget container = client.getWidget(ComponentID.CHATBOX_CONTAINER);
        if (container != null) {
            return container;
        }
        return client.getWidget(ComponentID.CHATBOX_PARENT);
    }

    private Widget findPricePromptWidget(Widget root) {
        if (root == null) {
            return null;
        }
        if (isPricePromptWidget(root)) {
            return root;
        }
        Widget match = findPricePromptInChildren(root.getChildren());
        if (match != null) {
            return match;
        }
        match = findPricePromptInChildren(root.getDynamicChildren());
        if (match != null) {
            return match;
        }
        return findPricePromptInChildren(root.getNestedChildren());
    }

    private Widget findPricePromptInChildren(Widget[] children) {
        if (children == null) {
            return null;
        }
        for (Widget child : children) {
            Widget match = findPricePromptWidget(child);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private Widget findQuantityPromptWidget(Widget root) {
        if (root == null) {
            return null;
        }
        if (isQuantityPromptWidget(root)) {
            return root;
        }
        Widget match = findQuantityPromptInChildren(root.getChildren());
        if (match != null) {
            return match;
        }
        match = findQuantityPromptInChildren(root.getDynamicChildren());
        if (match != null) {
            return match;
        }
        return findQuantityPromptInChildren(root.getNestedChildren());
    }

    private Widget findQuantityPromptInChildren(Widget[] children) {
        if (children == null) {
            return null;
        }
        for (Widget child : children) {
            Widget match = findQuantityPromptWidget(child);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private boolean isPricePromptWidget(Widget widget) {
        if (widget == null) {
            return false;
        }
        String text = normalizeOfferText(widget.getText());
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase();
        return lower.contains("set a price for each item")
            || lower.contains("enter price")
            || lower.contains("price per item");
    }

    private boolean isQuantityPromptWidget(Widget widget) {
        if (widget == null) {
            return false;
        }
        String text = normalizeOfferText(widget.getText());
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase();
        return lower.contains("how many do you wish to buy")
            || lower.contains("enter quantity")
            || lower.contains("set the quantity");
    }

    private int computeSuggestionY(Widget container, Widget anchor) {
        if (anchor == null) {
            return 32;
        }
        int anchorHeight = getWidgetHeight(anchor, 16);
        int anchorY = getRelativeCanvasY(container, anchor);
        int suggestionHeight = 16;
        int yBelow = anchorY + anchorHeight + 2;
        int yAbove = Math.max(2, anchorY - suggestionHeight - 2);
        boolean anchorIsInput = anchor.getId() == ComponentID.CHATBOX_FULL_INPUT
            || anchor.getId() == ComponentID.CHATBOX_INPUT;
        int containerHeight = getWidgetHeight(container, 0);
        int y = anchorIsInput ? yAbove : yBelow;
        if (containerHeight > 0 && y + suggestionHeight > containerHeight) {
            y = yAbove;
        }
        return Math.max(2, y);
    }

    private int getRelativeCanvasY(Widget container, Widget widget) {
        if (container != null && widget != null) {
            Point anchorLoc = widget.getCanvasLocation();
            Point containerLoc = container.getCanvasLocation();
            if (anchorLoc != null && containerLoc != null) {
                return Math.max(0, anchorLoc.getY() - containerLoc.getY());
            }
        }
        if (widget == null) {
            return 0;
        }
        int anchorY = widget.getOriginalY();
        if (anchorY <= 0) {
            anchorY = widget.getRelativeY();
        }
        return Math.max(0, anchorY);
    }

    private int getWidgetHeight(Widget widget, int fallback) {
        if (widget == null) {
            return fallback;
        }
        int height = widget.getOriginalHeight();
        if (height <= 0) {
            height = widget.getHeight();
        }
        return height > 0 ? height : fallback;
    }

    private boolean isSuggestionWidgetValid(Widget container) {
        if (priceSuggestionWidget == null || container == null) {
            return false;
        }
        if (priceSuggestionWidget.getParent() != container) {
            return false;
        }
        if (priceSuggestionWidget.getParentId() != container.getId()) {
            return false;
        }
        return isWidgetInParent(container, priceSuggestionWidget);
    }

    private boolean isLimitWidgetValid(Widget container) {
        if (limitSuggestionWidget == null || container == null) {
            return false;
        }
        if (limitSuggestionWidget.getParent() != container) {
            return false;
        }
        if (limitSuggestionWidget.getParentId() != container.getId()) {
            return false;
        }
        return isWidgetInParent(container, limitSuggestionWidget);
    }

    private Widget findSuggestionWidget(Widget container) {
        if (container == null) {
            return null;
        }
        Widget match = findSuggestionWidgetInChildren(container.getChildren());
        if (match != null) {
            return match;
        }
        match = findSuggestionWidgetInChildren(container.getDynamicChildren());
        if (match != null) {
            return match;
        }
        return findSuggestionWidgetInChildren(container.getNestedChildren());
    }

    private Widget findLimitSuggestionWidget(Widget container) {
        if (container == null) {
            return null;
        }
        Widget match = findLimitSuggestionWidgetInChildren(container.getChildren());
        if (match != null) {
            return match;
        }
        match = findLimitSuggestionWidgetInChildren(container.getDynamicChildren());
        if (match != null) {
            return match;
        }
        return findLimitSuggestionWidgetInChildren(container.getNestedChildren());
    }

    private Widget findSuggestionWidgetInChildren(Widget[] children) {
        if (children == null) {
            return null;
        }
        for (Widget child : children) {
            if (child == null) {
                continue;
            }
            if (child.getType() == WidgetType.TEXT && PRICE_SUGGESTION_WIDGET_NAME.equals(child.getName())) {
                return child;
            }
            Widget match = findSuggestionWidget(child);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private Widget findLimitSuggestionWidgetInChildren(Widget[] children) {
        if (children == null) {
            return null;
        }
        for (Widget child : children) {
            if (child == null) {
                continue;
            }
            if (child.getType() == WidgetType.TEXT && LIMIT_SUGGESTION_WIDGET_NAME.equals(child.getName())) {
                return child;
            }
            Widget match = findLimitSuggestionWidget(child);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private boolean isWidgetInParent(Widget parent, Widget widget) {
        if (parent == null || widget == null) {
            return false;
        }
        if (containsWidget(parent.getChildren(), widget)) {
            return true;
        }
        if (containsWidget(parent.getDynamicChildren(), widget)) {
            return true;
        }
        return containsWidget(parent.getNestedChildren(), widget);
    }

    private boolean containsWidget(Widget[] children, Widget target) {
        if (children == null) {
            return false;
        }
        for (Widget child : children) {
            if (child == target) {
                return true;
            }
        }
        return false;
    }

    private Boolean isBuyOfferSetup() {
        if (client == null) {
            return null;
        }
        Boolean fromSetupText = findOfferTypeFromSetupWidgets();
        if (fromSetupText != null) {
            cacheOfferTypeMapping(fromSetupText);
            return fromSetupText;
        }
        Boolean fromSelectedSlot = findOfferTypeFromSelectedSlot();
        if (fromSelectedSlot != null) {
            return fromSelectedSlot;
        }
        Boolean fromVarbit = mapNewOfferType(client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE));
        if (fromVarbit != null) {
            return fromVarbit;
        }
        return findOfferTypeFromGeRoot();
    }

    private Boolean findOfferTypeFromSetupWidgets() {
        Widget offerContainer = client.getWidget(ComponentID.GRAND_EXCHANGE_OFFER_CONTAINER);
        return findOfferTypeInWidget(offerContainer);
    }

    private Boolean findOfferTypeInWidget(Widget widget) {
        if (widget == null || widget.isHidden()) {
            return null;
        }
        String normalized = normalizeOfferText(widget.getText());
        if (normalized != null) {
            String lower = normalized.toLowerCase();
            if (lower.contains("buy offer")) {
                return true;
            }
            if (lower.contains("sell offer")) {
                return false;
            }
        }
        Boolean match = findOfferTypeInChildren(widget.getChildren());
        if (match != null) {
            return match;
        }
        match = findOfferTypeInChildren(widget.getDynamicChildren());
        if (match != null) {
            return match;
        }
        return findOfferTypeInChildren(widget.getNestedChildren());
    }

    private Boolean findOfferTypeInChildren(Widget[] children) {
        if (children == null) {
            return null;
        }
        for (Widget child : children) {
            Boolean match = findOfferTypeInWidget(child);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private Boolean findOfferTypeFromSelectedSlot() {
        GrandExchangeOffer offer = getSelectedOffer();
        if (offer == null) {
            return null;
        }
        GrandExchangeOfferState state = offer.getState();
        if (state == null) {
            return null;
        }
        if (state == GrandExchangeOfferState.BUYING
            || state == GrandExchangeOfferState.BOUGHT
            || state == GrandExchangeOfferState.CANCELLED_BUY) {
            return true;
        }
        if (state == GrandExchangeOfferState.SELLING
            || state == GrandExchangeOfferState.SOLD
            || state == GrandExchangeOfferState.CANCELLED_SELL) {
            return false;
        }
        return null;
    }

    private void cacheOfferTypeMapping(boolean isBuy) {
        int offerType = client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE);
        if (offerType <= 0) {
            return;
        }
        if (isBuy) {
            newOfferTypeBuyValue = offerType;
        } else {
            newOfferTypeSellValue = offerType;
        }
    }

    private Boolean mapNewOfferType(int offerType) {
        if (offerType <= 0) {
            return null;
        }
        if (newOfferTypeBuyValue != null && offerType == newOfferTypeBuyValue) {
            return true;
        }
        if (newOfferTypeSellValue != null && offerType == newOfferTypeSellValue) {
            return false;
        }
        if (offerType == 1) {
            return true;
        }
        if (offerType == 2) {
            return false;
        }
        return null;
    }

    private Boolean findOfferTypeFromGeRoot() {
        Widget geRoot = client.getWidget(ComponentID.GRAND_EXCHANGE_WINDOW_CONTAINER);
        if (geRoot == null || geRoot.isHidden()) {
            return null;
        }
        List<String> texts = new ArrayList<>();
        collectWidgetText(geRoot, texts);
        boolean seenBuy = false;
        boolean seenSell = false;
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            String lower = text.toLowerCase();
            if (lower.contains("buy offer")) {
                seenBuy = true;
            }
            if (lower.contains("sell offer")) {
                seenSell = true;
            }
        }
        if (seenSell && !seenBuy) {
            return false;
        }
        if (seenBuy && !seenSell) {
            return true;
        }
        return null;
    }

    private String formatPrice(int price) {
        return NumberFormat.getIntegerInstance(Locale.US).format(price);
    }

    private void applySuggestedPriceToChat() {
        if (client == null) {
            return;
        }
        Boolean isBuy = isBuyOfferSetup();
        if (isBuy == null || offerPreviewItem == null) {
            return;
        }
        Integer price = isBuy ? offerPreviewItem.instabuy_price : offerPreviewItem.instasell_price;
        if (price == null || price <= 0) {
            return;
        }
        client.setVarcStrValue(VarClientID.MESLAYERINPUT, String.valueOf(price));
        client.runScript(ScriptID.CHAT_TEXT_INPUT_REBUILD, "");
    }

    private void applySuggestedLimitToChat() {
        if (client == null) {
            return;
        }
        Boolean isBuy = isBuyOfferSetup();
        if (isBuy == null || !isBuy || offerPreviewItem == null) {
            return;
        }
        Integer remaining = offerPreviewItem.ge_limit_remaining;
        if (remaining == null || remaining <= 0) {
            return;
        }
        client.setVarcStrValue(VarClientID.MESLAYERINPUT, String.valueOf(remaining));
        client.runScript(ScriptID.CHAT_TEXT_INPUT_REBUILD, "");
    }

    private void trackOfferUpdate(int slot, OfferSnapshot prev, OfferSnapshot next) {
        if (next == null) {
            return;
        }
        if (next.state.equals(GrandExchangeOfferState.EMPTY.name()) || next.itemId <= 0) {
            OfferUpdateStamp existing = offerUpdateStamps.get(slot);
            if (shouldClearOfferStamp(prev)) {
                if (offerUpdateStamps.remove(slot) != null) {
                    persistOfferUpdateTimes();
                }
            } else if (existing != null) {
                if (existing.lastEmptyMs <= 0) {
                    existing.lastEmptyMs = System.currentTimeMillis();
                    persistOfferUpdateTimes();
                }
            }
            return;
        }
        OfferUpdateStamp existing = offerUpdateStamps.get(slot);
        if (prev == null) {
            if (existing != null) {
                if (stampMatchesSnapshot(existing, next) || shouldPreserveStamp(existing, next)
                    || shouldPreserveStampAfterLogin(existing, next)
                    || shouldPreserveStampAfterEmpty(prev, next, existing)) {
                    boolean changed = maybeUpdateStampDetails(existing, next.itemId, next.price, next.totalQty, next.isBuy,
                        next.filledQty, next.spentGp);
                    if (existing.lastUpdateMs <= 0) {
                        existing.lastUpdateMs = System.currentTimeMillis();
                        changed = true;
                    }
                    if (markCompletedIfNeeded(existing, next)) {
                        changed = true;
                    }
                    if (changed) {
                        persistOfferUpdateTimes();
                    }
                    return;
                }
            }
            OfferUpdateStamp created = OfferUpdateStamp.fromSnapshot(next, System.currentTimeMillis());
            offerUpdateStamps.put(slot, created);
            if (markCompletedIfNeeded(created, next)) {
                persistOfferUpdateTimes();
                return;
            }
            persistOfferUpdateTimes();
            return;
        }

        if (hasOfferChanged(prev, next)) {
            if (existing != null) {
                if (stampMatchesSnapshot(existing, next) || shouldPreserveStamp(existing, next)
                    || shouldPreserveStampAfterLogin(existing, next)
                    || shouldPreserveStampAfterEmpty(prev, next, existing)) {
                    boolean changed = maybeUpdateStampDetails(existing, next.itemId, next.price, next.totalQty, next.isBuy,
                        next.filledQty, next.spentGp);
                    if (shouldRefreshOfferTimestamp(prev, next)) {
                        existing.lastUpdateMs = System.currentTimeMillis();
                        changed = true;
                    }
                    if (markCompletedIfNeeded(existing, next)) {
                        changed = true;
                    }
                    if (changed) {
                        persistOfferUpdateTimes();
                    }
                    return;
                }
            }
            OfferUpdateStamp created = OfferUpdateStamp.fromSnapshot(next, System.currentTimeMillis());
            offerUpdateStamps.put(slot, created);
            if (markCompletedIfNeeded(created, next)) {
                persistOfferUpdateTimes();
                return;
            }
            persistOfferUpdateTimes();
            return;
        }

        if (existing == null) {
            OfferUpdateStamp created = OfferUpdateStamp.fromSnapshot(next, System.currentTimeMillis());
            offerUpdateStamps.put(slot, created);
            if (markCompletedIfNeeded(created, next)) {
                persistOfferUpdateTimes();
                return;
            }
            persistOfferUpdateTimes();
        }
    }

    private boolean hasOfferChanged(OfferSnapshot prev, OfferSnapshot next) {
        if (prev == null || next == null) {
            return true;
        }
        return prev.itemId != next.itemId
            || prev.price != next.price
            || prev.totalQty != next.totalQty
            || prev.filledQty != next.filledQty
            || prev.spentGp != next.spentGp
            || !prev.state.equals(next.state);
    }

    private boolean shouldRefreshOfferTimestamp(OfferSnapshot prev, OfferSnapshot next) {
        if (prev == null || next == null) {
            return false;
        }
        if (prev.itemId <= 0 || GrandExchangeOfferState.EMPTY.name().equals(prev.state)) {
            return false;
        }
        if (next.filledQty > prev.filledQty) {
            return true;
        }
        if (next.spentGp > prev.spentGp) {
            return true;
        }
        if (!prev.state.equals(next.state)) {
            return next.state.equals(GrandExchangeOfferState.BOUGHT.name())
                || next.state.equals(GrandExchangeOfferState.SOLD.name());
        }
        return false;
    }

    private boolean markCompletedIfNeeded(OfferUpdateStamp stamp, OfferSnapshot snapshot) {
        if (stamp == null || snapshot == null) {
            return false;
        }
        if (!isOfferComplete(snapshot)) {
            return false;
        }
        if (stamp.completedMs > 0) {
            return false;
        }
        stamp.completedMs = System.currentTimeMillis();
        if (stamp.firstSeenMs <= 0) {
            stamp.firstSeenMs = stamp.lastUpdateMs > 0 ? stamp.lastUpdateMs : stamp.completedMs;
        }
        return true;
    }

    private boolean markCompletedIfNeeded(OfferUpdateStamp stamp, GrandExchangeOffer offer) {
        if (stamp == null || offer == null) {
            return false;
        }
        if (!isOfferComplete(offer)) {
            return false;
        }
        if (stamp.completedMs > 0) {
            return false;
        }
        stamp.completedMs = System.currentTimeMillis();
        if (stamp.firstSeenMs <= 0) {
            stamp.firstSeenMs = stamp.lastUpdateMs > 0 ? stamp.lastUpdateMs : stamp.completedMs;
        }
        return true;
    }

    private boolean isOfferComplete(OfferSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        return GrandExchangeOfferState.BOUGHT.name().equals(snapshot.state)
            || GrandExchangeOfferState.SOLD.name().equals(snapshot.state);
    }

    private boolean isOfferComplete(GrandExchangeOffer offer) {
        if (offer == null) {
            return false;
        }
        GrandExchangeOfferState state = offer.getState();
        return state == GrandExchangeOfferState.BOUGHT || state == GrandExchangeOfferState.SOLD;
    }

    private long computeCompletedDisplayTimestamp(OfferUpdateStamp stamp) {
        if (stamp == null) {
            return -1;
        }
        long now = System.currentTimeMillis();
        long completionMs = stamp.completedMs > 0 ? stamp.completedMs
            : (stamp.lastUpdateMs > 0 ? stamp.lastUpdateMs : now);
        long startMs = stamp.firstSeenMs > 0 ? stamp.firstSeenMs : completionMs;
        long duration = Math.max(0L, completionMs - startMs);
        return now - duration;
    }

    private boolean shouldPreserveStamp(OfferUpdateStamp stamp, OfferSnapshot snapshot) {
        if (stamp == null || snapshot == null) {
            return false;
        }
        if (stamp.itemId != snapshot.itemId || stamp.isBuy != snapshot.isBuy) {
            return false;
        }
        if (!stampHasProgress(stamp)) {
            return false;
        }
        boolean snapshotHasProgress = snapshot.filledQty > 0 || snapshot.spentGp > 0;
        if (!snapshotHasProgress) {
            return true;
        }
        if (snapshot.filledQty > 0 && stamp.filledQty > 0 && snapshot.filledQty < stamp.filledQty) {
            return true;
        }
        return snapshot.spentGp > 0 && stamp.spentGp > 0 && snapshot.spentGp < stamp.spentGp;
    }

    private boolean shouldPreserveStamp(OfferUpdateStamp stamp, GrandExchangeOffer offer, boolean isBuy) {
        if (stamp == null || offer == null) {
            return false;
        }
        if (stamp.itemId != offer.getItemId() || stamp.isBuy != isBuy) {
            return false;
        }
        if (!stampHasProgress(stamp)) {
            return false;
        }
        int filledQty = offer.getQuantitySold();
        long spentGp = offer.getSpent();
        boolean offerHasProgress = filledQty > 0 || spentGp > 0;
        if (!offerHasProgress) {
            return true;
        }
        if (filledQty > 0 && stamp.filledQty > 0 && filledQty < stamp.filledQty) {
            return true;
        }
        return spentGp > 0 && stamp.spentGp > 0 && spentGp < stamp.spentGp;
    }

    private boolean shouldPreserveStampAfterLogin(OfferUpdateStamp stamp, OfferSnapshot snapshot) {
        if (!isWithinLoginGrace()) {
            return false;
        }
        if (stamp == null || snapshot == null) {
            return false;
        }
        if (stamp.itemId != snapshot.itemId) {
            return false;
        }
        if (!stampHasProgress(stamp)) {
            return false;
        }
        boolean snapshotHasProgress = snapshot.filledQty > 0 || snapshot.spentGp > 0;
        if (!snapshotHasProgress) {
            return true;
        }
        if (snapshot.filledQty > 0 && stamp.filledQty > 0 && snapshot.filledQty < stamp.filledQty) {
            return true;
        }
        return snapshot.spentGp > 0 && stamp.spentGp > 0 && snapshot.spentGp < stamp.spentGp;
    }

    private boolean shouldPreserveStampAfterLogin(OfferUpdateStamp stamp, GrandExchangeOffer offer) {
        if (!isWithinLoginGrace()) {
            return false;
        }
        if (stamp == null || offer == null) {
            return false;
        }
        if (stamp.itemId != offer.getItemId()) {
            return false;
        }
        if (!stampHasProgress(stamp)) {
            return false;
        }
        int filledQty = offer.getQuantitySold();
        long spentGp = offer.getSpent();
        boolean offerHasProgress = filledQty > 0 || spentGp > 0;
        if (!offerHasProgress) {
            return true;
        }
        if (filledQty > 0 && stamp.filledQty > 0 && filledQty < stamp.filledQty) {
            return true;
        }
        return spentGp > 0 && stamp.spentGp > 0 && spentGp < stamp.spentGp;
    }

    private boolean shouldPreserveStampAfterEmpty(OfferSnapshot prev, OfferSnapshot next, OfferUpdateStamp stamp) {
        if (prev == null || next == null || stamp == null) {
            return false;
        }
        boolean prevWasEmpty = GrandExchangeOfferState.EMPTY.name().equals(prev.state) || prev.itemId <= 0;
        if (!prevWasEmpty) {
            return false;
        }
        if (next.itemId <= 0 || stamp.itemId != next.itemId) {
            return false;
        }
        if (stamp.isBuy != next.isBuy) {
            return false;
        }
        return true;
    }

    private boolean shouldPreserveStampAfterEmpty(GrandExchangeOffer offer, OfferUpdateStamp stamp, boolean isBuy) {
        if (offer == null || stamp == null) {
            return false;
        }
        if (offer.getItemId() <= 0 || stamp.itemId != offer.getItemId()) {
            return false;
        }
        return stamp.isBuy == isBuy;
    }

    private boolean stampMatchesSnapshot(OfferUpdateStamp stamp, OfferSnapshot snapshot) {
        if (stamp == null || snapshot == null) {
            return false;
        }
        return stampMatches(stamp, snapshot.itemId, snapshot.price, snapshot.totalQty, snapshot.isBuy,
            snapshot.filledQty, snapshot.spentGp);
    }

    private boolean stampMatchesOffer(OfferUpdateStamp stamp, GrandExchangeOffer offer) {
        if (stamp == null || offer == null) {
            return false;
        }
        return stampMatches(stamp, offer.getItemId(), offer.getPrice(), offer.getTotalQuantity(), isBuyOffer(offer),
            offer.getQuantitySold(), offer.getSpent());
    }

    private boolean stampMatches(OfferUpdateStamp stamp, int itemId, int price, int totalQty, boolean isBuy,
        int filledQty, long spentGp) {
        if (stamp == null) {
            return false;
        }
        if (stamp.itemId != itemId || stamp.isBuy != isBuy) {
            return false;
        }
        if (price > 0 && stamp.price > 0 && stamp.price != price) {
            return false;
        }
        boolean progressCompatible = progressMatches(stamp, filledQty, spentGp);
        if (totalQty > 0 && stamp.totalQty > 0 && stamp.totalQty != totalQty) {
            if (filledQty <= 0 && stamp.filledQty <= 0) {
                return false;
            }
            if (!progressCompatible) {
                return false;
            }
        }
        if (!progressCompatible) {
            return false;
        }
        return true;
    }

    private boolean maybeUpdateStampDetails(OfferUpdateStamp stamp, int itemId, int price, int totalQty, boolean isBuy,
        int filledQty, long spentGp) {
        if (stamp == null) {
            return false;
        }
        boolean changed = false;
        if (stamp.itemId != itemId) {
            stamp.itemId = itemId;
            changed = true;
        }
        if (price > 0 && stamp.price != price) {
            stamp.price = price;
            changed = true;
        }
        if (totalQty > 0 && stamp.totalQty != totalQty) {
            stamp.totalQty = totalQty;
            changed = true;
        }
        if (stamp.isBuy != isBuy) {
            stamp.isBuy = isBuy;
            changed = true;
        }
        if (filledQty > stamp.filledQty) {
            stamp.filledQty = filledQty;
            changed = true;
        }
        if (spentGp > stamp.spentGp) {
            stamp.spentGp = spentGp;
            changed = true;
        }
        if (stamp.lastEmptyMs != 0) {
            stamp.lastEmptyMs = 0;
            changed = true;
        }
        if (stamp.firstSeenMs <= 0) {
            stamp.firstSeenMs = stamp.lastUpdateMs > 0 ? stamp.lastUpdateMs : System.currentTimeMillis();
            changed = true;
        }
        return changed;
    }

    private boolean progressMatches(OfferUpdateStamp stamp, int filledQty, long spentGp) {
        if (stamp == null) {
            return false;
        }
        boolean stampHasProgress = stamp.filledQty > 0 || stamp.spentGp > 0;
        if (filledQty > 0 && stamp.filledQty > 0 && filledQty < stamp.filledQty) {
            return stampHasProgress || isWithinLoginGrace();
        }
        if (spentGp > 0 && stamp.spentGp > 0 && spentGp < stamp.spentGp) {
            return stampHasProgress || isWithinLoginGrace();
        }
        return true;
    }

    private boolean isWithinLoginGrace() {
        if (lastLoginMs <= 0) {
            return false;
        }
        return System.currentTimeMillis() - lastLoginMs <= LOGIN_GRACE_MS;
    }

    private long resolveBaselineTradeTimestamp(OfferUpdateStamp stamp) {
        long stampMs = 0L;
        if (stamp != null) {
            stampMs = minPositive(stamp.firstSeenMs, stamp.lastUpdateMs, stamp.completedMs);
        }
        if (lastLoginMs > 0 && stampMs > 0 && stampMs < lastLoginMs) {
            return stampMs;
        }
        return 0L;
    }

    private long minPositive(long a, long b, long c) {
        long min = 0L;
        if (a > 0) {
            min = a;
        }
        if (b > 0 && (min == 0L || b < min)) {
            min = b;
        }
        if (c > 0 && (min == 0L || c < min)) {
            min = c;
        }
        return min;
    }

    private boolean stampHasProgress(OfferUpdateStamp stamp) {
        return stamp != null && (stamp.filledQty > 0 || stamp.spentGp > 0);
    }

    private boolean shouldClearOfferStamp(OfferSnapshot prev) {
        if (prev == null) {
            return false;
        }
        if (prev.itemId <= 0) {
            return false;
        }
        if (GrandExchangeOfferState.CANCELLED_BUY.name().equals(prev.state) ||
            GrandExchangeOfferState.CANCELLED_SELL.name().equals(prev.state) ||
            GrandExchangeOfferState.BOUGHT.name().equals(prev.state) ||
            GrandExchangeOfferState.SOLD.name().equals(prev.state)) {
            return true;
        }
        return false;
    }

    private boolean isBuyOffer(GrandExchangeOffer offer) {
        if (offer == null) {
            return false;
        }
        GrandExchangeOfferState state = offer.getState();
        return state == GrandExchangeOfferState.BUYING
            || state == GrandExchangeOfferState.BOUGHT
            || state == GrandExchangeOfferState.CANCELLED_BUY;
    }

    long getOfferLastUpdateMs(int slot, GrandExchangeOffer offer) {
        if (offer == null) {
            return -1;
        }
        if (offer.getState() == GrandExchangeOfferState.EMPTY || offer.getItemId() <= 0) {
            OfferUpdateStamp existing = offerUpdateStamps.get(slot);
            if (existing != null && existing.lastEmptyMs <= 0) {
                existing.lastEmptyMs = System.currentTimeMillis();
                persistOfferUpdateTimes();
            }
            return -1;
        }
        OfferUpdateStamp existing = offerUpdateStamps.get(slot);
        boolean isBuy = isBuyOffer(offer);
        if (existing != null) {
            if (stampMatchesOffer(existing, offer) || shouldPreserveStamp(existing, offer, isBuy)
                || shouldPreserveStampAfterLogin(existing, offer)
                || shouldPreserveStampAfterEmpty(offer, existing, isBuy)) {
                boolean changed = maybeUpdateStampDetails(existing, offer.getItemId(), offer.getPrice(),
                    offer.getTotalQuantity(), isBuy, offer.getQuantitySold(), offer.getSpent());
                if (existing.lastUpdateMs <= 0) {
                    existing.lastUpdateMs = System.currentTimeMillis();
                    changed = true;
                }
                if (markCompletedIfNeeded(existing, offer)) {
                    changed = true;
                }
                if (changed) {
                    persistOfferUpdateTimes();
                }
                if (isOfferComplete(offer)) {
                    return computeCompletedDisplayTimestamp(existing);
                }
                return existing.lastUpdateMs;
            }
        }

        OfferUpdateStamp stamp = OfferUpdateStamp.fromOffer(offer, System.currentTimeMillis(), isBuy);
        offerUpdateStamps.put(slot, stamp);
        markCompletedIfNeeded(stamp, offer);
        persistOfferUpdateTimes();
        if (isOfferComplete(offer)) {
            return computeCompletedDisplayTimestamp(stamp);
        }
        return stamp.lastUpdateMs;
    }

    private void ensureDeviceId() {
        String deviceId = config.deviceId();
        if (deviceId == null || deviceId.trim().isEmpty()) {
            deviceId = UUID.randomUUID().toString();
            configManager.setConfiguration("fliphub_dev", "deviceId", deviceId);
        }
    }

    private void loadBookmarks() {
        bookmarkedItems.clear();
        String raw = config.bookmarks();
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }
        String[] parts = raw.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                int itemId = Integer.parseInt(trimmed);
                if (itemId > 0) {
                    bookmarkedItems.add(itemId);
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void loadHiddenItems() {
        hiddenItems.clear();
        String raw = config.hiddenItems();
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }
        String[] parts = raw.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                int itemId = Integer.parseInt(trimmed);
                if (itemId > 0) {
                    hiddenItems.add(itemId);
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void loadOfferUpdateTimes() {
        offerUpdateStamps.clear();
        if (config == null || gson == null) {
            return;
        }
        String raw = config.geOfferUpdateTimes();
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }
        try {
            Type type = new TypeToken<Map<String, OfferUpdateStamp>>() {}.getType();
            Map<String, OfferUpdateStamp> parsed = gson.fromJson(raw, type);
            if (parsed == null) {
                return;
            }
            for (Map.Entry<String, OfferUpdateStamp> entry : parsed.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                try {
                    int slot = Integer.parseInt(entry.getKey());
                    if (slot >= 0 && slot <= 7) {
                        offerUpdateStamps.put(slot, entry.getValue());
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void persistOfferUpdateTimes() {
        if (configManager == null || gson == null) {
            return;
        }
        Map<String, OfferUpdateStamp> output = new TreeMap<>();
        for (Map.Entry<Integer, OfferUpdateStamp> entry : offerUpdateStamps.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            output.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        String json = gson.toJson(output);
        configManager.setConfiguration("fliphub_dev", "geOfferUpdateTimes", json);
    }

    private void persistBookmarks() {
        String value = bookmarkedItems.stream()
            .sorted()
            .map(String::valueOf)
            .collect(Collectors.joining(","));
        configManager.setConfiguration("fliphub_dev", "bookmarks", value);
        if (panel != null) {
            panel.refreshBookmarks();
        }
    }

    private void persistHiddenItems() {
        String value = hiddenItems.stream()
            .sorted()
            .map(String::valueOf)
            .collect(Collectors.joining(","));
        configManager.setConfiguration("fliphub_dev", "hiddenItems", value);
        if (panel != null) {
            panel.refreshBookmarks();
        }
    }

    private String getLinkInput() {
        String licenseKey = config.licenseKey();
        if (licenseKey != null && !licenseKey.trim().isEmpty()) {
            return licenseKey;
        }
        return config.linkCode();
    }

    private void attemptLink(String licenseKey) {
        if (!isClientLoggedIn()) {
            if (panel != null) {
                updateProfileHeader();
            }
            return;
        }
        Runnable task = () -> {
            try {
                String deviceId = config.deviceId();
                ApiClient.LinkResponse response = apiClient.linkDevice(
                    licenseKey,
                    deviceId,
                    System.getProperty("user.name"),
                    "1.0.0"
                );

                if (response.session_token != null && response.signing_secret != null) {
                    configManager.setConfiguration("fliphub_dev", "sessionToken", response.session_token);
                    configManager.setConfiguration("fliphub_dev", "signingSecret", response.signing_secret);
                    configManager.setConfiguration("fliphub_dev", "licenseKey", "");
                    configManager.setConfiguration("fliphub_dev", "linkCode", "");
                    refreshPanelData();
                    if (panel != null) {
                        updateProfileHeader();
                    }
                } else if (panel != null) {
                    updateProfileHeader();
                }
            } catch (Exception ex) {
                if (isTimeoutException(ex)) {
                    log.debug("FlipHub link timed out");
                    if (panel != null) {
                        updateProfileHeader();
                    }
                    scheduleLinkRetry(licenseKey);
                    return;
                }
                if (panel != null) {
                    updateProfileHeader();
                }
                log.warn("FlipHub link failed", ex);
            }
        };

        if (scheduler != null) {
            scheduler.execute(task);
        } else {
            Executors.newSingleThreadExecutor().execute(task);
        }
    }

    private void scheduleLinkRetry(String licenseKey) {
        if (scheduler == null || licenseKey == null || licenseKey.trim().isEmpty()) {
            return;
        }
        scheduler.schedule(() -> attemptLink(licenseKey.trim()), 5, TimeUnit.SECONDS);
    }

    private void flushEvents() {
        if (!isClientLoggedIn()) {
            return;
        }
        String sessionToken = config.sessionToken();
        String signingSecret = config.signingSecret();
        if (sessionToken == null || sessionToken.isEmpty() ||
            signingSecret == null || signingSecret.isEmpty()) {
                if (panel != null) {
                    updateProfileHeader();
                }
                return;
            }

        List<GeEvent> batch = new ArrayList<>(MAX_BATCH_SIZE);
        while (batch.size() < MAX_BATCH_SIZE) {
            GeEvent event = eventQueue.poll();
            if (event == null) {
                break;
            }
            batch.add(event);
        }

        if (batch.isEmpty()) {
            return;
        }

        try {
            int status = apiClient.sendEvents(sessionToken, signingSecret, batch);
            if (status == 401) {
                boolean refreshed = attemptRefresh(sessionToken);
                requeue(batch);
                if (!refreshed) {
                    clearSession();
                }
                if (!refreshed && panel != null && isPanelVisible()) {
                    updateProfileHeader();
                }
            } else if (status >= 500 || status == 429) {
                requeue(batch);
            } else if (status >= 400) {
                // Drop bad batches to avoid infinite retry loops
            } else if (panel != null) {
                updateProfileHeader();
            }
        } catch (Exception ex) {
            requeue(batch);
        }
    }

    private void pollOfferSetupItem() {
        if (clientThread == null) {
            return;
        }
        clientThread.invokeLater(this::updateOfferPreviewItem);
    }

    private void updateOfferPreviewItem() {
        Widget geRoot = getVisibleGeRoot();
        boolean geOpen = geRoot != null;
        boolean offerStatusOpen = geOpen && isOfferStatusOpen(geRoot);
        int newOfferType = geOpen ? client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE) : 0;
        boolean setupMode = newOfferType > 0;
        int selectedSlot = geOpen ? client.getVarbitValue(VarbitID.GE_SELECTEDSLOT) : -1;
        if (geOpen && !setupMode && !offerStatusOpen && selectedSlot <= 0) {
            clearOfferPreview();
            return;
        }

        boolean handled = setupMode ? updateOfferSetupItem() : (offerStatusOpen && updateOfferStatusItem());
        if (!handled) {
            handled = updateOfferItemFromVarp(geRoot);
        }
        if (!handled) {
            handled = updateOfferItemFromSelectedSlot(geRoot);
        }
        if (!handled && !setupMode) {
            handled = updateOfferItemFromText(geRoot);
        }
        if (!handled) {
            clearOfferPreview();
        }
    }

    private boolean updateOfferSetupItem() {
        Widget offerContainer = client.getWidget(ComponentID.GRAND_EXCHANGE_OFFER_CONTAINER);
        boolean offerVisible = offerContainer != null && !offerContainer.isHidden();
        if (!offerVisible) {
            return false;
        }

        String normalized = normalizeOfferText(offerContainer != null ? offerContainer.getText() : null);
        if (normalized != null) {
            String lower = normalized.toLowerCase();
            if (containsAny(lower, OFFER_SETUP_BLOCKERS)) {
                clearOfferPreview();
                return true;
            }
        }

        int itemId = -1;
        if (offerContainer != null && !offerContainer.isHidden()) {
            itemId = findFirstItemId(offerContainer);
        }
        if (itemId <= 0) {
            clearOfferPreview();
            return true;
        }
        return setOfferPreviewItem(itemId, null);
    }

    private boolean updateOfferStatusItem() {
        Widget geRoot = getVisibleGeRoot();
        if (geRoot == null) {
            return false;
        }
        int itemId = findFirstItemId(geRoot);
        if (itemId <= 0) {
            return false;
        }
        return setOfferPreviewItem(itemId, null);
    }

    private boolean isOfferStatusOpen(Widget geRoot) {
        return widgetTreeContainsAnyText(geRoot, OFFER_STATUS_MARKERS);
    }

    boolean isOfferStatusOpen() {
        Widget geRoot = getVisibleGeRoot();
        if (geRoot == null) {
            return false;
        }
        return isOfferStatusOpen(geRoot);
    }

    private boolean updateOfferItemFromVarp(Widget geRoot) {
        if (geRoot == null) {
            return false;
        }
        int itemId = client.getVarpValue(VarPlayer.CURRENT_GE_ITEM);
        if (itemId <= 0) {
            return false;
        }
        return setOfferPreviewItem(itemId, null);
    }

    private boolean updateOfferItemFromSelectedSlot(Widget geRoot) {
        if (geRoot == null) {
            return false;
        }
        GrandExchangeOffer offer = getSelectedOffer();
        if (offer == null) {
            return false;
        }

        int itemId = offer.getItemId();
        if (itemId <= 0) {
            return false;
        }
        return setOfferPreviewItem(itemId, null);
    }

    private boolean updateOfferItemFromText(Widget geRoot) {
        if (geRoot == null) {
            return false;
        }
        String candidate = findItemNameCandidate(geRoot);
        if (candidate == null) {
            return false;
        }
        int itemId = resolveItemIdFromName(candidate);
        if (itemId <= 0) {
            return false;
        }
        return setOfferPreviewItem(itemId, candidate);
    }

    private boolean setOfferPreviewItem(int itemId, String name) {
        if (itemId <= 0) {
            return false;
        }
        if (offerPreviewItemId != null && offerPreviewItemId == itemId && offerPreviewItem != null) {
            return true;
        }
        offerPreviewItemId = itemId;
        offerPreviewName = name;
        fetchOfferPreview(itemId);
        return true;
    }

    private int findItemIdFromOfferText(Widget offerText) {
        if (offerText == null || itemManager == null) {
            return -1;
        }
        String raw = normalizeOfferText(offerText.getText());
        if (raw == null || raw.trim().isEmpty()) {
            return -1;
        }
        String[] lines = raw.split("\\n");
        String candidate = null;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String lower = trimmed.toLowerCase();
            if (startsWithAny(lower, OFFER_TEXT_SKIP_PREFIX) || containsAny(lower, OFFER_SETUP_BLOCKERS)) {
                continue;
            }
            candidate = trimmed;
            break;
        }
        if (candidate == null) {
            return -1;
        }
        if (offerPreviewName != null && offerPreviewName.equalsIgnoreCase(candidate)) {
            return -1;
        }
        offerPreviewName = candidate;
        try {
            for (ItemPrice price : itemManager.search(candidate)) {
                if (price.getName() != null && price.getName().equalsIgnoreCase(candidate)) {
                    return price.getId();
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private int findFirstItemId(Widget widget) {
        if (widget == null) {
            return -1;
        }
        int itemId = widget.getItemId();
        if (itemId > 0) {
            return itemId;
        }
        int found = findFirstItemId(widget.getChildren());
        if (found > 0) {
            return found;
        }
        found = findFirstItemId(widget.getDynamicChildren());
        if (found > 0) {
            return found;
        }
        return findFirstItemId(widget.getNestedChildren());
    }

    private int findFirstItemId(Widget[] children) {
        if (children == null) {
            return -1;
        }
        for (Widget child : children) {
            int found = findFirstItemId(child);
            if (found > 0) {
                return found;
            }
        }
        return -1;
    }

    private String findItemNameCandidate(Widget root) {
        List<String> texts = new ArrayList<>();
        collectWidgetText(root, texts);
        for (String text : texts) {
            String[] lines = text.split("\\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (!isItemNameCandidate(trimmed)) {
                    continue;
                }
                if (resolveItemIdFromName(trimmed) > 0) {
                    return trimmed;
                }
            }
        }
        return null;
    }

    private void collectWidgetText(Widget widget, List<String> out) {
        if (widget == null || out == null) {
            return;
        }
        String text = normalizeOfferText(widget.getText());
        if (text != null && !text.trim().isEmpty()) {
            out.add(text);
        }
        collectWidgetText(widget.getChildren(), out);
        collectWidgetText(widget.getDynamicChildren(), out);
        collectWidgetText(widget.getNestedChildren(), out);
    }

    private void collectWidgetText(Widget[] children, List<String> out) {
        if (children == null) {
            return;
        }
        for (Widget child : children) {
            collectWidgetText(child, out);
        }
    }

    private boolean isItemNameCandidate(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.length() < 3 || trimmed.length() > 60) {
            return false;
        }
        String lower = trimmed.toLowerCase();
        if (containsAny(lower, ITEM_NAME_EXCLUDES)) {
            return false;
        }
        boolean hasLetter = false;
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isLetter(trimmed.charAt(i))) {
                hasLetter = true;
                break;
            }
        }
        return hasLetter;
    }

    private int resolveItemIdFromName(String name) {
        if (name == null || itemManager == null) {
            return -1;
        }
        String key = name.trim().toLowerCase();
        if (key.isEmpty()) {
            return -1;
        }
        Integer cached = itemNameLookupCache.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            for (ItemPrice price : itemManager.search(name)) {
                if (price.getName() != null && price.getName().equalsIgnoreCase(name)) {
                    itemNameLookupCache.put(key, price.getId());
                    return price.getId();
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private Widget getVisibleGeRoot() {
        if (client == null) {
            return null;
        }
        Widget geRoot = client.getWidget(ComponentID.GRAND_EXCHANGE_WINDOW_CONTAINER);
        if (geRoot == null || geRoot.isHidden()) {
            return null;
        }
        return geRoot;
    }

    private boolean containsAny(String lower, String[] needles) {
        if (lower == null || needles == null) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isEmpty() && lower.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean startsWithAny(String lower, String[] prefixes) {
        if (lower == null || prefixes == null) {
            return false;
        }
        for (String prefix : prefixes) {
            if (prefix != null && !prefix.isEmpty() && lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private GrandExchangeOffer getSelectedOffer() {
        int rawSlot = client.getVarbitValue(VarbitID.GE_SELECTEDSLOT);
        if (rawSlot <= 0) {
            return null;
        }
        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        if (offers == null || offers.length == 0) {
            return null;
        }
        int slot = rawSlot;
        if (slot >= 1 && slot <= offers.length) {
            slot -= 1;
        }
        if (slot < 0 || slot >= offers.length) {
            return null;
        }
        return offers[slot];
    }

    private boolean widgetTreeContainsAnyText(Widget widget, String[] needlesLower) {
        if (widget == null || needlesLower == null || needlesLower.length == 0) {
            return false;
        }
        String text = normalizeOfferText(widget.getText());
        if (text != null) {
            String lower = text.toLowerCase();
            for (String needle : needlesLower) {
                if (needle != null && !needle.isEmpty() && lower.contains(needle)) {
                    return true;
                }
            }
        }
        if (widgetTreeContainsAnyText(widget.getChildren(), needlesLower)) {
            return true;
        }
        if (widgetTreeContainsAnyText(widget.getDynamicChildren(), needlesLower)) {
            return true;
        }
        return widgetTreeContainsAnyText(widget.getNestedChildren(), needlesLower);
    }

    private boolean widgetTreeContainsAnyText(Widget[] children, String[] needlesLower) {
        if (children == null) {
            return false;
        }
        for (Widget child : children) {
            if (widgetTreeContainsAnyText(child, needlesLower)) {
                return true;
            }
        }
        return false;
    }

    private boolean widgetTreeContainsText(Widget widget, String needleLower) {
        if (widget == null || needleLower == null || needleLower.isEmpty()) {
            return false;
        }
        String text = normalizeOfferText(widget.getText());
        if (text != null && text.toLowerCase().contains(needleLower)) {
            return true;
        }
        if (widgetTreeContainsText(widget.getChildren(), needleLower)) {
            return true;
        }
        if (widgetTreeContainsText(widget.getDynamicChildren(), needleLower)) {
            return true;
        }
        return widgetTreeContainsText(widget.getNestedChildren(), needleLower);
    }

    private boolean widgetTreeContainsText(Widget[] children, String needleLower) {
        if (children == null) {
            return false;
        }
        for (Widget child : children) {
            if (widgetTreeContainsText(child, needleLower)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeOfferText(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text
            .replace("<br>", "\n")
            .replace("<br/>", "\n")
            .replace("<br />", "\n");
        return Text.removeTags(normalized);
    }

    private void clearOfferPreview() {
        if (offerPreviewItemId != null) {
            offerPreviewItemId = null;
            offerPreviewName = null;
            offerPreviewItem = null;
            if (panel != null) {
                panel.setOfferPreview(null, 0, null);
            }
        }
    }

    private void fetchOfferPreview(int itemId) {
        if (scheduler == null) {
            return;
        }
        if (!isClientLoggedIn()) {
            return;
        }
        offerPreviewItem = null;
        scheduler.execute(() -> {
            if (!isClientLoggedIn()) {
                return;
            }
            String sessionToken = config.sessionToken();
            if (sessionToken == null || sessionToken.isEmpty()) {
                if (clientThread != null) {
                    clientThread.invokeLater(() -> updateLocalOfferPreview(itemId));
                } else {
                    updateLocalOfferPreview(itemId);
                }
                return;
            }
            try {
                ApiClient.ItemResponse response = apiClient.fetchItem(sessionToken, itemId);
                if (response != null && response.item != null) {
                    applyLocalTradeInfoFallback(response.item);
                    offerPreviewItem = response.item;
                    suggestionDirty = true;
                    if (panel != null) {
                        panel.setOfferPreview(response.item, response.as_of_ms, response.price_cache_ms);
                    }
                } else {
                    offerPreviewItem = null;
                    suggestionDirty = true;
                }
            } catch (Exception ex) {
                if (isTimeoutException(ex)) {
                    log.debug("FlipHub offer preview fetch timed out");
                    return;
                }
                if (ex instanceof ApiClient.ApiException) {
                    ApiClient.ApiException apiEx = (ApiClient.ApiException) ex;
                    if (apiEx.statusCode == 401) {
                        boolean refreshed = attemptRefresh(config.sessionToken());
                        if (!refreshed && panel != null && isPanelVisible()) {
                            updateProfileHeader();
                        }
                        return;
                    }
                }
                offerPreviewItem = null;
                suggestionDirty = true;
                if (panel != null) {
                    panel.setOfferPreview(null, 0, null);
                }
                log.debug("FlipHub offer preview fetch failed", ex);
            }
        });
    }

    private FlipHubItem buildLocalOfferPreview(int itemId) {
        if (itemId <= 0) {
            return null;
        }
        ensureSelectedProfileLoaded();
        requestGeLimits(Collections.singleton(itemId));
        FlipHubItem item = new FlipHubItem();
        item.item_id = itemId;
        if (itemManager != null) {
            try {
                String name = itemManager.getItemComposition(itemId).getName();
                item.item_name = name;
            } catch (Exception ignored) {
            }
        }
        applyGuidePrices(item, itemId, false);
        long accountKey = resolveSelectedProfileKey();
        LocalLimitInfo limitInfoForItem = null;
        if (accountKey >= 0) {
            Map<Integer, LocalTradeInfo> tradeInfo = buildLocalTradeInfo(accountKey);
            applyLocalTradeInfo(item, tradeInfo.get(itemId));
            Map<Integer, LocalLimitInfo> limitInfo = buildLocalLimitInfo(accountKey, System.currentTimeMillis());
            limitInfoForItem = limitInfo.get(itemId);
            applyLocalLimitInfo(item, itemId, limitInfoForItem);
        }
        if ((item.ge_limit_total == null || item.ge_limit_total <= 0) && itemManager != null) {
            try {
                net.runelite.client.game.ItemStats stats = itemManager.getItemStats(itemId);
                if (stats != null && stats.getGeLimit() > 0) {
                    int limit = stats.getGeLimit();
                    item.ge_limit_total = limit;
                    if (limitInfoForItem != null && limitInfoForItem.buyQty > 0) {
                        int remaining = (int) Math.max(0L, limit - limitInfoForItem.buyQty);
                        item.ge_limit_remaining = remaining;
                        if (limitInfoForItem.firstBuyTs != null) {
                            long resetAt = limitInfoForItem.firstBuyTs + (4 * 60 * 60 * 1000L);
                            long remainingMs = Math.max(0L, resetAt - System.currentTimeMillis());
                            item.ge_limit_reset_ms = remainingMs;
                        } else {
                            item.ge_limit_reset_ms = 0L;
                        }
                    } else {
                        item.ge_limit_remaining = limit;
                        item.ge_limit_reset_ms = 0L;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        applyMarginInfo(item);
        return item;
    }

    private void applyLocalTradeInfoFallback(FlipHubItem item) {
        if (item == null || item.item_id <= 0) {
            return;
        }
        boolean needsBuy = item.last_buy_price == null || item.last_buy_price <= 0;
        boolean needsSell = item.last_sell_price == null || item.last_sell_price <= 0;
        if (!needsBuy && !needsSell) {
            return;
        }
        long accountKey = resolveSelectedProfileKey();
        if (accountKey < 0) {
            return;
        }
        Map<Integer, LocalTradeInfo> tradeInfo = buildLocalTradeInfo(accountKey);
        LocalTradeInfo info = tradeInfo.get(item.item_id);
        if (info == null) {
            return;
        }
        if (needsBuy && info.lastBuyPrice != null && info.lastBuyPrice > 0) {
            item.last_buy_price = info.lastBuyPrice;
            item.last_buy_ts_ms = info.lastBuyTs;
        }
        if (needsSell && info.lastSellPrice != null && info.lastSellPrice > 0) {
            item.last_sell_price = info.lastSellPrice;
            item.last_sell_ts_ms = info.lastSellTs;
        }
    }

    private void updateLocalOfferPreview(int itemId) {
        FlipHubItem localItem = buildLocalOfferPreview(itemId);
        offerPreviewItem = localItem;
        suggestionDirty = true;
        if (panel != null) {
            panel.setOfferPreview(localItem, System.currentTimeMillis(), null);
        }
    }

    private void updateLocalItemsPanel() {
        ensureSelectedProfileLoaded();
        ApiClient.ItemsResponse local = buildLocalItemsResponse(true);
        if (panel != null) {
            panel.setItems(
                local != null ? local.items : null,
                local != null ? local.page : 1,
                local != null ? local.total_pages : 1,
                local != null ? local.as_of_ms : System.currentTimeMillis(),
                local != null ? local.price_cache_ms : null
            );
        }
    }

    private void scheduleRefreshSoon() {
        if (scheduler == null) {
            return;
        }
        if (!refreshQueued.compareAndSet(false, true)) {
            return;
        }
        scheduler.schedule(() -> {
            try {
                refreshPanelData();
            } finally {
                refreshQueued.set(false);
            }
        }, 1, TimeUnit.SECONDS);
    }

    private void triggerPanelRefresh() {
        if (scheduler != null) {
            scheduler.execute(this::refreshPanelData);
        } else {
            Executors.newSingleThreadExecutor().execute(this::refreshPanelData);
        }
    }

    private void triggerStatsRefresh() {
        if (panel != null && !panel.isStatsTabSelected()) {
            return;
        }
        if (scheduler != null) {
            scheduler.execute(this::refreshStatsData);
        } else {
            Executors.newSingleThreadExecutor().execute(this::refreshStatsData);
        }
    }

    private boolean attemptRefresh(String currentToken) {
        try {
            ApiClient.LinkResponse response = apiClient.refreshSession(currentToken);
            if (response.session_token != null) {
                configManager.setConfiguration("fliphub_dev", "sessionToken", response.session_token);
                if (response.signing_secret != null && !response.signing_secret.isEmpty()) {
                    configManager.setConfiguration("fliphub_dev", "signingSecret", response.signing_secret);
                }
                return true;
            }
        } catch (Exception ex) {
            String message = ex.getMessage();
            if (message != null && (message.contains("401") || message.contains("403"))) {
                clearSession();
            }
            // Ignore; user can relink if needed
        }
        return false;
    }

    private void clearSession() {
        if (configManager == null) {
            return;
        }
        configManager.setConfiguration("fliphub_dev", "sessionToken", "");
        configManager.setConfiguration("fliphub_dev", "signingSecret", "");
    }

    private void renderLocalStats() {
        if (panel == null) {
            return;
        }
        ensureSelectedProfileLoaded();
        long nowMs = System.currentTimeMillis();
        StatsRange range = currentStatsRange != null ? currentStatsRange : StatsRange.SESSION;
        long accountKey = resolveSelectedProfileKey();
        long sessionStartMs = nowMs;
        if (accountKey >= 0) {
            synchronized (localStatsLock) {
                Long storedStart = localSessionStartByAccount.get(accountKey);
                if (storedStart != null && storedStart > 0) {
                    sessionStartMs = storedStart;
                } else {
                    localSessionStartByAccount.put(accountKey, nowMs);
                }
            }
        }
        Long sinceMs = range.getSinceMs(sessionStartMs, nowMs);
        StatsItemSort sort = currentStatsSort != null ? currentStatsSort : StatsItemSort.COMPLETION;
        LocalStatsSnapshot localStats = accountKey >= 0
            ? buildLocalStatsSnapshot(accountKey, sinceMs, sort)
            : new LocalStatsSnapshot(new StatsSummary(), new ArrayList<>());
        if (accountKey == ACCOUNTWIDE_KEY && isLinked()) {
            if (renderRemoteStats(nowMs, sinceMs, sort, localStats)) {
                return;
            }
        }
        panel.setStatsSummary(localStats.summary, nowMs);
        panel.setStatsItems(localStats.items, nowMs);
    }

    private boolean renderRemoteStats(long nowMs, Long sinceMs, StatsItemSort sort, LocalStatsSnapshot fallback) {
        if (panel == null || apiClient == null || config == null) {
            return false;
        }
        String sessionToken = config.sessionToken();
        if (sessionToken == null || sessionToken.isEmpty()) {
            return false;
        }
        ApiClient.StatsSummaryResponse summaryResponse = fetchRemoteStatsSummary(sessionToken, sinceMs, true);
        if (summaryResponse == null || summaryResponse.summary == null) {
            return false;
        }
        ApiClient.StatsItemsResponse itemsResponse = fetchRemoteStatsItems(sessionToken, sinceMs, sort, true);

        long summaryAsOf = summaryResponse.as_of_ms > 0 ? summaryResponse.as_of_ms : nowMs;
        panel.setStatsSummary(summaryResponse.summary, summaryAsOf);

        if (itemsResponse != null && itemsResponse.items != null) {
            long itemsAsOf = itemsResponse.as_of_ms > 0 ? itemsResponse.as_of_ms : nowMs;
            panel.setStatsItems(itemsResponse.items, itemsAsOf);
        } else if (fallback != null) {
            panel.setStatsItems(fallback.items, nowMs);
        }
        return true;
    }

    private ApiClient.StatsSummaryResponse fetchRemoteStatsSummary(String sessionToken, Long sinceMs, boolean allowRefresh) {
        try {
            return apiClient.fetchStatsSummary(sessionToken, sinceMs, null);
        } catch (ApiClient.ApiException ex) {
            if (ex.statusCode == 401 && allowRefresh) {
                boolean refreshed = attemptRefresh(sessionToken);
                if (refreshed) {
                    String refreshedToken = config.sessionToken();
                    if (refreshedToken != null && !refreshedToken.isEmpty()) {
                        return fetchRemoteStatsSummary(refreshedToken, sinceMs, false);
                    }
                }
                clearSession();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private ApiClient.StatsItemsResponse fetchRemoteStatsItems(String sessionToken, Long sinceMs, StatsItemSort sort,
        boolean allowRefresh) {
        try {
            return apiClient.fetchStatsItems(sessionToken, sinceMs, null, 200, sort);
        } catch (ApiClient.ApiException ex) {
            if (ex.statusCode == 401 && allowRefresh) {
                boolean refreshed = attemptRefresh(sessionToken);
                if (refreshed) {
                    String refreshedToken = config.sessionToken();
                    if (refreshedToken != null && !refreshedToken.isEmpty()) {
                        return fetchRemoteStatsItems(refreshedToken, sinceMs, sort, false);
                    }
                }
                clearSession();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void refreshPanelData() {
        if (java.awt.EventQueue.isDispatchThread()) {
            Runnable task = this::refreshPanelData;
            if (scheduler != null) {
                scheduler.execute(task);
            } else {
                Executors.newSingleThreadExecutor().execute(task);
            }
            return;
        }

        if (!isClientLoggedIn()) {
            return;
        }
        if (!isPanelVisible()) {
            return;
        }

        if (refreshInFlight.getAndSet(true)) {
            return;
        }

        try {
            long selectedKey = resolveSelectedProfileKey();
            String sessionToken = config.sessionToken();
            if (selectedKey == ACCOUNTWIDE_KEY) {
                if (panel != null) {
                    updateProfileHeader();
                    if (clientThread != null) {
                        clientThread.invokeLater(this::updateLocalItemsPanel);
                    } else {
                        updateLocalItemsPanel();
                    }
                }
                refreshInFlight.set(false);
                return;
            }
            if (sessionToken == null || sessionToken.isEmpty() || selectedKey != ACCOUNTWIDE_KEY) {
                if (panel != null) {
                    updateProfileHeader();
                    if (clientThread != null) {
                        clientThread.invokeLater(this::updateLocalItemsPanel);
                    } else {
                        updateLocalItemsPanel();
                    }
                }
                refreshInFlight.set(false);
                return;
            }
            String signingSecret = config.signingSecret();
            if (signingSecret == null || signingSecret.isEmpty()) {
                if (panel != null) {
                    updateProfileHeader();
                    if (clientThread != null) {
                        clientThread.invokeLater(this::updateLocalItemsPanel);
                    } else {
                        updateLocalItemsPanel();
                    }
                }
                refreshInFlight.set(false);
                return;
            }

            ApiClient.ItemsResponse response = fetchItemsForCurrentMode(sessionToken);
            if (response != null && panel != null) {
                panel.setItems(
                    response.items,
                    response.page,
                    response.total_pages,
                    response.as_of_ms,
                    response.price_cache_ms
                );
                if (isPanelVisible()) {
                    updateProfileHeader();
                }
            }
        } catch (Exception ex) {
            if (isTimeoutException(ex)) {
                log.debug("FlipHub fetch items timed out");
                scheduleRefreshSoon();
                return;
            }
            if (ex instanceof ApiClient.ApiException) {
                ApiClient.ApiException apiEx = (ApiClient.ApiException) ex;
                if (apiEx.statusCode == 401) {
                    log.debug("FlipHub fetch items failed: unauthorized");
                    boolean refreshed = attemptRefresh(config.sessionToken());
                    if (refreshed) {
                        try {
                            ApiClient.ItemsResponse retry = fetchItemsForCurrentMode(config.sessionToken());
                            if (retry != null && panel != null) {
                                panel.setItems(
                                    retry.items,
                                    retry.page,
                                    retry.total_pages,
                                    retry.as_of_ms,
                                    retry.price_cache_ms
                                );
                                if (isPanelVisible()) {
                                    updateProfileHeader();
                                }
                                refreshInFlight.set(false);
                                return;
                            }
                        } catch (Exception ignored) {
                            // fall through to status update
                        }
                    }
                    if (panel != null && !refreshed) {
                        clearSession();
                        updateProfileHeader();
                        if (clientThread != null) {
                            clientThread.invokeLater(this::updateLocalItemsPanel);
                        } else {
                            updateLocalItemsPanel();
                        }
                    }
                } else {
                    boolean refreshed = tryRefreshSession();
                    if (refreshed) {
                        scheduleRefreshSoon();
                        return;
                    }
                    log.warn("FlipHub fetch items failed", ex);
                }
            } else {
                boolean refreshed = tryRefreshSession();
                if (refreshed) {
                    scheduleRefreshSoon();
                    return;
                }
                log.warn("FlipHub fetch items failed", ex);
            }
        } finally {
            refreshInFlight.set(false);
        }
    }

    private ApiClient.ItemsResponse fetchItemsForCurrentMode(String sessionToken) throws Exception {
        if (!bookmarkFilterEnabled) {
            return apiClient.fetchItems(sessionToken, currentQuery, currentPage, DEFAULT_ITEMS_PAGE_SIZE);
        }

        if (bookmarkedItems.isEmpty()) {
            return emptyItemsResponse(System.currentTimeMillis(), null);
        }

        ApiClient.ItemsResponse allItems = fetchAllItems(sessionToken, currentQuery, BOOKMARK_ITEMS_PAGE_SIZE);
        if (allItems == null) {
            return null;
        }

        List<FlipHubItem> items = allItems.items != null ? allItems.items : Collections.emptyList();
        List<FlipHubItem> bookmarked = items.stream()
            .filter(item -> item != null && bookmarkedItems.contains(item.item_id))
            .collect(Collectors.toList());

        return buildItemsResponse(bookmarked, allItems.as_of_ms, allItems.price_cache_ms);
    }

    private ApiClient.ItemsResponse buildLocalItemsResponse(boolean includeEmptyFallback) {
        if (client == null) {
            return emptyItemsResponse(System.currentTimeMillis(), null);
        }

        long accountKey = resolveSelectedProfileKey();
        Map<Integer, LocalTradeInfo> tradeInfo = accountKey >= 0 ? buildLocalTradeInfo(accountKey) : new HashMap<>();
        Map<Integer, LocalLimitInfo> limitInfo = accountKey >= 0
            ? buildLocalLimitInfo(accountKey, System.currentTimeMillis())
            : new HashMap<>();

        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        if (offers == null || offers.length == 0) {
            offers = new GrandExchangeOffer[0];
        }

        List<FlipHubItem> items = new ArrayList<>();
        Set<Integer> seenItemIds = new HashSet<>();
        int filteredQuery = 0;
        int filteredBookmark = 0;
        
        Set<Integer> itemsNeedingLimits = new HashSet<>();
        for (GrandExchangeOffer offer : offers) {
            if (offer == null) {
                continue;
            }
            GrandExchangeOfferState state = offer.getState();
            if (state == null || state == GrandExchangeOfferState.EMPTY) {
                continue;
            }
            int itemId = offer.getItemId();
            if (itemId <= 0) {
                continue;
            }

            String name = null;
            if (itemManager != null) {
                name = getCachedItemName(itemId);
            }
            if (name == null || name.trim().isEmpty()) {
                cacheItemName(itemId);
            }
            if (currentQuery != null && !currentQuery.trim().isEmpty()) {
                String needle = currentQuery.trim().toLowerCase(Locale.US);
                String hay = name != null ? name.toLowerCase(Locale.US) : "";
                if (!hay.contains(needle)) {
                    filteredQuery++;
                    continue;
                }
            }
            if (bookmarkFilterEnabled && !bookmarkedItems.contains(itemId)) {
                filteredBookmark++;
                continue;
            }

            FlipHubItem item = new FlipHubItem();
            item.item_id = itemId;
            item.item_name = name;
            applyGuidePrices(item, itemId, true);
            boolean isBuy = state == GrandExchangeOfferState.BUYING || state == GrandExchangeOfferState.BOUGHT;
            if (isBuy) {
                item.last_buy_price = offer.getPrice();
            } else {
                item.last_sell_price = offer.getPrice();
            }
            applyLocalTradeInfo(item, tradeInfo.get(itemId));
            applyLocalLimitInfo(item, itemId, limitInfo.get(itemId));
            applyMarginInfo(item);
            if (!seenItemIds.contains(itemId)) {
                items.add(item);
                seenItemIds.add(itemId);
                itemsNeedingLimits.add(itemId);
            }
        }

        if (!tradeInfo.isEmpty()) {
            for (Map.Entry<Integer, LocalTradeInfo> entry : tradeInfo.entrySet()) {
                if (entry == null || entry.getKey() == null) {
                    continue;
                }
                int itemId = entry.getKey();
                if (itemId <= 0 || seenItemIds.contains(itemId)) {
                    continue;
                }
                String name = null;
                if (itemManager != null) {
                    name = getCachedItemName(itemId);
                }
                if (name == null || name.trim().isEmpty()) {
                    cacheItemName(itemId);
                }
                if (currentQuery != null && !currentQuery.trim().isEmpty()) {
                    String needle = currentQuery.trim().toLowerCase(Locale.US);
                    String hay = name != null ? name.toLowerCase(Locale.US) : "";
                    if (!hay.contains(needle)) {
                        filteredQuery++;
                        continue;
                    }
                }
                if (bookmarkFilterEnabled && !bookmarkedItems.contains(itemId)) {
                    filteredBookmark++;
                    continue;
                }
                FlipHubItem item = new FlipHubItem();
                item.item_id = itemId;
                item.item_name = name;
                applyGuidePrices(item, itemId, true);
                applyLocalTradeInfo(item, entry.getValue());
                applyLocalLimitInfo(item, itemId, limitInfo.get(itemId));
                applyMarginInfo(item);
                items.add(item);
                seenItemIds.add(itemId);
                itemsNeedingLimits.add(itemId);
            }
        }

        if (!itemsNeedingLimits.isEmpty()) {
            requestGeLimits(itemsNeedingLimits);
        }

        if (items.isEmpty() && includeEmptyFallback) {
            ApiClient.ItemsResponse stampFallback = buildOfferStampFallback();
            if (stampFallback != null && stampFallback.items != null && !stampFallback.items.isEmpty()) {
                return stampFallback;
            }
            return buildOfferStatusFallback();
        }

        items.sort((a, b) -> {
            long aTs = Math.max(a != null && a.last_sell_ts_ms != null ? a.last_sell_ts_ms : 0L,
                a != null && a.last_buy_ts_ms != null ? a.last_buy_ts_ms : 0L);
            long bTs = Math.max(b != null && b.last_sell_ts_ms != null ? b.last_sell_ts_ms : 0L,
                b != null && b.last_buy_ts_ms != null ? b.last_buy_ts_ms : 0L);
            int tsCompare = Long.compare(bTs, aTs);
            if (tsCompare != 0) {
                return tsCompare;
            }
            String aName = a != null && a.item_name != null ? a.item_name : "";
            String bName = b != null && b.item_name != null ? b.item_name : "";
            return aName.compareToIgnoreCase(bName);
        });

        int totalItems = items.size();
        int pageSize = DEFAULT_ITEMS_PAGE_SIZE;
        int totalPages = totalItems == 0 ? 1 : (int) Math.ceil(totalItems / (double) pageSize);
        int page = Math.max(1, Math.min(currentPage, totalPages));
        int start = (page - 1) * pageSize;
        int end = Math.min(start + pageSize, totalItems);
        List<FlipHubItem> pageItems = start >= end ? Collections.emptyList() : items.subList(start, end);

        return buildPagedItemsResponse(pageItems, page, pageSize, totalItems, totalPages,
            System.currentTimeMillis(), null);
    }

    private ApiClient.ItemsResponse buildOfferStatusFallback() {
        Widget geRoot = getVisibleGeRoot();
        if (geRoot == null) {
            return emptyItemsResponse(System.currentTimeMillis(), null);
        }
        int itemId = findFirstItemId(geRoot);
        if (itemId <= 0) {
            return emptyItemsResponse(System.currentTimeMillis(), null);
        }

        FlipHubItem item = buildLocalOfferPreview(itemId);
        if (item == null) {
            return emptyItemsResponse(System.currentTimeMillis(), null);
        }

        return buildPagedItemsResponse(Collections.singletonList(item), 1, 1, 1, 1,
            System.currentTimeMillis(), null);
    }

    private ApiClient.ItemsResponse buildOfferStampFallback() {
        if (offerUpdateStamps.isEmpty()) {
            return emptyItemsResponse(System.currentTimeMillis(), null);
        }

        List<FlipHubItem> items = new ArrayList<>();
        for (OfferUpdateStamp stamp : offerUpdateStamps.values()) {
            if (stamp == null || stamp.itemId <= 0) {
                continue;
            }
            FlipHubItem item = new FlipHubItem();
            item.item_id = stamp.itemId;
            if (itemManager != null) {
                try {
                    item.item_name = itemManager.getItemComposition(stamp.itemId).getName();
                } catch (Exception ignored) {
                }
                int guidePrice = 0;
                try {
                    guidePrice = itemManager.getItemPrice(stamp.itemId);
                } catch (Exception ignored) {
                }
                if (guidePrice > 0) {
                    item.instabuy_price = guidePrice;
                    item.instasell_price = guidePrice;
                }
            }
            if (stamp.isBuy) {
                item.last_buy_price = stamp.price;
            } else {
                item.last_sell_price = stamp.price;
            }
            if (item.last_buy_price != null && item.last_sell_price != null
                && item.last_buy_price > 0 && item.last_sell_price > 0) {
                int margin = item.last_sell_price - item.last_buy_price;
                item.margin = margin;
                item.roi_percent = item.last_buy_price > 0 ? (margin * 100.0) / item.last_buy_price : null;
            }
            items.add(item);
        }

        if (items.isEmpty()) {
            return emptyItemsResponse(System.currentTimeMillis(), null);
        }

        int total = items.size();
        return buildPagedItemsResponse(items, 1, total, total, 1, System.currentTimeMillis(), null);
    }

    private ApiClient.ItemsResponse fetchAllItems(String sessionToken, String query, int pageSize) throws Exception {
        ApiClient.ItemsResponse firstPage = apiClient.fetchItems(sessionToken, query, 1, pageSize);
        if (firstPage == null) {
            return null;
        }

        List<FlipHubItem> allItems = new ArrayList<>();
        if (firstPage.items != null) {
            allItems.addAll(firstPage.items);
        }

        int totalPages = Math.max(1, firstPage.total_pages);
        long asOfMs = firstPage.as_of_ms;
        Long priceCacheMs = firstPage.price_cache_ms;

        for (int page = 2; page <= totalPages; page++) {
            ApiClient.ItemsResponse nextPage = apiClient.fetchItems(sessionToken, query, page, pageSize);
            if (nextPage == null) {
                continue;
            }
            if (nextPage.items != null) {
                allItems.addAll(nextPage.items);
            }
            asOfMs = nextPage.as_of_ms;
            priceCacheMs = nextPage.price_cache_ms;
        }

        return buildItemsResponse(allItems, asOfMs, priceCacheMs);
    }

    private ApiClient.ItemsResponse buildItemsResponse(List<FlipHubItem> items, long asOfMs, Long priceCacheMs) {
        int total = items != null ? items.size() : 0;
        return buildPagedItemsResponse(items, 1, total, total, 1, asOfMs, priceCacheMs);
    }

    private ApiClient.ItemsResponse emptyItemsResponse(long asOfMs, Long priceCacheMs) {
        return buildItemsResponse(Collections.emptyList(), asOfMs, priceCacheMs);
    }

    private ApiClient.ItemsResponse buildPagedItemsResponse(List<FlipHubItem> items, int page, int pageSize,
        int totalItems, int totalPages, long asOfMs, Long priceCacheMs) {
        ApiClient.ItemsResponse response = new ApiClient.ItemsResponse();
        response.items = items != null ? new ArrayList<>(items) : Collections.emptyList();
        response.page = page;
        response.page_size = pageSize;
        response.total_items = totalItems;
        response.total_pages = totalPages;
        response.as_of_ms = asOfMs;
        response.price_cache_ms = priceCacheMs;
        return response;
    }

    private Long getStatsSinceMs() {
        StatsRange range = currentStatsRange != null ? currentStatsRange : StatsRange.SESSION;
        long nowMs = System.currentTimeMillis();
        long sessionStartMs = nowMs;
        long accountKey = resolveAccountHash();
        if (accountKey > 0) {
            synchronized (localStatsLock) {
                Long storedStart = localSessionStartByAccount.get(accountKey);
                if (storedStart != null && storedStart > 0) {
                    sessionStartMs = storedStart;
                } else {
                    localSessionStartByAccount.put(accountKey, nowMs);
                }
            }
        }
        return range.getSinceMs(sessionStartMs, nowMs);
    }

    private void refreshStatsData() {
        if (java.awt.EventQueue.isDispatchThread()) {
            Runnable task = this::refreshStatsData;
            if (scheduler != null) {
                scheduler.execute(task);
            } else {
                Executors.newSingleThreadExecutor().execute(task);
            }
            return;
        }

        if (!isClientLoggedIn()) {
            return;
        }
        if (!isPanelVisible() || panel == null || !panel.isStatsTabSelected()) {
            return;
        }

        if (statsRefreshInFlight.getAndSet(true)) {
            return;
        }

        try {
            renderLocalStats();
        } finally {
            statsRefreshInFlight.set(false);
        }
    }

    private void requeue(List<GeEvent> batch) {
        for (GeEvent event : batch) {
            eventQueue.offer(event);
        }
    }

    private boolean isPanelVisible() {
        return panel != null && panel.isShowing();
    }

    private boolean tryRefreshSession() {
        if (!isClientLoggedIn()) {
            return false;
        }
        String token = config.sessionToken();
        if (token == null || token.isEmpty()) {
            return false;
        }
        return attemptRefresh(token);
    }

    private boolean isTimeoutException(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof java.net.SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isClientLoggedIn() {
        return client != null && client.getGameState() == GameState.LOGGED_IN;
    }

    private static final class OfferUpdateStamp {
        private int itemId;
        private int price;
        private int totalQty;
        private int filledQty;
        private boolean isBuy;
        private long spentGp;
        private long lastUpdateMs;
        private long firstSeenMs;
        private long completedMs;
        private long lastEmptyMs;

        private OfferUpdateStamp() {
        }

        private OfferUpdateStamp(int itemId, int price, int totalQty, int filledQty, boolean isBuy, long spentGp,
            long lastUpdateMs, long firstSeenMs, long completedMs, long lastEmptyMs) {
            this.itemId = itemId;
            this.price = price;
            this.totalQty = totalQty;
            this.filledQty = filledQty;
            this.isBuy = isBuy;
            this.spentGp = spentGp;
            this.lastUpdateMs = lastUpdateMs;
            this.firstSeenMs = firstSeenMs;
            this.completedMs = completedMs;
            this.lastEmptyMs = lastEmptyMs;
        }

        private static OfferUpdateStamp fromSnapshot(OfferSnapshot snapshot, long timestamp) {
            if (snapshot == null) {
                return null;
            }
            long safeTimestamp = timestamp > 0 ? timestamp : System.currentTimeMillis();
            return new OfferUpdateStamp(
                snapshot.itemId,
                snapshot.price,
                snapshot.totalQty,
                snapshot.filledQty,
                snapshot.isBuy,
                snapshot.spentGp,
                safeTimestamp,
                safeTimestamp,
                0L,
                0L
            );
        }

        private static OfferUpdateStamp fromOffer(GrandExchangeOffer offer, long timestamp, boolean isBuy) {
            if (offer == null) {
                return null;
            }
            long safeTimestamp = timestamp > 0 ? timestamp : System.currentTimeMillis();
            return new OfferUpdateStamp(
                offer.getItemId(),
                offer.getPrice(),
                offer.getTotalQuantity(),
                offer.getQuantitySold(),
                isBuy,
                offer.getSpent(),
                safeTimestamp,
                safeTimestamp,
                0L,
                0L
            );
        }
    }

    private long computeDeltaGp(OfferSnapshot next, OfferSnapshot prev, int deltaQty) {
        long deltaGp = prev != null ? next.spentGp - prev.spentGp : next.spentGp;
        if (deltaGp < 0) {
            deltaGp = 0;
        }
        if (deltaQty > 0 && deltaGp <= 0) {
            long total = (long) next.price * (long) deltaQty;
            if (!next.isBuy) {
                long tax = total / 50; // 2% GE tax fallback
                deltaGp = Math.max(0L, total - tax);
            } else {
                deltaGp = Math.max(0L, total);
            }
        }
        return deltaGp;
    }

    private long computeDeltaGpFromBaseline(OfferSnapshot next, long baselineSpentGp, int deltaQty) {
        long deltaGp = next.spentGp - baselineSpentGp;
        if (deltaGp < 0) {
            deltaGp = 0;
        }
        if (deltaQty > 0 && deltaGp <= 0) {
            long total = (long) next.price * (long) deltaQty;
            if (!next.isBuy) {
                long tax = total / 50; // 2% GE tax fallback
                deltaGp = Math.max(0L, total - tax);
            } else {
                deltaGp = Math.max(0L, total);
            }
        }
        return deltaGp;
    }

    private void recordLocalTradeDelta(GeEvent event, boolean baselineSynthetic) {
        if (event == null) {
            return;
        }
        boolean hasDelta = event.delta_qty > 0 || event.delta_gp > 0;
        if (!hasDelta && !"OFFER_COMPLETED".equals(event.event_type)) {
            return;
        }
        if (baselineSynthetic) {
            return;
        }
        long accountKey = resolveLocalAccountKey();
        if (accountKey <= 0) {
            return;
        }
        ensureProfileLoaded(accountKey);
        ensureProfileLoaded(ACCOUNTWIDE_KEY);
        ensureLocalSessionStart(accountKey, event.ts_client_ms);
        ensureLocalSessionStart(ACCOUNTWIDE_KEY, event.ts_client_ms);
        LocalTradeDelta delta = new LocalTradeDelta(
            event.ts_client_ms,
            event.item_id,
            event.is_buy,
            event.delta_qty,
            event.delta_gp,
            event.event_type,
            event.price,
            baselineSynthetic
        );
        cacheItemName(event.item_id);
        synchronized (localStatsLock) {
            appendTradeDelta(accountKey, delta);
            appendTradeDelta(ACCOUNTWIDE_KEY, delta);
        }
        applyDeltaToStatsCache(accountKey, delta);
        persistLocalTrades(accountKey);
        persistLocalTrades(ACCOUNTWIDE_KEY);
        triggerStatsRefresh();
        triggerPanelRefresh();
    }

    private void ensureLocalTradesLoaded(long accountKey) {
        if (accountKey <= 0) {
            return;
        }
        ensureProfileLoaded(accountKey);
        localTradesLoadedThisLogin = true;
    }

    private void scheduleLocalTradesLoad() {
        long nowMs = System.currentTimeMillis();
        if (nowMs - localTradesLastLoadAttemptMs < LOCAL_TRADES_LOAD_RETRY_MS) {
            return;
        }
        localTradesLastLoadAttemptMs = nowMs;
        if (clientThread == null) {
            attemptLocalTradesLoad();
            return;
        }
        clientThread.invokeLater(this::attemptLocalTradesLoad);
    }

    private void attemptLocalTradesLoad() {
        long accountHash = resolveAccountHash();
        if (accountHash <= 0) {
            return;
        }
        ensureProfileLoaded(accountHash);
        localTradesLoadedThisLogin = true;
    }

    private boolean loadLocalTradesForAccount(long accountHash) {
        return loadLocalTradesForAccount(accountHash, true);
    }

    private boolean loadLocalTradesForAccount(long accountHash, boolean persistAfterLoad) {
        if (accountHash < 0 || gson == null) {
            return false;
        }
        Path file = getProfileFile(accountHash);
        if (file != null) {
            long fileMs = getProfileFileModifiedMs(file);
            if (fileMs > 0) {
                loadedProfileFileMs.put(accountHash, fileMs);
            }
        }
        ProfileData profile = readProfileData(accountHash);
        List<LocalTradeDelta> merged = profile != null ? profile.deltas : null;
        String profileName = profile != null ? profile.displayName : null;
        boolean placeholderName = isPlaceholderDisplayName(profileName);
        if (accountHash == ACCOUNTWIDE_KEY) {
            merged = buildAccountwideFromDisk();
        }
        if (accountHash != ACCOUNTWIDE_KEY) {
            if (merged == null || merged.isEmpty()) {
                merged = readLegacyLocalTrades(accountHash);
            } else if (placeholderName) {
                List<LocalTradeDelta> legacy = readLegacyLocalTrades(accountHash);
                if (legacy != null && !legacy.isEmpty()) {
                    merged = legacy;
                }
            }
        }
        if (merged == null) {
            merged = new ArrayList<>();
        }
        synchronized (localStatsLock) {
            localTradeDeltasByAccount.put(accountHash, new ArrayList<>(merged));
        }
        rebuildStatsCache(accountHash, merged);
        String resolvedName = null;
        if (profileName != null && !profileName.trim().isEmpty() && !isPlaceholderDisplayName(profileName)) {
            resolvedName = profileName.trim();
        }
        if (resolvedName == null) {
            String legacyKey = legacyNameKeysByHash.get(accountHash);
            String legacyDisplay = displayNameFromLegacyKey(legacyKey);
            if (legacyDisplay == null) {
                legacyDisplay = resolveLegacyDisplayNameForHash(accountHash);
            }
            if (legacyDisplay != null && !legacyDisplay.trim().isEmpty()) {
                resolvedName = legacyDisplay.trim();
            }
        }
        if (resolvedName != null && !resolvedName.trim().isEmpty()) {
            profileDisplayNames.put(accountHash, resolvedName.trim());
        }
        for (LocalTradeDelta delta : merged) {
            if (delta != null && delta.itemId > 0) {
                cacheItemName(delta.itemId);
            }
        }
        if (persistAfterLoad) {
            persistLocalTrades(accountHash);
        } else if (accountHash != ACCOUNTWIDE_KEY) {
            accountwideDirty.set(true);
        }
        scheduleRefreshSoon();
        triggerStatsRefresh();
        return true;
    }

    private boolean isPlaceholderDisplayName(String displayName) {
        if (displayName == null) {
            return true;
        }
        String trimmed = displayName.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        return trimmed.startsWith("Profile ");
    }

    private void persistLocalTrades(long accountKey) {
        if (accountKey < 0 || gson == null) {
            return;
        }
        List<LocalTradeDelta> snapshot;
        synchronized (localStatsLock) {
            List<LocalTradeDelta> deltas = localTradeDeltasByAccount.get(accountKey);
            snapshot = deltas != null ? new ArrayList<>(deltas) : new ArrayList<>();
        }
        writeProfileData(accountKey, snapshot);
        localTradesLoadedThisLogin = true;
        if (accountKey != ACCOUNTWIDE_KEY) {
            accountwideDirty.set(true);
        }
    }

    private void appendTradeDelta(long accountKey, LocalTradeDelta delta) {
        if (accountKey < 0 || delta == null) {
            return;
        }
        List<LocalTradeDelta> deltas = localTradeDeltasByAccount.computeIfAbsent(accountKey, key -> new ArrayList<>());
        deltas.add(delta);
        if (deltas.size() > MAX_LOCAL_TRADES) {
            int trim = deltas.size() - MAX_LOCAL_TRADES;
            deltas.subList(0, trim).clear();
        }
    }

    private boolean hasLocalTrades(long accountKey) {
        if (accountKey < 0) {
            return false;
        }
        ensureProfileLoaded(accountKey);
        synchronized (localStatsLock) {
            List<LocalTradeDelta> deltas = localTradeDeltasByAccount.get(accountKey);
            if (deltas != null && !deltas.isEmpty()) {
                return true;
            }
        }
        // If a profile was loaded before the on-disk file changed, reload once.
        loadLocalTradesForAccount(accountKey);
        synchronized (localStatsLock) {
            List<LocalTradeDelta> deltas = localTradeDeltasByAccount.get(accountKey);
            return deltas != null && !deltas.isEmpty();
        }
    }

    private void ensureProfileLoaded(long accountKey) {
        if (accountKey < 0) {
            return;
        }
        if (accountKey == ACCOUNTWIDE_KEY) {
            if (!loadedProfiles.contains(accountKey) || accountwideDirty.getAndSet(false)) {
                loadLocalTradesForAccount(accountKey);
                loadedProfiles.add(accountKey);
            }
            return;
        }
        if (loadedProfiles.contains(accountKey)) {
            return;
        }
        loadLocalTradesForAccount(accountKey);
        loadedProfiles.add(accountKey);
    }

    private long getProfileFileModifiedMs(Path file) {
        if (file == null || !Files.exists(file)) {
            return 0L;
        }
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private void ensureSelectedProfileLoaded() {
        long key = resolveSelectedProfileKey();
        ensureProfileLoaded(key);
    }

    private void startProfileWatcher() {
        if (profileWatchRunning.get()) {
            return;
        }
        Path profilesDir = getProfilesDir();
        Path legacyDir = getLegacyProfilesDir();
        if (profilesDir == null && legacyDir == null) {
            return;
        }
        try {
            profileWatchService = FileSystems.getDefault().newWatchService();
            if (profilesDir != null && Files.exists(profilesDir)) {
                registerProfileWatchDir(profilesDir);
            }
            if (legacyDir != null && Files.exists(legacyDir)) {
                registerProfileWatchDir(legacyDir);
            }
        } catch (IOException ex) {
            profileWatchService = null;
            return;
        }
        profileWatchRunning.set(true);
        profileWatchThread = new Thread(this::runProfileWatchLoop, "fliphub-profile-watch");
        profileWatchThread.setDaemon(true);
        profileWatchThread.start();
    }

    private void stopProfileWatcher() {
        profileWatchRunning.set(false);
        if (profileWatchThread != null) {
            profileWatchThread.interrupt();
            profileWatchThread = null;
        }
        if (profileWatchService != null) {
            try {
                profileWatchService.close();
            } catch (IOException ignored) {
            }
            profileWatchService = null;
        }
        for (ScheduledFuture<?> future : pendingProfileReloads.values()) {
            if (future != null) {
                future.cancel(true);
            }
        }
        pendingProfileReloads.clear();
        profileWatchRoots.clear();
    }

    private void registerProfileWatchDir(Path dir) throws IOException {
        WatchKey key = dir.register(
            profileWatchService,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY
        );
        profileWatchRoots.put(key, dir);
    }

    private void runProfileWatchLoop() {
        while (profileWatchRunning.get()) {
            WatchKey key;
            try {
                key = profileWatchService.take();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                return;
            }
            Path root = profileWatchRoots.get(key);
            if (root != null) {
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event == null || event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }
                    Object context = event.context();
                    if (!(context instanceof Path)) {
                        continue;
                    }
                    Path file = root.resolve((Path) context);
                    long accountKey = parseAccountKeyFromProfileFile(file);
                    if (accountKey > 0) {
                        scheduleProfileReload(accountKey, file);
                    }
                }
            }
            if (!key.reset()) {
                profileWatchRoots.remove(key);
            }
        }
    }

    private long parseAccountKeyFromProfileFile(Path file) {
        if (file == null) {
            return -1L;
        }
        String name = file.getFileName().toString();
        if (name == null || !name.endsWith(".json")) {
            return -1L;
        }
        if ("accountwide.json".equalsIgnoreCase(name)) {
            return -1L;
        }
        if (!name.startsWith("hash_")) {
            return -1L;
        }
        String hashPart = name.substring(5, name.length() - ".json".length());
        try {
            return Long.parseLong(hashPart);
        } catch (Exception ex) {
            return -1L;
        }
    }

    private void scheduleProfileReload(long accountKey, Path file) {
        if (scheduler == null || accountKey <= 0) {
            return;
        }
        long fileMs = getProfileFileModifiedMs(file);
        Long loadedMs = loadedProfileFileMs.get(accountKey);
        if (fileMs > 0 && loadedMs != null && fileMs <= loadedMs) {
            return;
        }
        ScheduledFuture<?> existing = pendingProfileReloads.get(accountKey);
        if (existing != null && !existing.isDone()) {
            return;
        }
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            pendingProfileReloads.remove(accountKey);
            reloadProfileFromDisk(accountKey);
        }, PROFILE_WATCH_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        pendingProfileReloads.put(accountKey, future);
    }

    private void reloadProfileFromDisk(long accountKey) {
        if (accountKey <= 0) {
            return;
        }
        loadLocalTradesForAccount(accountKey, false);
        loadedProfiles.add(accountKey);
        accountwideDirty.set(true);
    }

    private Path getProfilesDir() {
        String home = System.getProperty("user.home");
        if (home == null || home.trim().isEmpty()) {
            return null;
        }
        Path dir = Paths.get(home, ".runelite", PROFILE_DIR_NAME, "profiles");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }
        migrateLegacyProfilesIfNeeded(home, dir);
        return dir;
    }

    private Path getLegacyProfilesDir() {
        String home = System.getProperty("user.home");
        if (home == null || home.trim().isEmpty()) {
            return null;
        }
        return Paths.get(home, ".runelite", LEGACY_PROFILE_DIR_NAME, "profiles");
    }

    private void migrateLegacyProfilesIfNeeded(String home, Path devDir) {
        if (home == null || devDir == null) {
            return;
        }
        if (!legacyProfilesMigrated.compareAndSet(false, true)) {
            return;
        }
        Path legacyDir = Paths.get(home, ".runelite", LEGACY_PROFILE_DIR_NAME, "profiles");
        if (!Files.exists(legacyDir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(legacyDir, "*.json")) {
            boolean devHasFiles = false;
            if (Files.exists(devDir)) {
                try (java.util.stream.Stream<Path> devStream = Files.list(devDir)) {
                    devHasFiles = devStream.findAny().isPresent();
                }
            }
            for (Path legacyFile : stream) {
                if (legacyFile == null) {
                    continue;
                }
                Path target = devDir.resolve(legacyFile.getFileName());
                if (Files.exists(target)) {
                    continue;
                }
                Files.copy(legacyFile, target);
                devHasFiles = true;
            }
            if (!devHasFiles) {
                legacyProfilesMigrated.set(false);
            }
        } catch (Exception ignored) {
        }
    }

    private Path getProfileFile(long accountHash) {
        Path dir = getProfilesDir();
        if (dir == null) {
            return null;
        }
        if (accountHash == ACCOUNTWIDE_KEY) {
            return dir.resolve("accountwide.json");
        }
        return dir.resolve("hash_" + accountHash + ".json");
    }

    private ProfileData readProfileData(long accountHash) {
        Path file = getProfileFile(accountHash);
        return readProfileData(file);
    }

    private ProfileData readProfileData(Path file) {
        if (file == null || !Files.exists(file)) {
            return null;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            if (json == null || json.trim().isEmpty()) {
                return null;
            }
            return gson.fromJson(json, ProfileData.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void writeProfileData(long accountHash, List<LocalTradeDelta> deltas) {
        Path file = getProfileFile(accountHash);
        if (file == null) {
            return;
        }
        ProfileData data = new ProfileData();
        data.accountHash = accountHash;
        data.displayName = accountHash == ACCOUNTWIDE_KEY ? "Accountwide" : profileDisplayNames.get(accountHash);
        data.deltas = deltas != null ? deltas : new ArrayList<>();
        data.updatedMs = System.currentTimeMillis();
        try {
            String json = gson.toJson(data);
            Files.writeString(file, json, StandardCharsets.UTF_8);
            long fileMs = getProfileFileModifiedMs(file);
            if (fileMs > 0) {
                loadedProfileFileMs.put(accountHash, fileMs);
            }
        } catch (Exception ignored) {
        }
    }

    private List<LocalTradeDelta> readLegacyLocalTrades(long accountHash) {
        if (configManager == null || gson == null || accountHash <= 0) {
            return null;
        }
        List<LocalTradeDelta> byName = null;
        String legacyNameKey = legacyNameKeysByHash.get(accountHash);
        if (legacyNameKey != null) {
            byName = readLegacyLocalTrades(legacyNameKey);
        }
        String primaryKey = "hash_" + accountHash;
        List<String> keys = buildLegacyLocalTradesKeys(primaryKey, accountHash);
        if (byName != null && !byName.isEmpty()) {
            return mergeLocalTrades(
                byName,
                readLegacyLocalTrades(keys, 0),
                readLegacyLocalTrades(keys, 1),
                readLegacyLocalTrades(keys, 2)
            );
        }
        return mergeLocalTrades(
            readLegacyLocalTrades(keys, 0),
            readLegacyLocalTrades(keys, 1),
            readLegacyLocalTrades(keys, 2),
            readLegacyLocalTrades(keys, 3)
        );
    }

    private List<LocalTradeDelta> buildAccountwideFromDisk() {
        List<LocalTradeDelta> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        mergeAccountwideFromDir(merged, seen, getProfilesDir());
        mergeAccountwideFromDir(merged, seen, getLegacyProfilesDir());
        mergeAccountwideFromLegacyConfig(merged, seen);
        if (merged.isEmpty()) {
            return null;
        }
        merged.sort(Comparator.comparingLong(delta -> delta != null ? delta.tsClientMs : 0L));
        if (merged.size() > MAX_LOCAL_TRADES) {
            int trim = merged.size() - MAX_LOCAL_TRADES;
            merged.subList(0, trim).clear();
        }
        return merged;
    }

    private void mergeAccountwideFromLegacyConfig(List<LocalTradeDelta> merged, Set<String> seen) {
        Map<String, String> entries = getLegacyLocalTradesCache();
        if (entries == null || entries.isEmpty()) {
            return;
        }
        Type type = new TypeToken<List<LocalTradeDelta>>() {}.getType();
        for (String raw : entries.values()) {
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            try {
                List<LocalTradeDelta> deltas = gson.fromJson(raw, type);
                if (deltas == null || deltas.isEmpty()) {
                    continue;
                }
                for (LocalTradeDelta delta : deltas) {
                    if (delta == null) {
                        continue;
                    }
                    if (seen.add(buildLocalTradeSignature(delta))) {
                        merged.add(delta);
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void mergeAccountwideFromDir(List<LocalTradeDelta> merged, Set<String> seen, Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "hash_*.json")) {
            for (Path path : stream) {
                String fileName = path.getFileName().toString();
                if (!fileName.startsWith("hash_")) {
                    continue;
                }
                String hashPart = fileName.substring(5, fileName.length() - ".json".length());
                long hash;
                try {
                    hash = Long.parseLong(hashPart);
                } catch (Exception ex) {
                    continue;
                }
                ProfileData data = readProfileData(path);
                if (data == null || data.deltas == null || data.deltas.isEmpty()) {
                    continue;
                }
                for (LocalTradeDelta delta : data.deltas) {
                    if (delta == null) {
                        continue;
                    }
                    if (seen.add(buildLocalTradeSignature(delta))) {
                        merged.add(delta);
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void cacheItemName(int itemId) {
        if (itemId <= 0 || itemManager == null || clientThread == null) {
            return;
        }
        if (itemNameCache.containsKey(itemId)) {
            return;
        }
        clientThread.invokeLater(() -> {
            try {
                String name = itemManager.getItemComposition(itemId).getName();
                if (name != null && !name.trim().isEmpty()) {
                    if (itemNameCache.putIfAbsent(itemId, name) == null) {
                        scheduleRefreshSoon();
                        triggerStatsRefresh();
                    }
                }
            } catch (AssertionError ignored) {
            } catch (Exception ignored) {
            }
        });
    }

    private String getCachedItemName(int itemId) {
        if (itemId <= 0) {
            return null;
        }
        return itemNameCache.get(itemId);
    }

    private List<LocalTradeDelta> readLocalTrades(String key) {
        if (key == null || key.trim().isEmpty() || configManager == null || gson == null) {
            return null;
        }
        String raw = configManager.getConfiguration("fliphub_dev", "localTrades." + key);
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            Type type = new TypeToken<List<LocalTradeDelta>>() {}.getType();
            return gson.fromJson(raw, type);
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<LocalTradeDelta> readLegacyLocalTrades(List<String> keys, int index) {
        if (keys == null || index < 0 || index >= keys.size()) {
            return null;
        }
        return readLegacyLocalTrades(keys.get(index));
    }

    private List<LocalTradeDelta> readLegacyLocalTrades(String key) {
        if (key == null || key.trim().isEmpty() || configManager == null || gson == null) {
            return null;
        }
        Map<String, String> legacyCache = getLegacyLocalTradesCache();
        String raw = legacyCache != null ? legacyCache.get(key.trim()) : null;
        if (raw == null || raw.trim().isEmpty()) {
            raw = configManager.getConfiguration(LEGACY_CONFIG_GROUP, "localTrades." + key);
        }
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            Type type = new TypeToken<List<LocalTradeDelta>>() {}.getType();
            return gson.fromJson(raw, type);
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<LocalTradeDelta> readLegacyLocalTrades(List<String> keys, int index) {
        if (keys == null || index < 0 || index >= keys.size()) {
            return null;
        }
        return readLegacyLocalTrades(keys.get(index));
    }

    private List<LocalTradeDelta> readLegacyLocalTrades(String key) {
        if (key == null || key.trim().isEmpty() || configManager == null || gson == null) {
            return null;
        }
        Map<String, String> legacyCache = getLegacyLocalTradesCache();
        String raw = legacyCache != null ? legacyCache.get(key.trim()) : null;
        if (raw == null || raw.trim().isEmpty()) {
            raw = configManager.getConfiguration(LEGACY_CONFIG_GROUP, "localTrades." + key);
        }
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            Type type = new TypeToken<List<LocalTradeDelta>>() {}.getType();
            return gson.fromJson(raw, type);
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<String> buildLocalTradesKeys(String primaryKey, long accountHash) {
        List<String> keys = new ArrayList<>();
        addLocalTradesKey(keys, primaryKey);
        addLocalTradesKey(keys, resolveLocalAccountNameKey());
        addLocalTradesKey(keys, resolveAccountHashKey());
        if (accountHash > 0) {
            addLocalTradesKey(keys, "hash_" + accountHash);
        }
        return keys;
    }

    private List<String> buildLegacyLocalTradesKeys(String primaryKey, long accountHash) {
        List<String> keys = new ArrayList<>();
        addLocalTradesKey(keys, primaryKey);
        if (accountHash > 0) {
            addLocalTradesKey(keys, "hash_" + accountHash);
            addLocalTradesKey(keys, String.valueOf(accountHash));
        }
        return keys;
    }

    private void addLocalTradesKey(List<String> keys, String key) {
        if (key == null || key.trim().isEmpty()) {
            return;
        }
        String trimmed = key.trim();
        if (!keys.contains(trimmed)) {
            keys.add(trimmed);
        }
    }

    private List<LocalTradeDelta> mergeLocalTrades(List<LocalTradeDelta> primary, List<LocalTradeDelta> secondary,
        List<LocalTradeDelta> tertiary, List<LocalTradeDelta> quaternary) {
        List<LocalTradeDelta> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        addMergedTrades(merged, seen, primary);
        addMergedTrades(merged, seen, secondary);
        addMergedTrades(merged, seen, tertiary);
        addMergedTrades(merged, seen, quaternary);
        if (merged.isEmpty()) {
            return null;
        }
        merged.sort(Comparator.comparingLong(delta -> delta != null ? delta.tsClientMs : 0L));
        if (merged.size() > MAX_LOCAL_TRADES) {
            int trim = merged.size() - MAX_LOCAL_TRADES;
            merged.subList(0, trim).clear();
        }
        return merged;
    }

    private void addMergedTrades(List<LocalTradeDelta> merged, Set<String> seen, List<LocalTradeDelta> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (LocalTradeDelta delta : source) {
            if (delta == null) {
                continue;
            }
            if (seen.add(buildLocalTradeSignature(delta))) {
                merged.add(delta);
            }
        }
    }

    private List<LocalTradeDelta> readLocalTrades(List<String> keys, int index) {
        if (keys == null || index < 0 || index >= keys.size()) {
            return null;
        }
        return readLocalTrades(keys.get(index));
    }

    private String buildLocalTradeSignature(LocalTradeDelta delta) {
        return delta.tsClientMs + "|" + delta.itemId + "|" + delta.isBuy + "|" + delta.deltaQty + "|" + delta.deltaGp + "|"
            + String.valueOf(delta.eventType) + "|" + delta.price;
    }

    private String buildLimitTradeSignature(LocalTradeDelta delta) {
        long bucket = delta.tsClientMs / LOCAL_EVENT_BUCKET_MS;
        return bucket + "|" + delta.itemId + "|" + delta.isBuy + "|" + delta.deltaQty + "|" + delta.price;
    }


    private LocalStatsSnapshot buildLocalStatsSnapshot(long accountKey, Long sinceMs, StatsItemSort sort) {
        if (sinceMs == null) {
            if (accountKey == ACCOUNTWIDE_KEY) {
                return buildAccountwideStatsSnapshotFromCaches(sort);
            }
            return buildLocalStatsSnapshotFromCache(accountKey, sort);
        }
        if (accountKey == ACCOUNTWIDE_KEY) {
            return buildAccountwideStatsSnapshot(sinceMs, sort);
        }
        return buildLocalStatsSnapshotForAccount(accountKey, sinceMs, sort);
    }

    private LocalStatsSnapshot buildLocalStatsSnapshotFromCache(long accountKey, StatsItemSort sort) {
        ensureProfileLoaded(accountKey);
        LocalStatsCache cache = getOrBuildStatsCache(accountKey);
        if (cache == null) {
            return new LocalStatsSnapshot(new StatsSummary(), new ArrayList<>());
        }
        List<StatsItem> items = cache.getItems();
        hydrateLocalItemNames(items);
        items.sort(buildLocalStatsComparator(sort));
        return new LocalStatsSnapshot(cache.getSummary(), items);
    }

    private LocalStatsSnapshot buildLocalStatsSnapshotForAccount(long accountKey, Long sinceMs, StatsItemSort sort) {
        ensureProfileLoaded(accountKey);
        LocalStatsCache cache = getOrBuildStatsCache(accountKey);
        if (cache == null) {
            return new LocalStatsSnapshot(new StatsSummary(), new ArrayList<>());
        }
        LocalStatsSnapshot snapshot = cache.buildSnapshotSince(sinceMs);
        List<StatsItem> items = snapshot.items != null ? snapshot.items : new ArrayList<>();
        hydrateLocalItemNames(items);
        items.sort(buildLocalStatsComparator(sort));
        return new LocalStatsSnapshot(snapshot.summary, items);
    }

    private void hydrateLocalItemNames(List<StatsItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (StatsItem item : items) {
            if (item == null || item.item_id <= 0) {
                continue;
            }
            String cachedName = getCachedItemName(item.item_id);
            item.item_name = cachedName;
            if (cachedName == null || cachedName.trim().isEmpty()) {
                cacheItemName(item.item_id);
            }
        }
    }

    private LocalStatsSnapshot buildAccountwideStatsSnapshot(Long sinceMs, StatsItemSort sort) {
        List<LocalTradeDelta> merged = buildAccountwideDeltas();
        return buildAccountwideSnapshotFromDeltas(merged, sinceMs, sort);
    }

    private LocalStatsSnapshot buildAccountwideStatsSnapshotFromCaches(StatsItemSort sort) {
        List<LocalTradeDelta> merged = buildAccountwideDeltas();
        return buildAccountwideSnapshotFromDeltas(merged, null, sort);
    }

    private LocalStatsSnapshot buildAccountwideSnapshotFromDeltas(List<LocalTradeDelta> deltas, Long sinceMs,
        StatsItemSort sort) {
        if (deltas == null || deltas.isEmpty()) {
            return new LocalStatsSnapshot(new StatsSummary(), new ArrayList<>());
        }
        LocalStatsCache cache = new LocalStatsCache();
        cache.rebuild(deltas);
        LocalStatsSnapshot snapshot = cache.buildSnapshotSince(sinceMs);
        if (snapshot == null) {
            return new LocalStatsSnapshot(new StatsSummary(), new ArrayList<>());
        }
        List<StatsItem> items = snapshot.items != null ? snapshot.items : new ArrayList<>();
        hydrateLocalItemNames(items);
        items.sort(buildLocalStatsComparator(sort));
        return new LocalStatsSnapshot(snapshot.summary, items);
    }

    private List<LocalTradeDelta> buildAccountwideDeltas() {
        Map<Long, String> profiles = loadProfilesFromDisk();
        if (profiles == null || profiles.isEmpty()) {
            return new ArrayList<>();
        }
        List<LocalTradeDelta> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Long key : profiles.keySet()) {
            if (key == null || key <= 0) {
                continue;
            }
            ensureProfileLoaded(key);
            List<LocalTradeDelta> deltas = null;
            synchronized (localStatsLock) {
                List<LocalTradeDelta> snapshot = localTradeDeltasByAccount.get(key);
                if (snapshot != null && !snapshot.isEmpty()) {
                    deltas = new ArrayList<>(snapshot);
                }
            }
            if (deltas == null) {
                ProfileData data = readProfileData(key);
                deltas = data != null ? data.deltas : null;
            }
            if (deltas == null || deltas.isEmpty()) {
                continue;
            }
            for (LocalTradeDelta delta : deltas) {
                if (delta == null) {
                    continue;
                }
                if (seen.add(buildLocalTradeSignature(delta))) {
                    merged.add(delta);
                }
            }
        }
        if (merged.isEmpty()) {
            return merged;
        }
        merged.sort(Comparator.comparingLong(delta -> delta != null ? delta.tsClientMs : 0L));
        if (merged.size() > MAX_LOCAL_TRADES) {
            int trim = merged.size() - MAX_LOCAL_TRADES;
            merged.subList(0, trim).clear();
        }
        return merged;
    }

    private StatsSummary finalizeAccountwideSnapshot(List<StatsItem> items,
                                                     StatsItemSort sort,
                                                     long totalProfit,
                                                     long totalCost,
                                                     long totalQty,
                                                     long totalTax,
                                                     long totalActiveMs,
                                                     int totalCompleted,
                                                     Long firstBuyTs,
                                                     Long lastSellTs) {
        if (items != null) {
            for (StatsItem item : items) {
                if (item == null) {
                    continue;
                }
                long cost = item.total_cost_gp != null ? item.total_cost_gp : 0L;
                long profit = item.total_profit_gp != null ? item.total_profit_gp : 0L;
                item.roi_percent = cost > 0 ? (profit * 100.0) / cost : 0.0;
            }
            hydrateLocalItemNames(items);
            items.sort(buildLocalStatsComparator(sort));
        }
        StatsSummary summary = new StatsSummary();
        summary.total_profit_gp = totalProfit;
        summary.total_cost_gp = totalCost;
        summary.roi_percent = totalCost > 0 ? (totalProfit * 100.0) / totalCost : 0.0;
        summary.gp_per_hour = totalActiveMs > 0 ? (totalProfit / (totalActiveMs / 3600000.0)) : 0.0;
        summary.fill_count = totalCompleted;
        summary.total_qty = totalQty;
        summary.active_ms = totalActiveMs;
        summary.tax_paid_gp = totalTax;
        summary.first_buy_ts_ms = firstBuyTs;
        summary.last_sell_ts_ms = lastSellTs;
        return summary;
    }

    private LocalStatsCache getOrBuildStatsCache(long accountKey) {
        if (accountKey <= 0) {
            return null;
        }
        LocalStatsCache cache = statsCacheByAccount.get(accountKey);
        if (cache != null) {
            return cache;
        }
        List<LocalTradeDelta> snapshot;
        synchronized (localStatsLock) {
            List<LocalTradeDelta> deltas = localTradeDeltasByAccount.get(accountKey);
            snapshot = deltas != null ? new ArrayList<>(deltas) : new ArrayList<>();
        }
        LocalStatsCache created = new LocalStatsCache();
        created.rebuild(snapshot);
        statsCacheByAccount.put(accountKey, created);
        return created;
    }

    private void rebuildStatsCache(long accountKey, List<LocalTradeDelta> deltas) {
        if (accountKey <= 0) {
            return;
        }
        LocalStatsCache cache = new LocalStatsCache();
        cache.rebuild(deltas != null ? deltas : new ArrayList<>());
        statsCacheByAccount.put(accountKey, cache);
    }

    private void applyDeltaToStatsCache(long accountKey, LocalTradeDelta delta) {
        if (accountKey <= 0 || delta == null) {
            return;
        }
        LocalStatsCache cache = statsCacheByAccount.get(accountKey);
        if (cache == null) {
            cache = new LocalStatsCache();
            statsCacheByAccount.put(accountKey, cache);
        }
        if (!cache.applyDeltaInOrder(delta)) {
            List<LocalTradeDelta> snapshot;
            synchronized (localStatsLock) {
                List<LocalTradeDelta> deltas = localTradeDeltasByAccount.get(accountKey);
                snapshot = deltas != null ? new ArrayList<>(deltas) : new ArrayList<>();
            }
            rebuildStatsCache(accountKey, snapshot);
        }
    }

    private Comparator<StatsItem> buildLocalStatsComparator(StatsItemSort sort) {
        StatsItemSort effective = sort != null ? sort : StatsItemSort.COMPLETION;
        if (effective == StatsItemSort.ROI) {
            return Comparator
                .comparingDouble((StatsItem item) -> item != null && item.roi_percent != null ? item.roi_percent : 0.0)
                .reversed()
                .thenComparing(Comparator.comparingLong(
                    (StatsItem item) -> item != null && item.total_profit_gp != null ? item.total_profit_gp : 0L
                ).reversed());
        }
        if (effective == StatsItemSort.PROFIT) {
            return Comparator
                .comparingLong((StatsItem item) -> item != null && item.total_profit_gp != null ? item.total_profit_gp : 0L)
                .reversed()
                .thenComparing(Comparator.comparingLong(
                    (StatsItem item) -> item != null && item.last_sell_ts_ms != null ? item.last_sell_ts_ms : 0L
                ).reversed());
        }
        return Comparator
            .comparingLong((StatsItem item) -> item != null && item.last_sell_ts_ms != null ? item.last_sell_ts_ms : 0L)
            .reversed()
            .thenComparing(Comparator.comparingLong(
                (StatsItem item) -> item != null && item.total_profit_gp != null ? item.total_profit_gp : 0L
            ).reversed());
    }

    private long resolveLocalAccountKey() {
        if (client == null || client.getGameState() != GameState.LOGGED_IN) {
            return -1L;
        }
        long accountHash = resolveAccountHash();
        long nameKey = resolveNameAccountKey();
        if (accountHash > 0 && nameKey > 0 && accountHash != nameKey) {
            maybeMergeLocalAccounts(accountHash, nameKey);
        }
        if (accountHash > 0) {
            return accountHash;
        }
        if (nameKey > 0) {
            return nameKey;
        }
        return -1L;
    }

    private void loadProfileSelectionState() {
        if (configManager == null) {
            return;
        }
        String storedKey = configManager.getConfiguration("fliphub", PROFILE_SELECTED_KEY);
        String mode = configManager.getConfiguration("fliphub", PROFILE_SELECTION_MODE_KEY);
        if (storedKey != null && !storedKey.trim().isEmpty()) {
            selectedProfileKey = storedKey.trim();
        }
        manualProfileSelection = "manual".equalsIgnoreCase(mode);
    }

    private void persistProfileSelectionState() {
        if (configManager == null) {
            return;
        }
        configManager.setConfiguration("fliphub", PROFILE_SELECTED_KEY, selectedProfileKey);
        configManager.setConfiguration("fliphub", PROFILE_SELECTION_MODE_KEY, manualProfileSelection ? "manual" : "auto");
    }

    private void updateProfileForLogin() {
        long accountHash = resolveAccountHash();
        if (accountHash <= 0) {
            return;
        }
        String displayName = resolveDisplayName();
        if (displayName != null && !displayName.trim().isEmpty()) {
            profileDisplayNames.put(accountHash, displayName.trim());
        }
        ensureProfileLoaded(accountHash);
        if (!manualProfileSelection) {
            selectedProfileKey = buildProfileKey(accountHash);
            persistProfileSelectionState();
        }
        updateProfileOptionsUI();
        updateProfileHeader();
    }

    private void updateProfileOptionsUI() {
        if (panel == null) {
            return;
        }
        List<FlipHubPanel.ProfileOption> options = buildProfileOptions();
        panel.setProfileOptions(options, resolveSelectedProfileKeyForUi());
    }

    private void updateProfileHeader() {
        if (panel == null) {
            return;
        }
        String label = resolveProfileHeaderLabel();
        panel.setProfileHeader(label, isLinked());
    }

    private String resolveSelectedProfileKeyForUi() {
        if (client == null || client.getGameState() != GameState.LOGGED_IN) {
            return ACCOUNTWIDE_KEY_STRING;
        }
        if (selectedProfileKey == null || selectedProfileKey.trim().isEmpty()) {
            return ACCOUNTWIDE_KEY_STRING;
        }
        String normalized = selectedProfileKey.trim().toLowerCase(Locale.US);
        if (ACCOUNTWIDE_KEY_STRING.equals(normalized)) {
            return ACCOUNTWIDE_KEY_STRING;
        }
        if (normalized.startsWith("hash_")) {
            return normalized;
        }
        try {
            long parsed = Long.parseLong(normalized);
            return parsed > 0 ? "hash_" + parsed : ACCOUNTWIDE_KEY_STRING;
        } catch (Exception ignored) {
        }
        return ACCOUNTWIDE_KEY_STRING;
    }

    private String resolveProfileHeaderLabel() {
        return resolveSelectedProfileLabel();
    }

    private boolean isLinked() {
        String sessionToken = config != null ? config.sessionToken() : null;
        String signingSecret = config != null ? config.signingSecret() : null;
        return sessionToken != null && !sessionToken.isEmpty()
            && signingSecret != null && !signingSecret.isEmpty();
    }

    private long resolveSelectedProfileKey() {
        if (client == null || client.getGameState() != GameState.LOGGED_IN) {
            return ACCOUNTWIDE_KEY;
        }
        String key = selectedProfileKey;
        if (key == null || key.trim().isEmpty()) {
            return ACCOUNTWIDE_KEY;
        }
        String normalized = key.trim().toLowerCase(Locale.US);
        if (ACCOUNTWIDE_KEY_STRING.equals(normalized)) {
            return ACCOUNTWIDE_KEY;
        }
        if (normalized.startsWith("hash_")) {
            try {
                return Long.parseLong(normalized.substring(5));
            } catch (Exception ignored) {
            }
        }
        try {
            return Long.parseLong(normalized);
        } catch (Exception ignored) {
        }
        return ACCOUNTWIDE_KEY;
    }

    private String resolveSelectedProfileLabel() {
        long key = resolveSelectedProfileKey();
        if (key == ACCOUNTWIDE_KEY) {
            return "Accountwide";
        }
        String displayName = profileDisplayNames.get(key);
        if (displayName != null && !displayName.trim().isEmpty() && !isPlaceholderDisplayName(displayName)) {
            return displayName;
        }
        String legacyKey = legacyNameKeysByHash.get(key);
        String legacyDisplay = displayNameFromLegacyKey(legacyKey);
        if (legacyDisplay == null) {
            legacyDisplay = resolveLegacyDisplayNameForHash(key);
        }
        if (legacyDisplay != null) {
            profileDisplayNames.put(key, legacyDisplay);
            return legacyDisplay;
        }
        if (displayName != null && !displayName.trim().isEmpty()) {
            return displayName;
        }
        return "Profile " + key;
    }

    private String buildProfileKey(long accountHash) {
        if (accountHash <= 0) {
            return ACCOUNTWIDE_KEY_STRING;
        }
        return "hash_" + accountHash;
    }

    private String resolveDisplayName() {
        try {
            String name = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
            return name != null ? name.trim() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<FlipHubPanel.ProfileOption> buildProfileOptions() {
        List<FlipHubPanel.ProfileOption> options = new ArrayList<>();
        options.add(new FlipHubPanel.ProfileOption(ACCOUNTWIDE_KEY_STRING, "Accountwide"));
        Map<Long, String> diskProfiles = loadProfilesFromDisk();
        long currentHash = resolveAccountHash();
        if (currentHash > 0) {
            String display = resolveDisplayName();
            if (display != null && !display.trim().isEmpty()) {
                diskProfiles.put(currentHash, display.trim());
            } else if (!diskProfiles.containsKey(currentHash)) {
                diskProfiles.put(currentHash, "Profile " + currentHash);
            }
        }
        List<Map.Entry<Long, String>> entries = new ArrayList<>(diskProfiles.entrySet());
        entries.sort(Comparator.comparing(entry -> entry.getValue() != null ? entry.getValue().toLowerCase(Locale.US) : ""));
        for (Map.Entry<Long, String> entry : entries) {
            long hash = entry.getKey();
            if (hash <= 0) {
                continue;
            }
            String label = entry.getValue();
            if (label == null || label.trim().isEmpty()) {
                label = "Profile " + hash;
            }
            if (label.startsWith("Profile ")) {
                String legacyKey = legacyNameKeysByHash.get(hash);
                String legacyDisplay = displayNameFromLegacyKey(legacyKey);
                if (legacyDisplay == null) {
                    legacyDisplay = resolveLegacyDisplayNameForHash(hash);
                }
                if (legacyDisplay != null) {
                    label = legacyDisplay;
                }
            }
            if (label != null && !label.trim().isEmpty() && !isPlaceholderDisplayName(label)) {
                profileDisplayNames.put(hash, label.trim());
            }
            options.add(new FlipHubPanel.ProfileOption(buildProfileKey(hash), label));
        }
        return options;
    }

    private String resolveLegacyDisplayNameForHash(long hash) {
        if (hash <= 0) {
            return null;
        }
        Map<String, String> entries = getLegacyLocalTradesCache();
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        String raw = entries.get("hash_" + hash);
        if (raw == null || raw.trim().isEmpty()) {
            raw = entries.get(String.valueOf(hash));
        }
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            if (entry == null) {
                continue;
            }
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || value == null) {
                continue;
            }
            if (!value.equals(raw)) {
                continue;
            }
            String normalized = key.trim().toLowerCase(Locale.US);
            if (normalized.startsWith("name_")) {
                String display = normalized.substring(5).trim();
                return display.isEmpty() ? null : display;
            }
        }
        return null;
    }

    private String displayNameFromLegacyKey(String legacyKey) {
        if (legacyKey == null) {
            return null;
        }
        String trimmed = legacyKey.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.startsWith("name_")) {
            String display = trimmed.substring(5).trim();
            return display.isEmpty() ? null : display;
        }
        return null;
    }

    private Map<Long, String> loadProfilesFromDisk() {
        Map<Long, String> profiles = new HashMap<>();
        profiles.putAll(profileDisplayNames);
        mergeProfilesFromDir(profiles, getProfilesDir());
        mergeProfilesFromDir(profiles, getLegacyProfilesDir());
        mergeProfilesFromLegacyConfig(profiles);
        profileDisplayNames.putAll(profiles);
        return profiles;
    }

    private void mergeProfilesFromLegacyConfig(Map<Long, String> profiles) {
        Map<String, String> entries = getLegacyLocalTradesCache();
        if (entries == null || entries.isEmpty()) {
            return;
        }
        Map<String, String> nameValueToDisplay = new HashMap<>();
        Map<String, String> nameValueToKey = new HashMap<>();
        for (String key : entries.keySet()) {
            if (key == null || key.trim().isEmpty()) {
                continue;
            }
            String normalized = key.trim().toLowerCase(Locale.US);
            if (normalized.startsWith("name_")) {
                String display = normalized.substring(5).trim();
                if (display.isEmpty()) {
                    continue;
                }
                String raw = entries.get(key);
                if (raw != null && !raw.trim().isEmpty()) {
                    nameValueToDisplay.putIfAbsent(raw, display);
                    nameValueToKey.putIfAbsent(raw, normalized);
                }
            } else if (normalized.startsWith("hash_")) {
                try {
                    long hash = Long.parseLong(normalized.substring(5));
                    if (hash > 0) {
                        String raw = entries.get(key);
                        String display = raw != null ? nameValueToDisplay.get(raw) : null;
                        if (display != null && !display.trim().isEmpty()) {
                            String existing = profiles.get(hash);
                            if (existing == null || existing.startsWith("Profile ")) {
                                profiles.put(hash, display);
                            }
                            String nameKey = nameValueToKey.get(raw);
                            if (nameKey != null) {
                                legacyNameKeysByHash.putIfAbsent(hash, nameKey);
                            }
                        } else {
                            if (!profiles.containsKey(hash)) {
                                profiles.put(hash, "Profile " + hash);
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            } else {
                try {
                    long hash = Long.parseLong(normalized);
                    if (hash > 0) {
                        String raw = entries.get(key);
                        String display = raw != null ? nameValueToDisplay.get(raw) : null;
                        if (display != null && !display.trim().isEmpty()) {
                            String existing = profiles.get(hash);
                            if (existing == null || existing.startsWith("Profile ")) {
                                profiles.put(hash, display);
                            }
                            String nameKey = nameValueToKey.get(raw);
                            if (nameKey != null) {
                                legacyNameKeysByHash.putIfAbsent(hash, nameKey);
                            }
                        } else {
                            if (!profiles.containsKey(hash)) {
                                profiles.put(hash, "Profile " + hash);
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void mergeProfilesFromDir(Map<Long, String> profiles, Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "hash_*.json")) {
            for (Path path : stream) {
                String fileName = path.getFileName().toString();
                if (!fileName.startsWith("hash_")) {
                    continue;
                }
                String hashPart = fileName.substring(5, fileName.length() - ".json".length());
                long hash;
                try {
                    hash = Long.parseLong(hashPart);
                } catch (Exception ex) {
                    continue;
                }
                ProfileData data = readProfileData(path);
                if (data != null && data.displayName != null && !data.displayName.trim().isEmpty()) {
                    profiles.put(hash, data.displayName.trim());
                } else if (!profiles.containsKey(hash)) {
                    profiles.put(hash, "Profile " + hash);
                }
            }
        } catch (IOException ignored) {
        }
    }

    private Map<String, String> getLegacyLocalTradesCache() {
        Map<String, String> cache = legacyLocalTradesCache;
        if (cache != null) {
            return cache;
        }
        synchronized (legacyLocalTradesLock) {
            if (legacyLocalTradesCache != null) {
                return legacyLocalTradesCache;
            }
            Map<String, String> next = new HashMap<>();
            loadLegacyLocalTradesFromProfiles(next);
            legacyLocalTradesCache = next;
            return next;
        }
    }

    private void loadLegacyLocalTradesFromProfiles(Map<String, String> target) {
        if (target == null) {
            return;
        }
        String home = System.getProperty("user.home");
        if (home == null || home.trim().isEmpty()) {
            return;
        }
        Path profilesDir = Paths.get(home, ".runelite", "profiles2");
        if (Files.exists(profilesDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(profilesDir, "*.properties")) {
                for (Path path : stream) {
                    loadLegacyLocalTradesFromFile(target, path);
                }
            } catch (IOException ignored) {
            }
        }
        Path settingsFile = Paths.get(home, ".runelite", "settings.properties");
        loadLegacyLocalTradesFromFile(target, settingsFile);
    }

    private void loadLegacyLocalTradesFromFile(Map<String, String> target, Path path) {
        if (target == null || path == null || !Files.exists(path)) {
            return;
        }
        java.util.Properties props = new java.util.Properties();
        try (java.io.InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (Exception ignored) {
            return;
        }
        for (Map.Entry<Object, Object> entry : props.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String key = String.valueOf(entry.getKey());
            if (!key.startsWith(LEGACY_CONFIG_GROUP + ".localTrades.")) {
                continue;
            }
            String suffix = key.substring((LEGACY_CONFIG_GROUP + ".localTrades.").length());
            String value = String.valueOf(entry.getValue());
            if (!suffix.isEmpty() && value != null && !value.trim().isEmpty()) {
                target.putIfAbsent(suffix, value);
            }
        }
    }

    private long deriveNameHash(String name) {
        if (name == null) {
            return 1L;
        }
        String trimmed = name.trim().toLowerCase(Locale.US);
        if (trimmed.isEmpty()) {
            return 1L;
        }
        long fallback = trimmed.hashCode();
        return fallback != 0 ? Math.abs(fallback) : 1L;
    }

    private void maybeMergeLocalAccounts(long accountHash, long nameKey) {
        if (accountHash <= 0 || nameKey <= 0 || accountHash == nameKey) {
            return;
        }
        if (accountHash == lastMergedAccountHash && nameKey == lastMergedNameKey) {
            return;
        }
        mergeLocalAccountData(accountHash, nameKey);
        lastMergedAccountHash = accountHash;
        lastMergedNameKey = nameKey;
    }

    private void mergeLocalAccountData(long targetKey, long sourceKey) {
        List<LocalTradeDelta> mergedSnapshot = null;
        synchronized (localStatsLock) {
            List<LocalTradeDelta> target = localTradeDeltasByAccount.get(targetKey);
            List<LocalTradeDelta> source = localTradeDeltasByAccount.remove(sourceKey);
            if (source != null && !source.isEmpty()) {
                if (target == null || target.isEmpty()) {
                    localTradeDeltasByAccount.put(targetKey, new ArrayList<>(source));
                } else {
                    Set<String> seen = new HashSet<>();
                    for (LocalTradeDelta delta : target) {
                        if (delta != null) {
                            seen.add(buildLocalTradeSignature(delta));
                        }
                    }
                    for (LocalTradeDelta delta : source) {
                        if (delta != null && seen.add(buildLocalTradeSignature(delta))) {
                            target.add(delta);
                        }
                    }
                    target.sort(Comparator.comparingLong(delta -> delta != null ? delta.tsClientMs : 0L));
                    if (target.size() > MAX_LOCAL_TRADES) {
                        int trim = target.size() - MAX_LOCAL_TRADES;
                        target.subList(0, trim).clear();
                    }
                }
            }
            List<LocalTradeDelta> updated = localTradeDeltasByAccount.get(targetKey);
            if (updated != null) {
                mergedSnapshot = new ArrayList<>(updated);
            }

            Long targetStart = localSessionStartByAccount.get(targetKey);
            Long sourceStart = localSessionStartByAccount.remove(sourceKey);
            if (sourceStart != null) {
                if (targetStart == null || targetStart <= 0) {
                    localSessionStartByAccount.put(targetKey, sourceStart);
                }
            }
        }
        if (mergedSnapshot != null) {
            rebuildStatsCache(targetKey, mergedSnapshot);
        }
        statsCacheByAccount.remove(sourceKey);
    }

    private long resolveNameAccountKey() {
        try {
            String name = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
            if (name != null) {
                String trimmed = name.trim();
                if (!trimmed.isEmpty()) {
                    long fallback = trimmed.toLowerCase(Locale.US).hashCode();
                    return fallback != 0 ? Math.abs(fallback) : 1L;
                }
            }
        } catch (Exception ignored) {
        }
        return -1L;
    }

    private String resolveLocalAccountKeyString() {
        String hashKey = resolveAccountHashKey();
        if (hashKey != null) {
            return hashKey;
        }
        return resolveLocalAccountNameKey();
    }

    private long resolveAccountHash() {
        if (client == null || client.getGameState() != GameState.LOGGED_IN) {
            return -1L;
        }
        try {
            long hash = client.getAccountHash();
            return hash > 0 ? hash : -1L;
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private String resolveAccountHashKey() {
        long hash = resolveAccountHash();
        return hash > 0 ? "hash_" + hash : null;
    }

    private String resolveLocalAccountNameKey() {
        try {
            String name = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
            if (name != null) {
                String trimmed = name.trim();
                if (!trimmed.isEmpty()) {
                    return "name_" + trimmed.toLowerCase(Locale.US);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void updateLocalAccountSessionStart() {
        long nowMs = System.currentTimeMillis();
        long accountKey = resolveAccountHash();
        if (accountKey <= 0) {
            return;
        }
        synchronized (localStatsLock) {
            localSessionStartByAccount.put(accountKey, nowMs);
        }
    }

    private void ensureLocalSessionStart(long accountKey, long nowMs) {
        synchronized (localStatsLock) {
            localSessionStartByAccount.putIfAbsent(accountKey, nowMs);
        }
    }

    private Map<Integer, LocalTradeInfo> buildLocalTradeInfo(long accountKey) {
        List<LocalTradeDelta> snapshot;
        synchronized (localStatsLock) {
            List<LocalTradeDelta> deltas = localTradeDeltasByAccount.get(accountKey);
            snapshot = deltas != null ? new ArrayList<>(deltas) : new ArrayList<>();
        }

        Map<Integer, LocalTradeInfo> infoMap = new HashMap<>();
        for (LocalTradeDelta delta : snapshot) {
            if (delta == null || delta.itemId <= 0) {
                continue;
            }
            boolean isCompletion = "OFFER_COMPLETED".equals(delta.eventType);
            if (delta.deltaQty <= 0 && !isCompletion) {
                continue;
            }
            if (delta.price <= 0) {
                continue;
            }
            LocalTradeInfo info = infoMap.computeIfAbsent(delta.itemId, LocalTradeInfo::new);
            if (delta.isBuy) {
                if (info.lastBuyTs == null || delta.tsClientMs >= info.lastBuyTs) {
                    info.lastBuyTs = delta.tsClientMs;
                    info.lastBuyPrice = delta.price;
                }
            } else {
                if (info.lastSellTs == null || delta.tsClientMs >= info.lastSellTs) {
                    info.lastSellTs = delta.tsClientMs;
                    info.lastSellPrice = delta.price;
                }
            }
        }
        return infoMap;
    }

    private Map<Integer, LocalLimitInfo> buildLocalLimitInfo(long accountKey, long nowMs) {
        List<LocalTradeDelta> snapshot;
        synchronized (localStatsLock) {
            List<LocalTradeDelta> deltas = localTradeDeltasByAccount.get(accountKey);
            snapshot = deltas != null ? new ArrayList<>(deltas) : new ArrayList<>();
        }
        long windowStart = nowMs - (4 * 60 * 60 * 1000L);
        Map<Integer, LocalLimitInfo> infoMap = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (LocalTradeDelta delta : snapshot) {
            if (delta == null || !delta.isBuy || delta.deltaQty <= 0 || delta.baselineSynthetic) {
                continue;
            }
            if (delta.tsClientMs <= 0 || delta.tsClientMs < windowStart) {
                continue;
            }
            if (delta.tsClientMs > nowMs + (5 * 60 * 1000L)) {
                continue;
            }
            String signature = buildLimitTradeSignature(delta);
            if (!seen.add(signature)) {
                continue;
            }
            LocalLimitInfo info = infoMap.computeIfAbsent(delta.itemId, LocalLimitInfo::new);
            info.buyQty += delta.deltaQty;
            if (info.firstBuyTs == null || delta.tsClientMs < info.firstBuyTs) {
                info.firstBuyTs = delta.tsClientMs;
            }
        }
        return infoMap;
    }

    private void applyLocalTradeInfo(FlipHubItem item, LocalTradeInfo info) {
        if (item == null || info == null) {
            return;
        }
        if (info.lastBuyPrice != null && info.lastBuyPrice > 0) {
            item.last_buy_price = info.lastBuyPrice;
            item.last_buy_ts_ms = info.lastBuyTs;
        }
        if (info.lastSellPrice != null && info.lastSellPrice > 0) {
            item.last_sell_price = info.lastSellPrice;
            item.last_sell_ts_ms = info.lastSellTs;
        }
    }

    private boolean hasRecentLocalBuy(long accountKey, int itemId, long nowMs) {
        if (accountKey <= 0 || itemId <= 0) {
            return false;
        }
        long windowStart = nowMs - (4 * 60 * 60 * 1000L);
        List<LocalTradeDelta> snapshot;
        synchronized (localStatsLock) {
            List<LocalTradeDelta> deltas = localTradeDeltasByAccount.get(accountKey);
            snapshot = deltas != null ? new ArrayList<>(deltas) : new ArrayList<>();
        }
        for (LocalTradeDelta delta : snapshot) {
            if (delta == null || !delta.isBuy || delta.deltaQty <= 0) {
                continue;
            }
            if (delta.itemId != itemId) {
                continue;
            }
            if (delta.tsClientMs <= 0 || delta.tsClientMs < windowStart) {
                continue;
            }
            if (delta.tsClientMs > nowMs + (5 * 60 * 1000L)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private void applyLocalLimitInfo(FlipHubItem item, int itemId, LocalLimitInfo info) {
        if (item == null || itemId <= 0) {
            return;
        }
        Integer geLimit = getCachedGeLimit(itemId);
        if (geLimit != null && geLimit > 0) {
            item.ge_limit_total = geLimit;
            if (info != null && info.buyQty > 0) {
                int remaining = (int) Math.max(0L, geLimit - info.buyQty);
                item.ge_limit_remaining = remaining;
                if (info.firstBuyTs != null) {
                    long resetAt = info.firstBuyTs + (4 * 60 * 60 * 1000L);
                    long remainingMs = Math.max(0L, resetAt - System.currentTimeMillis());
                    item.ge_limit_reset_ms = remainingMs;
                }
            } else {
                item.ge_limit_remaining = geLimit;
                item.ge_limit_reset_ms = 0L;
            }
        }
    }

    private void applyMarginInfo(FlipHubItem item) {
        if (item == null) {
            return;
        }
        Integer buy = null;
        Integer sell = null;
        boolean hasLastBuy = item.last_buy_price != null && item.last_buy_price > 0;
        boolean hasLastSell = item.last_sell_price != null && item.last_sell_price > 0;
        boolean hasInstaBuy = item.instabuy_price != null && item.instabuy_price > 0;
        boolean hasInstaSell = item.instasell_price != null && item.instasell_price > 0;
        if (hasInstaBuy && hasInstaSell) {
            buy = item.instabuy_price;
            sell = item.instasell_price;
        } else if (hasLastBuy && hasLastSell) {
            buy = item.last_buy_price;
            sell = item.last_sell_price;
        } else if (hasLastSell && hasInstaBuy) {
            buy = item.instabuy_price;
            sell = item.last_sell_price;
        } else if (hasLastBuy && hasInstaSell) {
            buy = item.last_buy_price;
            sell = item.instasell_price;
        }
        if (buy == null || sell == null) {
            return;
        }
        // Apply 2% GE tax to align with server/dashboard margin calculations.
        double marginWithTax = sell - buy - (0.02 * sell);
        int margin = (int) marginWithTax;
        item.margin = margin;
        item.roi_percent = buy > 0 ? (margin * 100.0) / buy : null;
        if (item.ge_limit_remaining != null) {
            item.margin_x_limit = (long) margin * (long) item.ge_limit_remaining;
        } else if (item.ge_limit_total != null) {
            item.margin_x_limit = (long) margin * (long) item.ge_limit_total;
        }
    }

    private Integer getCachedGeLimit(int itemId) {
        synchronized (geLimitLock) {
            return geLimitCache.get(itemId);
        }
    }

    private Set<Integer> collectKnownItemIds() {
        Set<Integer> itemIds = new HashSet<>();
        synchronized (localStatsLock) {
            for (List<LocalTradeDelta> deltas : localTradeDeltasByAccount.values()) {
                if (deltas == null) {
                    continue;
                }
                for (LocalTradeDelta delta : deltas) {
                    if (delta != null && delta.itemId > 0) {
                        itemIds.add(delta.itemId);
                    }
                }
            }
        }
        for (OfferUpdateStamp stamp : offerUpdateStamps.values()) {
            if (stamp != null && stamp.itemId > 0) {
                itemIds.add(stamp.itemId);
            }
        }
        if (client != null) {
            GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
            if (offers != null) {
                for (GrandExchangeOffer offer : offers) {
                    if (offer != null && offer.getItemId() > 0) {
                        itemIds.add(offer.getItemId());
                    }
                }
            }
        }
        return itemIds;
    }

    private void requestGeLimits(Set<Integer> itemIds) {
        if (clientThread == null || itemManager == null || itemIds == null || itemIds.isEmpty()) {
            return;
        }
        List<Integer> missing = new ArrayList<>();
        synchronized (geLimitLock) {
            for (int itemId : itemIds) {
                if (itemId <= 0) {
                    continue;
                }
                if (geLimitCache.containsKey(itemId) || geLimitPending.contains(itemId)) {
                    continue;
                }
                geLimitPending.add(itemId);
                missing.add(itemId);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        clientThread.invokeLater(() -> {
            boolean updated = false;
            for (int itemId : missing) {
                int limit = 0;
                try {
                    net.runelite.client.game.ItemStats stats = itemManager.getItemStats(itemId);
                    if (stats != null) {
                        limit = stats.getGeLimit();
                    }
                } catch (Exception ex) {
                    if (log.isDebugEnabled()) {
                        log.debug("Local GE limit lookup failed for {}: {}", itemId, ex.getMessage());
                    }
                }
                synchronized (geLimitLock) {
                    if (limit > 0) {
                        geLimitCache.put(itemId, limit);
                        updated = true;
                    }
                    geLimitPending.remove(itemId);
                }
            }
            if (updated) {
                scheduleRefreshSoon();
            }
        });
    }

    private void applyGuidePrices(FlipHubItem item, int itemId, boolean allowRefresh) {
        if (item == null || itemId <= 0) {
            return;
        }
        WikiPriceEntry entry = getWikiPriceEntry(itemId, allowRefresh);
        if (entry == null) {
            return;
        }
        if (entry.high != null && entry.high > 0) {
            item.instasell_price = entry.high;
        }
        if (entry.low != null && entry.low > 0) {
            item.instabuy_price = entry.low;
        }
        if (entry.highTime != null && entry.highTime > 0) {
            item.instasell_ts_ms = entry.highTime * 1000L;
        }
        if (entry.lowTime != null && entry.lowTime > 0) {
            item.instabuy_ts_ms = entry.lowTime * 1000L;
        }
    }

    private WikiPriceEntry getWikiPriceEntry(int itemId, boolean allowRefresh) {
        long now = System.currentTimeMillis();
        if (allowRefresh && (wikiLatestFetchedMs <= 0 || now - wikiLatestFetchedMs > WIKI_CACHE_TTL_MS)) {
            requestWikiLatestFetch(true);
        }
        synchronized (wikiPriceLock) {
            return wikiLatestCache.get(itemId);
        }
    }

    private void refreshWikiLatestPrices() {
        requestWikiLatestFetch(false);
    }

    private void startWikiFetcher() {
        if (scheduler == null) {
            return;
        }
        if (wikiFetchTask != null && !wikiFetchTask.isCancelled()) {
            return;
        }
        wikiFetchTask = scheduler.scheduleAtFixedRate(this::tickWikiFetch, 5, 1, TimeUnit.SECONDS);
    }

    private void stopWikiFetcher() {
        if (wikiFetchTask != null) {
            wikiFetchTask.cancel(true);
            wikiFetchTask = null;
        }
        wikiFetchInFlight.set(false);
    }

    private void tickWikiFetch() {
        requestWikiLatestFetch(false);
    }

    private void requestWikiLatestFetch(boolean allowWhenHidden) {
        if (!shouldFetchWikiLatest(allowWhenHidden)) {
            return;
        }
        if (!wikiFetchInFlight.compareAndSet(false, true)) {
            return;
        }
        long attemptMs = System.currentTimeMillis();
        wikiLastAttemptMs.set(attemptMs);
        if (httpClient == null || gson == null) {
            wikiFetchInFlight.set(false);
            return;
        }
        Request request = new Request.Builder()
            .url(WIKI_LATEST_URL)
            .get()
            .addHeader("User-Agent", WIKI_USER_AGENT)
            .addHeader("Accept", "application/json")
            .build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                wikiFetchInFlight.set(false);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful()) {
                        return;
                    }
                    String body = responseBody != null ? responseBody.string() : null;
                    if (body == null || body.isEmpty()) {
                        return;
                    }
                    WikiLatestResponse latest = gson.fromJson(body, WikiLatestResponse.class);
                    if (latest == null || latest.data == null) {
                        return;
                    }
                    Map<Integer, WikiPriceEntry> next = new HashMap<>();
                    for (Map.Entry<String, WikiPriceEntry> entry : latest.data.entrySet()) {
                        if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                            continue;
                        }
                        try {
                            int itemId = Integer.parseInt(entry.getKey());
                            if (itemId > 0) {
                                next.put(itemId, entry.getValue());
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    long completedMs = System.currentTimeMillis();
                    synchronized (wikiPriceLock) {
                        wikiLatestCache.clear();
                        wikiLatestCache.putAll(next);
                        wikiLatestFetchedMs = completedMs;
                    }
                } catch (Exception ex) {
                    if (log.isDebugEnabled()) {
                        log.debug("Wiki price refresh failed: {}", ex.getMessage());
                    }
                } finally {
                    wikiFetchInFlight.set(false);
                }
            }
        });
    }

    private boolean shouldFetchWikiLatest(boolean allowWhenHidden) {
        long now = System.currentTimeMillis();
        if (!allowWhenHidden && !panelVisible) {
            return false;
        }
        if (wikiFetchInFlight.get()) {
            return false;
        }
        if (wikiLatestFetchedMs > 0 && now - wikiLatestFetchedMs <= WIKI_CACHE_TTL_MS) {
            return false;
        }
        long lastAttempt = wikiLastAttemptMs.get();
        if (lastAttempt > 0 && now - lastAttempt < WIKI_MIN_REFRESH_MS) {
            return false;
        }
        return true;
    }

    private void primeOfferSnapshots() {
        if (client == null) {
            return;
        }
        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        if (offers == null || offers.length == 0) {
            return;
        }
        for (int slot = 0; slot < offers.length; slot++) {
            GrandExchangeOffer offer = offers[slot];
            if (offer == null) {
                continue;
            }
            OfferSnapshot snapshot = OfferSnapshot.fromOffer(slot, offer, null);
            if (snapshot != null) {
                snapshots.put(slot, snapshot);
            }
        }
    }

    private static final class ProfileData {
        private long accountHash;
        private String displayName;
        private List<LocalTradeDelta> deltas;
        private long updatedMs;
    }

    private static final class LocalTradeDelta {
        private long tsClientMs;
        private int itemId;
        private boolean isBuy;
        private int deltaQty;
        private long deltaGp;
        private String eventType;
        private int price;
        private boolean baselineSynthetic;

        private LocalTradeDelta() {
        }

        private LocalTradeDelta(long tsClientMs, int itemId, boolean isBuy, int deltaQty, long deltaGp, String eventType,
                                int price, boolean baselineSynthetic) {
            this.tsClientMs = tsClientMs;
            this.itemId = itemId;
            this.isBuy = isBuy;
            this.deltaQty = deltaQty;
            this.deltaGp = deltaGp;
            this.eventType = eventType;
            this.price = price;
            this.baselineSynthetic = baselineSynthetic;
        }
    }

    private static final class LocalTradeInfo {
        private final int itemId;
        private Integer lastBuyPrice;
        private Integer lastSellPrice;
        private Long lastBuyTs;
        private Long lastSellTs;

        private LocalTradeInfo(int itemId) {
            this.itemId = itemId;
        }
    }

    private static final class LocalLimitInfo {
        private final int itemId;
        private long buyQty;
        private Long firstBuyTs;

        private LocalLimitInfo(int itemId) {
            this.itemId = itemId;
        }
    }

    private static final class WikiLatestResponse {
        private Map<String, WikiPriceEntry> data;
    }

    private static final class WikiPriceEntry {
        private Integer high;
        private Integer low;
        private Long highTime;
        private Long lowTime;
    }

    private static final class LocalInventoryState {
        private final int itemId;
        private long qty;
        private long cost;
        private Long firstBuyTs;

        private LocalInventoryState(int itemId) {
            this.itemId = itemId;
        }
    }

    private static final class LocalItemAgg {
        private final int itemId;
        private long buyCost;
        private long sellRevenue;
        private long buyQty;
        private long sellQty;
        private long taxPaid;
        private long activeMs;
        private int completedSells;
        private Long firstBuyTs;
        private Long lastSellTs;

        private LocalItemAgg(int itemId) {
            this.itemId = itemId;
        }
    }

        private static final class LocalStatsCache {
            private final Map<Integer, LocalItemAgg> itemAggs = new HashMap<>();
            private final Map<Integer, LocalInventoryState> inventory = new HashMap<>();
            private final List<LocalTradeDelta> sortedDeltas = new ArrayList<>();
            private long lastTs = Long.MIN_VALUE;
            private long totalProfit;
            private long totalCost;
            private long totalQty;
        private long totalTax;
        private long totalActiveMs;
        private int totalCompleted;
        private Long firstBuyTs;
        private Long lastSellTs;

        private void rebuild(List<LocalTradeDelta> deltas) {
            itemAggs.clear();
            inventory.clear();
            sortedDeltas.clear();
            lastTs = Long.MIN_VALUE;
            totalProfit = 0L;
            totalCost = 0L;
            totalQty = 0L;
            totalTax = 0L;
            totalActiveMs = 0L;
            totalCompleted = 0;
            firstBuyTs = null;
            lastSellTs = null;
            if (deltas == null || deltas.isEmpty()) {
                return;
            }
            List<LocalTradeDelta> snapshot = new ArrayList<>(deltas);
            snapshot.sort(Comparator
                .comparingLong((LocalTradeDelta delta) -> delta != null ? delta.tsClientMs / LOCAL_EVENT_BUCKET_MS : 0L)
                .thenComparingInt(delta -> delta != null && delta.isBuy ? 0 : 1)
                .thenComparingLong(delta -> delta != null ? delta.tsClientMs : 0L));
            sortedDeltas.addAll(snapshot);
            LocalTradeDelta last = snapshot.get(snapshot.size() - 1);
            if (last != null) {
                lastTs = last.tsClientMs;
            }
            for (LocalTradeDelta delta : snapshot) {
                applyDelta(delta);
            }
        }

        private boolean applyDeltaInOrder(LocalTradeDelta delta) {
            if (delta == null) {
                return false;
            }
            if (lastTs != Long.MIN_VALUE && delta.tsClientMs < lastTs) {
                return false;
            }
            sortedDeltas.add(delta);
            lastTs = Math.max(lastTs, delta.tsClientMs);
            applyDelta(delta);
            return true;
        }

        private LocalStatsSnapshot buildSnapshotSince(Long sinceMs) {
            if (sinceMs == null) {
                return new LocalStatsSnapshot(getSummary(), getItems());
            }
            LocalStatsCache window = new LocalStatsCache();
            for (LocalTradeDelta delta : sortedDeltas) {
                if (delta == null) {
                    continue;
                }
                if (delta.tsClientMs < sinceMs) {
                    continue;
                }
                window.applyDelta(delta);
            }
            return new LocalStatsSnapshot(window.getSummary(), window.getItems());
        }

        private void applyDelta(LocalTradeDelta delta) {
            if (delta == null) {
                return;
            }
            boolean isCompletion = "OFFER_COMPLETED".equals(delta.eventType);
            if (delta.deltaQty <= 0 && !isCompletion) {
                return;
            }
            LocalInventoryState state = inventory.computeIfAbsent(delta.itemId, LocalInventoryState::new);
            if (delta.isBuy) {
                if (delta.deltaQty <= 0) {
                    return;
                }
                state.qty += delta.deltaQty;
                state.cost += Math.max(0L, delta.deltaGp);
                if (state.firstBuyTs == null || delta.tsClientMs < state.firstBuyTs) {
                    state.firstBuyTs = delta.tsClientMs;
                }
                return;
            }

            if (state.qty <= 0 && !isCompletion) {
                return;
            }

            long matchQty = 0L;
            long matchCost = 0L;
            long matchRevenue = 0L;
            Long matchedBuyTs = state.firstBuyTs;
            if (delta.deltaQty > 0 && state.qty > 0) {
                matchQty = Math.min((long) delta.deltaQty, state.qty);
                if (matchQty > 0) {
                    matchRevenue = delta.deltaGp;
                    if (matchQty < delta.deltaQty) {
                        matchRevenue = (delta.deltaGp * matchQty) / delta.deltaQty;
                    }
                    if (matchQty >= state.qty) {
                        matchCost = state.cost;
                        state.qty = 0L;
                        state.cost = 0L;
                        state.firstBuyTs = null;
                    } else {
                        matchCost = (state.cost * matchQty) / state.qty;
                        state.qty -= matchQty;
                        state.cost = Math.max(0L, state.cost - matchCost);
                    }
                }
            }

            LocalItemAgg agg = itemAggs.computeIfAbsent(delta.itemId, LocalItemAgg::new);
            if (matchQty > 0) {
                agg.buyCost += matchCost;
                agg.buyQty += matchQty;
                agg.sellRevenue += Math.max(0L, matchRevenue);
                agg.sellQty += matchQty;
                long tax = ((long) delta.price * matchQty) / 50L;
                agg.taxPaid += Math.max(0L, tax);
                if (matchedBuyTs == null) {
                    matchedBuyTs = delta.tsClientMs;
                }
                if (agg.firstBuyTs == null || matchedBuyTs < agg.firstBuyTs) {
                    agg.firstBuyTs = matchedBuyTs;
                }
                long duration = delta.tsClientMs - matchedBuyTs;
                if (duration > 0) {
                    agg.activeMs += duration;
                }
                totalProfit += (Math.max(0L, matchRevenue) - matchCost);
                totalCost += matchCost;
                totalQty += matchQty;
                totalTax += Math.max(0L, tax);
                totalActiveMs += Math.max(0L, duration);
            }
            if (agg.lastSellTs == null || delta.tsClientMs > agg.lastSellTs) {
                agg.lastSellTs = delta.tsClientMs;
            }
            if (isCompletion && !delta.isBuy) {
                agg.completedSells += 1;
                totalCompleted += 1;
            }
            if (agg.firstBuyTs != null && (firstBuyTs == null || agg.firstBuyTs < firstBuyTs)) {
                firstBuyTs = agg.firstBuyTs;
            }
            if (agg.lastSellTs != null && (lastSellTs == null || agg.lastSellTs > lastSellTs)) {
                lastSellTs = agg.lastSellTs;
            }
        }

        private StatsSummary getSummary() {
            StatsSummary summary = new StatsSummary();
            summary.total_profit_gp = totalProfit;
            summary.total_cost_gp = totalCost;
            summary.roi_percent = totalCost > 0 ? (totalProfit * 100.0) / totalCost : 0.0;
            summary.gp_per_hour = totalActiveMs > 0 ? (totalProfit / (totalActiveMs / 3600000.0)) : 0.0;
            summary.fill_count = totalCompleted;
            summary.total_qty = totalQty;
            summary.active_ms = totalActiveMs;
            summary.tax_paid_gp = totalTax;
            summary.first_buy_ts_ms = firstBuyTs;
            summary.last_sell_ts_ms = lastSellTs;
            return summary;
        }

        private List<StatsItem> getItems() {
            List<StatsItem> items = new ArrayList<>();
            for (LocalItemAgg agg : itemAggs.values()) {
                if (agg.buyQty <= 0 || agg.sellQty <= 0) {
                    continue;
                }
                long profit = agg.sellRevenue - agg.buyCost;
                long cost = agg.buyCost;
                long qty = agg.sellQty;
                double roi = cost > 0 ? (profit * 100.0) / cost : 0.0;

                StatsItem item = new StatsItem();
                item.item_id = agg.itemId;
                item.total_profit_gp = profit;
                item.total_cost_gp = cost;
                item.roi_percent = roi;
                item.total_qty = (int) Math.min(Integer.MAX_VALUE, Math.max(0, qty));
                item.fill_count = agg.completedSells;
                item.last_sell_ts_ms = agg.lastSellTs;
                items.add(item);
            }
            return items;
        }

    }

    private static final class LocalStatsSnapshot {
        private final StatsSummary summary;
        private final List<StatsItem> items;

        private LocalStatsSnapshot(StatsSummary summary, List<StatsItem> items) {
            this.summary = summary;
            this.items = items;
        }
    }
}

