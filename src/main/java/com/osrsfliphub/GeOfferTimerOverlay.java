package com.osrsfliphub;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Point;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.InterfaceID;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.util.Text;

public class GeOfferTimerOverlay extends Overlay {
    private static final int MIN_SLOT_WIDTH = 80;
    private static final int MAX_SLOT_WIDTH = 200;
    private static final int MIN_SLOT_HEIGHT = 50;
    private static final int MAX_SLOT_HEIGHT = 120;
    private static final int MAX_ICON_SIZE = 40;
    private static final int TEXT_PADDING_X = 4;
    private static final int TEXT_PADDING_Y = 2;
    private static final int SLOT_SCAN_COMPONENT_LIMIT = 300;
    private static final String[] OFFER_STATUS_MARKERS = new String[] {
        "offer status",
        "you have bought",
        "you have sold",
        "bought a total",
        "sold a total"
    };

    private static final long GREEN_THRESHOLD_MS = 5 * 60 * 1000L;
    private static final long YELLOW_THRESHOLD_MS = 30 * 60 * 1000L;

    private static final Color GREEN = new Color(16, 185, 129);
    private static final Color YELLOW = new Color(251, 191, 36);
    private static final Color RED = new Color(239, 68, 68);

    private final Client client;
    private final PluginConfig config;
    private final GeLifecyclePlugin plugin;

    GeOfferTimerOverlay(Client client, PluginConfig config, GeLifecyclePlugin plugin) {
        this.client = client;
        this.config = config;
        this.plugin = plugin;
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPosition(OverlayPosition.DYNAMIC);
        setPriority(OverlayPriority.HIGH);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (config == null || !config.showGeOfferTimers()) {
            return null;
        }
        if (client == null) {
            return null;
        }
        Widget geRoot = client.getWidget(ComponentID.GRAND_EXCHANGE_WINDOW_CONTAINER);
        if (geRoot == null || geRoot.isHidden()) {
            return null;
        }
        Widget offerContainer = client.getWidget(ComponentID.GRAND_EXCHANGE_OFFER_CONTAINER);
        if (offerContainer != null && !offerContainer.isHidden()) {
            return null;
        }
        Widget offerText = client.getWidget(ComponentID.GRAND_EXCHANGE_OFFER_TEXT);
        if (offerText != null && !offerText.isHidden()) {
            return null;
        }
        if (isOfferStatusWindowOpen()) {
            return null;
        }
        if (plugin != null && plugin.isOfferStatusOpen()) {
            return null;
        }
        List<Rectangle> slotBounds = findSlotBounds(geRoot);
        if (slotBounds.isEmpty()) {
            return null;
        }
        slotBounds.sort(Comparator.comparingInt((Rectangle bounds) -> bounds.y)
            .thenComparingInt(bounds -> bounds.x));
        if (!looksLikeMainGrid(slotBounds)) {
            return null;
        }

        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        if (offers == null || offers.length == 0) {
            return null;
        }

        graphics.setFont(FontManager.getRunescapeSmallFont());

        for (int slot = 0; slot < offers.length && slot < slotBounds.size(); slot++) {
            GrandExchangeOffer offer = offers[slot];
            if (offer == null || offer.getState() == GrandExchangeOfferState.EMPTY || offer.getItemId() <= 0) {
                continue;
            }
            Rectangle slotRect = slotBounds.get(slot);
            long lastUpdateMs = plugin.getOfferLastUpdateMs(slot, offer);
            if (lastUpdateMs <= 0) {
                continue;
            }
            long ageMs = Math.max(0, System.currentTimeMillis() - lastUpdateMs);
            String text = formatElapsed(ageMs);
            Color color = getAgeColor(ageMs);
            renderTimerText(graphics, slotRect, text, color);
        }

        return null;
    }

    private boolean isOfferStatusWindowOpen() {
        if (client == null) {
            return false;
        }
        Widget[] roots = client.getWidgetRoots();
        if (roots == null || roots.length == 0) {
            return false;
        }
        for (Widget root : roots) {
            if (root == null || root.isHidden()) {
                continue;
            }
            if (widgetTreeContainsAnyText(root, OFFER_STATUS_MARKERS)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text
            .replace("<br>", "\n")
            .replace("<br/>", "\n")
            .replace("<br />", "\n");
        return Text.removeTags(normalized).trim();
    }

    private boolean widgetTreeContainsAnyText(Widget widget, String[] markers) {
        if (widget == null || markers == null || markers.length == 0) {
            return false;
        }
        String text = normalize(widget.getText());
        if (text != null && !text.isEmpty()) {
            String lower = text.toLowerCase();
            for (String marker : markers) {
                if (marker != null && !marker.isEmpty() && lower.contains(marker)) {
                    return true;
                }
            }
        }
        return widgetTreeContainsAnyText(widget.getChildren(), markers)
            || widgetTreeContainsAnyText(widget.getDynamicChildren(), markers)
            || widgetTreeContainsAnyText(widget.getNestedChildren(), markers);
    }

    private boolean widgetTreeContainsAnyText(Widget[] children, String[] markers) {
        if (children == null) {
            return false;
        }
        for (Widget child : children) {
            if (widgetTreeContainsAnyText(child, markers)) {
                return true;
            }
        }
        return false;
    }

    private void renderTimerText(Graphics2D graphics, Rectangle slotBounds, String text, Color color) {
        FontMetrics metrics = graphics.getFontMetrics();
        int textWidth = metrics.stringWidth(text);
        int x = slotBounds.x + slotBounds.width - textWidth - TEXT_PADDING_X;
        int y = slotBounds.y + metrics.getAscent() + TEXT_PADDING_Y;
        OverlayUtil.renderTextLocation(graphics, new Point(x, y), text, color);
    }

    private Color getAgeColor(long ageMs) {
        if (ageMs <= GREEN_THRESHOLD_MS) {
            return GREEN;
        }
        if (ageMs <= YELLOW_THRESHOLD_MS) {
            return YELLOW;
        }
        return RED;
    }

    private String formatElapsed(long ms) {
        long totalSeconds = Math.max(0, ms / 1000);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    private List<Rectangle> findSlotBounds(Widget root) {
        if (root == null) {
            return Collections.emptyList();
        }
        Rectangle rootBounds = toCanvasBounds(root);
        List<Rectangle> candidates = new ArrayList<>();
        collectSlotBounds(root, rootBounds, candidates);
        List<Rectangle> unique = dedupeBounds(candidates);
        List<Rectangle> filtered = filterByCommonSize(unique);

        if (filtered.size() < 8) {
            List<Rectangle> fromIcons = findSlotBoundsFromIcons(root);
            if (fromIcons.size() > filtered.size()) {
                filtered = fromIcons;
            }
        }

        if (filtered.size() < 8) {
            List<Rectangle> fromGroup = scanSlotBoundsFromGroup(rootBounds);
            if (fromGroup.size() > filtered.size()) {
                filtered = fromGroup;
            }
        }

        if (filtered.size() > 8) {
            filtered.sort(Comparator.comparingInt((Rectangle bounds) -> bounds.y)
                .thenComparingInt(bounds -> bounds.x));
            return new ArrayList<>(filtered.subList(0, 8));
        }
        return filtered;
    }

    private boolean looksLikeMainGrid(List<Rectangle> slotBounds) {
        if (slotBounds == null || slotBounds.isEmpty()) {
            return false;
        }
        if (slotBounds.size() < 6) {
            return false;
        }
        List<Integer> widths = new ArrayList<>();
        List<Integer> heights = new ArrayList<>();
        for (Rectangle rect : slotBounds) {
            widths.add(rect.width);
            heights.add(rect.height);
        }
        Collections.sort(widths);
        Collections.sort(heights);
        int medianWidth = widths.get(widths.size() / 2);
        int medianHeight = heights.get(heights.size() / 2);
        int rowTolerance = Math.max(10, medianHeight / 2);
        int colTolerance = Math.max(10, medianWidth / 2);

        List<Integer> rows = new ArrayList<>();
        List<Integer> cols = new ArrayList<>();
        for (Rectangle rect : slotBounds) {
            addCluster(rows, rect.y, rowTolerance);
            addCluster(cols, rect.x, colTolerance);
        }
        return rows.size() >= 2 && cols.size() >= 4;
    }

    private void addCluster(List<Integer> clusters, int value, int tolerance) {
        for (int i = 0; i < clusters.size(); i++) {
            if (Math.abs(clusters.get(i) - value) <= tolerance) {
                return;
            }
        }
        clusters.add(value);
    }

    private List<Rectangle> findSlotBoundsFromIcons(Widget root) {
        List<Rectangle> results = new ArrayList<>();
        collectSlotIconBounds(root, results);
        return dedupeBounds(results);
    }

    private void collectSlotIconBounds(Widget widget, List<Rectangle> out) {
        if (widget == null) {
            return;
        }
        Rectangle bounds = toCanvasBounds(widget);
        if (bounds != null && widget.getItemId() > 0 && isSlotIconBounds(bounds)) {
            Rectangle slotBounds = resolveSlotBounds(widget);
            if (slotBounds != null) {
                out.add(slotBounds);
            }
        }
        collectSlotIconBounds(widget.getChildren(), out);
        collectSlotIconBounds(widget.getDynamicChildren(), out);
        collectSlotIconBounds(widget.getNestedChildren(), out);
    }

    private void collectSlotIconBounds(Widget[] children, List<Rectangle> out) {
        if (children == null) {
            return;
        }
        for (Widget child : children) {
            collectSlotIconBounds(child, out);
        }
    }

    private void collectSlotBounds(Widget widget, Rectangle rootBounds, List<Rectangle> out) {
        if (widget == null) {
            return;
        }
        Rectangle bounds = toCanvasBounds(widget);
        if (bounds != null && isSlotContainerBounds(bounds, rootBounds)) {
            out.add(bounds);
        }
        collectSlotBounds(widget.getChildren(), rootBounds, out);
        collectSlotBounds(widget.getDynamicChildren(), rootBounds, out);
        collectSlotBounds(widget.getNestedChildren(), rootBounds, out);
    }

    private void collectSlotBounds(Widget[] children, Rectangle rootBounds, List<Rectangle> out) {
        if (children == null) {
            return;
        }
        for (Widget child : children) {
            collectSlotBounds(child, rootBounds, out);
        }
    }

    private boolean isSlotIconBounds(Rectangle bounds) {
        if (bounds == null) {
            return false;
        }
        return bounds.width > 0 && bounds.height > 0
            && bounds.width <= MAX_ICON_SIZE
            && bounds.height <= MAX_ICON_SIZE;
    }

    private List<Rectangle> scanSlotBoundsFromGroup(Rectangle rootBounds) {
        List<Rectangle> results = new ArrayList<>();
        if (client == null) {
            return results;
        }
        int groupId = InterfaceID.GRAND_EXCHANGE;
        for (int component = 0; component <= SLOT_SCAN_COMPONENT_LIMIT; component++) {
            Widget widget = client.getWidget(groupId, component);
            if (widget == null || widget.isHidden()) {
                continue;
            }
            Rectangle bounds = toCanvasBounds(widget);
            if (bounds != null && isSlotContainerBounds(bounds, rootBounds)) {
                results.add(bounds);
            }
        }
        return filterByCommonSize(dedupeBounds(results));
    }

    private boolean isSlotContainerBounds(Rectangle bounds, Rectangle rootBounds) {
        if (bounds == null) {
            return false;
        }
        if (bounds.width < MIN_SLOT_WIDTH || bounds.width > MAX_SLOT_WIDTH) {
            return false;
        }
        if (bounds.height < MIN_SLOT_HEIGHT || bounds.height > MAX_SLOT_HEIGHT) {
            return false;
        }
        if (rootBounds != null) {
            if (!rootBounds.contains(bounds)) {
                return false;
            }
            if (bounds.width > rootBounds.width * 0.75 || bounds.height > rootBounds.height * 0.75) {
                return false;
            }
        }
        return true;
    }

    private List<Rectangle> filterByCommonSize(List<Rectangle> candidates) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        List<Integer> widths = new ArrayList<>();
        List<Integer> heights = new ArrayList<>();
        for (Rectangle rect : candidates) {
            widths.add(rect.width);
            heights.add(rect.height);
        }
        Collections.sort(widths);
        Collections.sort(heights);
        int medianWidth = widths.get(widths.size() / 2);
        int medianHeight = heights.get(heights.size() / 2);
        int widthTolerance = Math.max(10, medianWidth / 4);
        int heightTolerance = Math.max(8, medianHeight / 4);

        List<Rectangle> filtered = new ArrayList<>();
        for (Rectangle rect : candidates) {
            if (Math.abs(rect.width - medianWidth) <= widthTolerance &&
                Math.abs(rect.height - medianHeight) <= heightTolerance) {
                filtered.add(rect);
            }
        }
        return filtered.isEmpty() ? candidates : filtered;
    }

    private Rectangle resolveSlotBounds(Widget widget) {
        Widget current = widget;
        while (current != null) {
            Rectangle bounds = toCanvasBounds(current);
            if (bounds != null && isSlotContainerBounds(bounds, null)) {
                return bounds;
            }
            current = current.getParent();
        }
        return null;
    }

    private List<Rectangle> dedupeBounds(List<Rectangle> candidates) {
        List<Rectangle> unique = new ArrayList<>();
        for (Rectangle rect : candidates) {
            if (!containsSimilarRect(unique, rect)) {
                unique.add(rect);
            }
        }
        return unique;
    }

    private boolean containsSimilarRect(List<Rectangle> rects, Rectangle target) {
        for (Rectangle rect : rects) {
            if (Math.abs(rect.x - target.x) <= 2 &&
                Math.abs(rect.y - target.y) <= 2 &&
                Math.abs(rect.width - target.width) <= 2 &&
                Math.abs(rect.height - target.height) <= 2) {
                return true;
            }
        }
        return false;
    }

    private Rectangle toCanvasBounds(Widget widget) {
        if (widget == null) {
            return null;
        }
        Rectangle bounds = widget.getBounds();
        Point location = widget.getCanvasLocation();
        if (bounds == null || location == null) {
            return null;
        }
        return new Rectangle(location.getX(), location.getY(), bounds.width, bounds.height);
    }

}


