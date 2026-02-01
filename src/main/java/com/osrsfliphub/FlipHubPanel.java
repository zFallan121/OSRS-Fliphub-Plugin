package com.osrsfliphub;

import java.awt.BorderLayout;
import java.awt.AWTEvent;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.awt.Insets;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.net.URI;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayList;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;
import javax.swing.ImageIcon;
import javax.swing.JScrollBar;
import javax.swing.JToolTip;
import javax.swing.Popup;
import javax.swing.PopupFactory;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;

public class FlipHubPanel extends PluginPanel {
    private static final String DEFAULT_BASE_URL = "https://www.osrsfliphub.com";
    private static final String DISCORD_INVITE_URL = "https://discord.gg/gNakvRzXNX";
    static final Color BG = new Color(16, 19, 27);
    static final Color BG_ALT = new Color(22, 25, 34);
    static final Color PANEL = new Color(27, 31, 42);
    static final Color CARD = new Color(32, 37, 51);
    static final Color CARD_ALT = new Color(30, 34, 47);
    static final Color BORDER = new Color(56, 64, 82);
    static final Color SOFT_BORDER = new Color(40, 46, 60);
    static final Color TEXT = new Color(233, 238, 247);
    static final Color MUTED = new Color(140, 148, 167);
    static final Color ACCENT = new Color(88, 174, 255);
    static final Color ACCENT_SOFT = new Color(39, 75, 120);
    static final Color SUCCESS = new Color(34, 197, 94);
    static final Color WARNING = new Color(245, 158, 11);
    static final Color DANGER = new Color(244, 63, 94);
    private static final DateTimeFormatter REFRESH_TIME_FORMATTER =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    static final int VALUE_RIGHT_PADDING = 4;
    static final int OFFER_VALUE_RIGHT_PADDING = 4;
    static final int SCROLL_UNIT_INCREMENT = 64;
    static final int SCROLL_BLOCK_INCREMENT = 256;
    private static final int CARD_ARC = 12;
    private static final int INPUT_ARC = 10;
    private static final int CHIP_ARC = 10;
    private static final String AGE_ENTRY_KEY = "fliphub.ageEntry";
    private static final int AGE_TOOLTIP_OFFSET_X = 12;
    private static final int AGE_TOOLTIP_OFFSET_Y = 18;
    private static final int AGE_TOOLTIP_MIN_WIDTH = 150;

    interface PanelListener {
        void onSearchChanged(String query);
        void onPageChanged(int page);
        void onBookmarkFilterChanged(boolean enabled);
        void onStatsRangeChanged(StatsRange range);
        void onStatsSortChanged(StatsItemSort sort);
        void onProfileSelected(String profileKey);
    }

    interface BookmarkStore {
        boolean isBookmarked(int itemId);
        void toggleBookmark(int itemId);
    }

    interface HiddenItemStore {
        boolean isHidden(int itemId);
        void hideItem(int itemId);
    }

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cardPanel = new JPanel(cardLayout);
    private final JToggleButton flippingTab = new JToggleButton("Activity");
    private final JToggleButton statsTab = new JToggleButton("Flip Profile");
    private final JTextField searchField = new JTextField();
    private final JLabel refreshLabel = new JLabel("Updated: --");
    private final JButton profileButton = new JButton("Accountwide");
    private final JLabel pageLabel = new JLabel("Page 0 of 0");
    private final JButton prevButton = new JButton("<");
    private final JButton nextButton = new JButton(">");
    private JPanel footerPanel;
    private final JToggleButton bookmarkFilterButton = new JToggleButton("\u2605");
    private final JPanel listPanel = new TrackingPanel();
    private final JScrollPane scrollPane = new JScrollPane(listPanel);
    private final JComboBox<StatsRange> statsRangeCombo = new JComboBox<>(StatsRange.values());
    private final JComboBox<StatsItemSort> statsSortCombo = new JComboBox<>(StatsItemSort.values());
    private final JTextField statsSearchField = new JTextField();
    private final JButton statsClearButton = new JButton("Clear");
    private final JLabel statsUpdatedLabel = new JLabel("Updated: --");
    private final JPanel statsContentPanel = new TrackingPanel();
    private final JPanel statsItemsListPanel = new JPanel();
    private JScrollPane statsScrollPane;
    private JLabel statsTotalProfitValue;
    private JLabel statsRoiValue;
    private JLabel statsFlipsValue;
    private JLabel statsTaxValue;
    private JLabel statsSessionTimeValue;
    private JLabel statsHourlyValue;
    private StatsSummary statsSummary;
    private List<StatsItem> statsItems = new ArrayList<>();
    private String statsSearchQuery = "";
    private final Map<Integer, ImageIcon> iconCache = new HashMap<>();
    private final ItemManager itemManager;
    private final PanelListener listener;
    private final BookmarkStore bookmarkStore;
    private final HiddenItemStore hiddenItemStore;
    private final PluginConfig config;
    private Timer searchTimer;
    private Timer countdownTimer;
    private int currentPage = 1;
    private int totalPages = 1;
    private List<FlipHubItem> lastItems;
    private long lastAsOfMs;
    private Long lastPriceCacheMs;
    private FlipHubItem offerPreviewItem;
    private long offerAsOfMs;
    private Long offerPriceCacheMs;
    private final MouseWheelListener wheelForwarder = this::forwardWheelEvent;
    private boolean showBookmarkedOnly = false;
    private AWTEventListener globalWheelListener;
    private final List<CountdownEntry> countdownEntries = new ArrayList<>();
    private final List<AgePairEntry> ageEntries = new ArrayList<>();
    private JComponent hoveredAgeComponent;
    private AgePairEntry hoveredAgeEntry;
    private int hoveredAgeX;
    private int hoveredAgeY;
    private Popup ageTooltipPopup;
    private JToolTip ageTooltip;
    private JPopupMenu profileMenu;
    private String selectedProfileKey;

    static final class ProfileOption {
        final String key;
        final String label;

        ProfileOption(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    private static final class CountdownEntry {
        private final JLabel label;
        private final long baseRemainingMs;
        private final long baseTimeMs;

        private CountdownEntry(JLabel label, long baseRemainingMs, long baseTimeMs) {
            this.label = label;
            this.baseRemainingMs = Math.max(0, baseRemainingMs);
            this.baseTimeMs = baseTimeMs;
        }
    }

    private static final class AgePairEntry {
        private final JComponent[] components;
        private final long buyTimestampMs;
        private final long sellTimestampMs;

        private AgePairEntry(JComponent[] components, long buyTimestampMs, long sellTimestampMs) {
            this.components = components;
            this.buyTimestampMs = buyTimestampMs;
            this.sellTimestampMs = sellTimestampMs;
        }
    }

    private static final class LineComponents {
        private final JPanel row;
        private final JLabel left;
        private final JLabel right;

        private LineComponents(JPanel row, JLabel left, JLabel right) {
            this.row = row;
            this.left = left;
            this.right = right;
        }
    }

    private static final class TrackingPanel extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return SCROLL_UNIT_INCREMENT;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return SCROLL_BLOCK_INCREMENT;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static final class RoundedPanel extends JPanel {
        private final int arc;
        private final Color borderColor;

        private RoundedPanel(int arc, Color background, Color borderColor) {
            this.arc = arc;
            this.borderColor = borderColor;
            setOpaque(false);
            setBackground(background);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(getBackground());
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
            g2.dispose();
            super.paintComponent(g);
        }

        @Override
        protected void paintBorder(Graphics g) {
            if (borderColor == null) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(borderColor);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            g2.dispose();
        }
    }

    private static final class RoundedBorder implements Border {
        private final int arc;
        private final Color color;
        private final Insets insets;

        private RoundedBorder(int arc, Color color, Insets insets) {
            this.arc = arc;
            this.color = color;
            this.insets = insets;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            if (color == null) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.drawRoundRect(x, y, width - 1, height - 1, arc, arc);
            g2.dispose();
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return insets;
        }

        @Override
        public boolean isBorderOpaque() {
            return false;
        }
    }

    private static final class EllipsisLabel extends JLabel {
        private static final String ELLIPSIS = "...";
        private String fullText = "";

        private EllipsisLabel(String text) {
            super();
            setFullText(text);
        }

        @Override
        public void setText(String text) {
            setFullText(text);
        }

        private void setFullText(String text) {
            fullText = text != null ? text : "";
            updateDisplayedText();
        }

        @Override
        public void setFont(Font font) {
            super.setFont(font);
            updateDisplayedText();
        }

        @Override
        public void setBounds(int x, int y, int width, int height) {
            boolean widthChanged = width != getWidth();
            super.setBounds(x, y, width, height);
            if (widthChanged) {
                updateDisplayedText();
            }
        }

        private void updateDisplayedText() {
            if (fullText == null || fullText.isEmpty()) {
                super.setText("");
                return;
            }
            int availableWidth = getAvailableWidth();
            super.setText(clipText(fullText, availableWidth));
        }

        private int getAvailableWidth() {
            int width = getWidth();
            Insets insets = getInsets();
            return Math.max(0, width - insets.left - insets.right);
        }

        private String clipText(String text, int maxWidth) {
            if (maxWidth <= 0) {
                return text;
            }
            FontMetrics metrics = getFontMetrics(getFont());
            if (metrics.stringWidth(text) <= maxWidth) {
                return text;
            }
            int ellipsisWidth = metrics.stringWidth(ELLIPSIS);
            if (ellipsisWidth >= maxWidth) {
                return "";
            }
            int low = 0;
            int high = text.length();
            while (low < high) {
                int mid = (low + high + 1) / 2;
                String candidate = text.substring(0, mid);
                if (metrics.stringWidth(candidate) + ellipsisWidth <= maxWidth) {
                    low = mid;
                } else {
                    high = mid - 1;
                }
            }
            return text.substring(0, low) + ELLIPSIS;
        }
    }

    FlipHubPanel(ItemManager itemManager, PanelListener listener, BookmarkStore bookmarkStore, HiddenItemStore hiddenItemStore, PluginConfig config) {
        super(false);
        this.itemManager = itemManager;
        this.listener = listener;
        this.bookmarkStore = bookmarkStore;
        this.hiddenItemStore = hiddenItemStore;
        this.config = config;
        setLayout(new BorderLayout());
        setBackground(BG);
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel header = buildHeader();
        JPanel tabs = buildTabs();
        JPanel content = buildContent();

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBackground(BG);
        top.add(header);
        top.add(Box.createVerticalStrut(8));
        top.add(tabs);
        top.add(Box.createVerticalStrut(6));

        add(top, BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);
        addMouseWheelListener(wheelForwarder);
        cardPanel.addMouseWheelListener(wheelForwarder);
        installGlobalWheelListener();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        installGlobalWheelListener();
    }

    @Override
    public void removeNotify() {
        hideAgeTooltip();
        hoveredAgeComponent = null;
        hoveredAgeEntry = null;
        uninstallGlobalWheelListener();
        super.removeNotify();
    }

    BufferedImage buildNavIcon() {
        BufferedImage icon = null;
        try {
            java.net.URL resource = getClass().getResource("/com/osrsfliphub/fliphub-icon.png");
            if (resource != null) {
                icon = javax.imageio.ImageIO.read(resource);
            }
        } catch (Exception ignored) {
        }
        if (icon == null) {
            BufferedImage fallback = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = fallback.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(ACCENT);
            g.fillRoundRect(0, 0, 16, 16, 4, 4);
            g.setColor(Color.WHITE);
            g.setFont(fontBold(10f));
            g.drawString("F", 4, 12);
            g.dispose();
            return fallback;
        }
        return ImageUtil.resizeImage(icon, 16, 16);
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(BG);

        JLabel title = new JLabel("FlipHub OSRS");
        title.setForeground(TEXT);
        title.setFont(fontBold(15f));

        profileButton.setForeground(MUTED);
        profileButton.setFont(font(10.5f));
        profileButton.setBorder(new EmptyBorder(2, 6, 2, 6));
        profileButton.setContentAreaFilled(false);
        profileButton.setFocusPainted(false);
        profileButton.setOpaque(false);
        profileButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        profileButton.addActionListener(e -> showProfileMenu());

        JPanel statusWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        statusWrap.setOpaque(false);
        statusWrap.add(profileButton);

        header.add(title, BorderLayout.WEST);
        header.add(statusWrap, BorderLayout.EAST);
        return header;
    }

    private JButton buildDiscordButton() {
        JButton button = new JButton("Discord");
        button.setToolTipText("Join the FlipHub Discord");
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setBackground(BG_ALT);
        button.setForeground(MUTED);
        button.setFont(fontSemiBold(10f));
        button.setBorder(roundedBorder(CHIP_ARC, SOFT_BORDER, new Insets(2, 8, 2, 8)));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.addActionListener(e -> openExternalUrl(DISCORD_INVITE_URL));
        return button;
    }

    private JPanel buildTabs() {
        JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        tabs.setBackground(BG);

        ButtonGroup group = new ButtonGroup();
        group.add(flippingTab);
        group.add(statsTab);

        styleTab(flippingTab, true);
        styleTab(statsTab, false);

        flippingTab.addActionListener(e -> switchTab(true));
        statsTab.addActionListener(e -> switchTab(false));

        flippingTab.setSelected(true);

        JButton discordButton = buildDiscordButton();

        tabs.add(flippingTab);
        tabs.add(statsTab);
        tabs.add(discordButton);
        return tabs;
    }

    private Font font(float size) {
        return resolveFont(Font.PLAIN, size);
    }

    private Font fontBold(float size) {
        return resolveFont(Font.BOLD, size);
    }

    private Font fontSemiBold(float size) {
        return resolveFont(Font.BOLD, size - 0.5f);
    }

    private Font fontSymbol(float size) {
        return FontManager.getDefaultBoldFont().deriveFont(size);
    }

    private Font resolveFont(int style, float size) {
        String[] families = new String[] { "Avenir Next", "Segoe UI", "Trebuchet MS" };
        int fontSize = Math.max(10, Math.round(size));
        for (String family : families) {
            Font candidate = new Font(family, style, fontSize);
            if (family.equalsIgnoreCase(candidate.getFamily())) {
                return candidate;
            }
        }
        return FontManager.getDefaultFont().deriveFont(style, size);
    }

    private void switchTab(boolean flippingSelected) {
        hideAgeTooltip();
        hoveredAgeComponent = null;
        hoveredAgeEntry = null;
        styleTab(flippingTab, flippingSelected);
        styleTab(statsTab, !flippingSelected);
        cardLayout.show(cardPanel, flippingSelected ? "flipping" : "stats");
        if (!flippingSelected && listener != null) {
            StatsRange range = (StatsRange) statsRangeCombo.getSelectedItem();
            if (range != null) {
                listener.onStatsRangeChanged(range);
            }
        }
    }

    private void styleTab(JToggleButton button, boolean active) {
        button.setFocusPainted(false);
        button.setFont(fontSemiBold(12f));
        button.setForeground(active ? TEXT : MUTED);
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 2, 0, active ? ACCENT : BG),
            BorderFactory.createEmptyBorder(6, 10, 6, 10)
        ));
    }

    private void styleComboBox(JComboBox<?> combo) {
        combo.setBackground(BG_ALT);
        combo.setForeground(TEXT);
        combo.setFont(font(11f));
        combo.setBorder(roundedBorder(INPUT_ARC, SOFT_BORDER, new Insets(4, 8, 4, 8)));
        combo.setFocusable(false);
        combo.setOpaque(true);
    }

    private void styleTextField(JTextField field) {
        field.setBackground(BG_ALT);
        field.setForeground(TEXT);
        field.setCaretColor(TEXT);
        field.setBorder(roundedBorder(INPUT_ARC, SOFT_BORDER, new Insets(6, 10, 6, 10)));
        field.setFont(font(12f));
    }

    private Border roundedBorder(int arc, Color color, Insets padding) {
        Insets borderInsets = new Insets(1, 1, 1, 1);
        Border border = new RoundedBorder(arc, color, borderInsets);
        if (padding == null) {
            return border;
        }
        return BorderFactory.createCompoundBorder(border, new EmptyBorder(padding));
    }

    private JPanel buildContent() {
        cardPanel.setBackground(BG_ALT);

        JPanel flipping = buildFlippingPanel();
        JPanel stats = buildStatsPanel();

        cardPanel.add(flipping, "flipping");
        cardPanel.add(stats, "stats");

        return cardPanel;
    }

    private JPanel buildFlippingPanel() {
        JPanel panel = new JPanel();
        panel.setBackground(BG);
        panel.setLayout(new BorderLayout());

        JPanel searchRow = new JPanel(new BorderLayout(8, 0));
        searchRow.setBackground(BG);

        styleTextField(searchField);

        bookmarkFilterButton.setFocusPainted(false);
        bookmarkFilterButton.setFont(fontSymbol(14.5f));
        bookmarkFilterButton.setBackground(BG_ALT);
        bookmarkFilterButton.setForeground(ACCENT);
        bookmarkFilterButton.setBorder(roundedBorder(CHIP_ARC, SOFT_BORDER, new Insets(6, 10, 6, 10)));
        bookmarkFilterButton.setOpaque(true);
        bookmarkFilterButton.setToolTipText("Show bookmarks only");
        bookmarkFilterButton.addActionListener(e -> {
            showBookmarkedOnly = bookmarkFilterButton.isSelected();
            bookmarkFilterButton.setBackground(BG_ALT);
            bookmarkFilterButton.setForeground(showBookmarkedOnly ? WARNING : ACCENT);
            currentPage = 1;
            if (listener != null) {
                listener.onBookmarkFilterChanged(showBookmarkedOnly);
            }
            renderItems();
        });

        searchRow.add(searchField, BorderLayout.CENTER);
        searchRow.add(bookmarkFilterButton, BorderLayout.EAST);

        refreshLabel.setForeground(MUTED);
        refreshLabel.setFont(font(10.5f));

        profileButton.setForeground(MUTED);
        profileButton.setFont(font(10.5f));

        JPanel top = new JPanel();
        top.setBackground(BG);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(searchRow);
        top.add(Box.createVerticalStrut(6));

        listPanel.setBackground(BG_ALT);
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));

        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getViewport().setBackground(BG_ALT);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setWheelScrollingEnabled(true);
        scrollPane.addMouseWheelListener(wheelForwarder);
        scrollPane.getViewport().addMouseWheelListener(wheelForwarder);
        listPanel.addMouseWheelListener(wheelForwarder);
        JScrollBar vBar = scrollPane.getVerticalScrollBar();
        vBar.setUnitIncrement(SCROLL_UNIT_INCREMENT);
        vBar.setBlockIncrement(SCROLL_BLOCK_INCREMENT);

        footerPanel = new JPanel(new BorderLayout());
        footerPanel.setBackground(BG);

        JPanel pager = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        pager.setBackground(BG);
        pager.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        prevButton.setFocusPainted(false);
        nextButton.setFocusPainted(false);
        stylePagerButton(prevButton);
        stylePagerButton(nextButton);

        prevButton.addActionListener(e -> {
            if (currentPage > 1 && listener != null) {
                listener.onPageChanged(currentPage - 1);
            }
        });
        nextButton.addActionListener(e -> {
            if (currentPage < totalPages && listener != null) {
                listener.onPageChanged(currentPage + 1);
            }
        });

        pageLabel.setForeground(MUTED);
        pageLabel.setFont(font(10.5f));
        pager.add(prevButton);
        pager.add(pageLabel);
        pager.add(nextButton);

        footerPanel.add(pager, BorderLayout.CENTER);

        panel.add(top, BorderLayout.NORTH);
        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(footerPanel, BorderLayout.SOUTH);

        hookSearchListener();
        return panel;
    }

    private JPanel buildStatsPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(BG);

        JPanel header = buildStatsHeader();
        JComponent content = buildStatsContent();

        panel.add(header, BorderLayout.NORTH);
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildStatsHeader() {
        JPanel header = new JPanel();
        header.setBackground(BG);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));

        JPanel rangeRow = new JPanel(new BorderLayout(8, 0));
        rangeRow.setBackground(BG);
        rangeRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        rangeRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        styleComboBox(statsRangeCombo);
        statsRangeCombo.setSelectedItem(StatsRange.SESSION);
        statsRangeCombo.addActionListener(e -> {
            StatsRange range = (StatsRange) statsRangeCombo.getSelectedItem();
            if (listener != null && range != null) {
                listener.onStatsRangeChanged(range);
            }
        });

        rangeRow.add(statsRangeCombo, BorderLayout.WEST);

        JPanel searchRow = new JPanel(new BorderLayout(8, 0));
        searchRow.setBackground(BG);
        searchRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        searchRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        styleTextField(statsSearchField);
        statsSearchField.setToolTipText("Filter items");
        installDocumentListener(statsSearchField, this::updateStatsSearch);

        statsClearButton.setFocusPainted(false);
        statsClearButton.setFont(fontSemiBold(10.5f));
        statsClearButton.setBackground(CARD_ALT);
        statsClearButton.setForeground(TEXT);
        statsClearButton.setBorder(roundedBorder(CHIP_ARC, SOFT_BORDER, new Insets(4, 10, 4, 10)));
        statsClearButton.setOpaque(true);
        statsClearButton.addActionListener(e -> statsSearchField.setText(""));

        searchRow.add(statsSearchField, BorderLayout.CENTER);
        searchRow.add(statsClearButton, BorderLayout.EAST);

        statsUpdatedLabel.setForeground(MUTED);
        statsUpdatedLabel.setFont(font(10.5f));
        statsUpdatedLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        header.add(rangeRow);
        header.add(Box.createVerticalStrut(6));
        header.add(searchRow);
        header.add(Box.createVerticalStrut(4));
        return header;
    }

    private JComponent buildStatsContent() {
        statsContentPanel.setBackground(BG_ALT);
        statsContentPanel.setLayout(new BoxLayout(statsContentPanel, BoxLayout.Y_AXIS));
        statsContentPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        statsTotalProfitValue = new JLabel("0 gp");
        JPanel totalBlock = buildStatsBlock("Total Profit", statsTotalProfitValue, WARNING);
        statsContentPanel.add(totalBlock);
        statsContentPanel.add(Box.createVerticalStrut(6));

        statsRoiValue = new JLabel("0.00%");
        statsFlipsValue = new JLabel("0");
        statsTaxValue = new JLabel("0 gp");
        statsSessionTimeValue = new JLabel("00:00:00");
        statsHourlyValue = new JLabel("0 gp/hr");

        Object[][] rows = new Object[][]{
            {"ROI", statsRoiValue, TEXT},
            {"Total Flips Made", statsFlipsValue, TEXT},
            {"Tax paid", statsTaxValue, TEXT},
            {"Session Time", statsSessionTimeValue, TEXT},
            {"Hourly Profit", statsHourlyValue, SUCCESS}
        };
        for (Object[] row : rows) {
            statsContentPanel.add(buildStatsRow((String) row[0], (JLabel) row[1], (Color) row[2]));
        }

        statsContentPanel.add(Box.createVerticalStrut(10));
        statsContentPanel.add(buildStatsSortRow());
        statsContentPanel.add(Box.createVerticalStrut(6));

        statsItemsListPanel.setBackground(BG_ALT);
        statsItemsListPanel.setLayout(new BoxLayout(statsItemsListPanel, BoxLayout.Y_AXIS));
        statsItemsListPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        statsContentPanel.add(statsItemsListPanel);

        statsScrollPane = new JScrollPane(statsContentPanel);
        statsScrollPane.setBorder(BorderFactory.createEmptyBorder());
        statsScrollPane.getViewport().setBackground(BG_ALT);
        statsScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        statsScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        statsScrollPane.setWheelScrollingEnabled(true);
        statsScrollPane.addMouseWheelListener(wheelForwarder);
        statsScrollPane.getViewport().addMouseWheelListener(wheelForwarder);
        JScrollBar statsBar = statsScrollPane.getVerticalScrollBar();
        statsBar.setUnitIncrement(SCROLL_UNIT_INCREMENT);
        statsBar.setBlockIncrement(SCROLL_BLOCK_INCREMENT);

        installWheelForwarder(statsContentPanel);
        renderStatsItems();
        updateStatsSummary();

        return statsScrollPane;
    }

    private JPanel buildStatsSortRow() {
        JPanel row = new JPanel(new BorderLayout());
        row.setBackground(BG_ALT);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        row.setPreferredSize(new Dimension(0, 28));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.setBackground(BG_ALT);
        JLabel sortLabel = new JLabel("Sort");
        sortLabel.setForeground(MUTED);
        sortLabel.setFont(font(10.5f));
        styleComboBox(statsSortCombo);
        statsSortCombo.setFont(font(10.5f));
        statsSortCombo.setBorder(roundedBorder(INPUT_ARC, SOFT_BORDER, new Insets(2, 6, 2, 6)));
        Dimension sortSize = statsSortCombo.getPreferredSize();
        statsSortCombo.setPreferredSize(new Dimension(sortSize.width, 24));
        statsSortCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        if (statsSortCombo.getSelectedItem() == null) {
            statsSortCombo.setSelectedItem(StatsItemSort.COMPLETION);
        }
        statsSortCombo.addActionListener(e -> {
            StatsItemSort sort = (StatsItemSort) statsSortCombo.getSelectedItem();
            if (listener != null && sort != null) {
                listener.onStatsSortChanged(sort);
            }
            renderStatsItems();
        });
        left.add(sortLabel);
        left.add(statsSortCombo);
        row.add(left, BorderLayout.WEST);
        return row;
    }

    private void updateStatsSearch() {
        String query = statsSearchField.getText();
        statsSearchQuery = query != null ? query.trim().toLowerCase(Locale.US) : "";
        renderStatsItems();
    }

    void setItems(List<FlipHubItem> items, int page, int totalPages, long asOfMs, Long priceCacheMs) {
        SwingUtilities.invokeLater(() -> {
            lastItems = items;
            lastAsOfMs = asOfMs;
            lastPriceCacheMs = priceCacheMs;
            this.currentPage = page;
            this.totalPages = totalPages <= 0 ? 1 : totalPages;
            pageLabel.setText("Page " + page + " of " + this.totalPages);
            prevButton.setEnabled(page > 1);
            nextButton.setEnabled(page < this.totalPages);

            renderItems();
        });
    }

    void setStatsSummary(StatsSummary summary, long asOfMs) {
        SwingUtilities.invokeLater(() -> {
            statsSummary = summary;
            updateStatsUpdatedLabel(asOfMs);
            updateStatsSummary();
        });
    }

    void setStatsItems(List<StatsItem> items, long asOfMs) {
        SwingUtilities.invokeLater(() -> {
            statsItems = items != null ? items : new ArrayList<>();
            updateStatsUpdatedLabel(asOfMs);
            renderStatsItems();
        });
    }

    void refreshBookmarks() {
        SwingUtilities.invokeLater(this::renderItems);
    }

    boolean isStatsTabSelected() {
        return statsTab.isSelected();
    }

    void setOfferPreview(FlipHubItem item, long asOfMs, Long priceCacheMs) {
        SwingUtilities.invokeLater(() -> {
            if (item == null || item.item_id <= 0) {
                offerPreviewItem = null;
                offerAsOfMs = 0;
                offerPriceCacheMs = null;
            } else {
                offerPreviewItem = item;
                offerAsOfMs = asOfMs;
                offerPriceCacheMs = priceCacheMs;
            }
            renderItems();
        });
    }

    void setStatusMessage(String message) {
        SwingUtilities.invokeLater(() -> profileButton.setText(message));
    }

    void setProfileHeader(String label, boolean linked) {
        SwingUtilities.invokeLater(() -> {
            profileButton.setText(label != null ? label : "");
            profileButton.setForeground(linked ? SUCCESS : MUTED);
        });
    }

    void setProfileOptions(List<ProfileOption> options, String selectedKey) {
        SwingUtilities.invokeLater(() -> {
            selectedProfileKey = selectedKey;
            rebuildProfileMenu(options);
        });
    }

    private void updateStatsUpdatedLabel(long asOfMs) {
        if (asOfMs > 0) {
            statsUpdatedLabel.setText(buildRefreshText(asOfMs, null));
        }
    }


    private void showProfileMenu() {
        if (profileMenu == null || profileMenu.getComponentCount() == 0) {
            return;
        }
        profileMenu.show(profileButton, 0, profileButton.getHeight() + 2);
    }

    private JPanel buildCard(String title, String body) {
        JPanel card = new RoundedPanel(CARD_ARC, CARD, SOFT_BORDER);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 100));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel titleLabel = new JLabel(title);
        titleLabel.setForeground(TEXT);
        titleLabel.setFont(fontSemiBold(12f));

        JLabel bodyLabel = new JLabel(body);
        bodyLabel.setForeground(MUTED);
        bodyLabel.setFont(font(10.5f));

        card.add(titleLabel);
        card.add(Box.createVerticalStrut(4));
        card.add(bodyLabel);
        return card;
    }

    private void rebuildProfileMenu(List<ProfileOption> options) {
        if (options == null || options.isEmpty()) {
            profileMenu = null;
            return;
        }
        profileMenu = new JPopupMenu();
        for (ProfileOption option : options) {
            String label = option != null ? option.label : null;
            String key = option != null ? option.key : null;
            if (label == null || key == null) {
                continue;
            }
            JMenuItem item = new JMenuItem(label);
            item.setFont(font(11f));
            item.setForeground(TEXT);
            item.setBackground(BG_ALT);
            item.setOpaque(true);
            if (selectedProfileKey != null && selectedProfileKey.equals(key)) {
                item.setFont(fontSemiBold(11f));
                item.setForeground(ACCENT);
            }
            item.addActionListener(e -> {
                selectedProfileKey = key;
                if (listener != null) {
                    listener.onProfileSelected(key);
                }
            });
            profileMenu.add(item);
        }
    }

    private JPanel buildStatsBlock(String label, JLabel valueView, Color valueColor) {
        JPanel block = new RoundedPanel(CARD_ARC, CARD, SOFT_BORDER);
        block.setLayout(new BorderLayout());
        block.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        block.setMaximumSize(new Dimension(Integer.MAX_VALUE, 64));
        block.setPreferredSize(new Dimension(0, 64));
        block.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel labelView = new JLabel(label);
        labelView.setForeground(MUTED);
        labelView.setFont(font(10.5f));

        valueView.setForeground(valueColor);
        valueView.setFont(fontBold(17f));
        valueView.setAlignmentX(Component.LEFT_ALIGNMENT);

        block.add(labelView, BorderLayout.NORTH);
        block.add(valueView, BorderLayout.CENTER);
        return block;
    }

    private JPanel buildStatsRow(String label, JLabel valueView, Color valueColor) {
        JPanel row = new RoundedPanel(CARD_ARC, CARD_ALT, SOFT_BORDER);
        row.setLayout(new BorderLayout());
        row.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel labelView = new JLabel(label);
        labelView.setForeground(MUTED);
        labelView.setFont(font(10.5f));

        valueView.setHorizontalAlignment(SwingConstants.RIGHT);
        valueView.setForeground(valueColor);
        valueView.setFont(fontSemiBold(12f));

        row.add(labelView, BorderLayout.WEST);
        row.add(valueView, BorderLayout.EAST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        row.setPreferredSize(new Dimension(0, 32));
        return row;
    }

    private JPanel buildItemCard(FlipHubItem item, long asOfMs, boolean compactRightPadding) {
        JPanel card = new RoundedPanel(CARD_ARC, CARD, SOFT_BORDER);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        int cardRightPadding = compactRightPadding ? OFFER_VALUE_RIGHT_PADDING : VALUE_RIGHT_PADDING;
        card.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, cardRightPadding));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel header = new JPanel(new BorderLayout(7, 0));
        header.setBackground(CARD);

        JLabel iconLabel = new JLabel();
        iconLabel.setPreferredSize(new Dimension(32, 32));
        setItemIcon(iconLabel, item.item_id);

        JLayeredPane iconLayer = new JLayeredPane();
        iconLayer.setLayout(null);
        iconLayer.setPreferredSize(new Dimension(32, 32));
        iconLayer.setMinimumSize(new Dimension(32, 32));
        iconLayer.setMaximumSize(new Dimension(32, 32));
        iconLabel.setBounds(0, 0, 32, 32);
        iconLayer.add(iconLabel, JLayeredPane.DEFAULT_LAYER);

        JButton removeButton = buildRemoveButton(item);
        int removeSize = 12;
        int removeOffset = (32 - removeSize) / 2;
        removeButton.setBounds(removeOffset, removeOffset, removeSize, removeSize);
        iconLayer.add(removeButton, JLayeredPane.PALETTE_LAYER);
        installRemoveHover(iconLayer, removeButton);

        String resolvedName = item.item_name != null && !item.item_name.trim().isEmpty()
            ? item.item_name
            : "Item " + item.item_id;
        EllipsisLabel nameLabel = new EllipsisLabel(resolvedName);
        nameLabel.setForeground(TEXT);
        nameLabel.setFont(fontBold(13f));
        attachOpenItemPageHandler(nameLabel, item.item_id, resolvedName);

        header.add(iconLayer, BorderLayout.WEST);
        header.add(nameLabel, BorderLayout.CENTER);

        boolean bookmarked = isBookmarked(item.item_id);
        JButton bookmarkButton = new JButton(bookmarked ? "\u2605" : "\u2606");
        bookmarkButton.setFocusPainted(false);
        bookmarkButton.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        bookmarkButton.setBorderPainted(false);
        bookmarkButton.setContentAreaFilled(false);
        bookmarkButton.setOpaque(false);
        bookmarkButton.setForeground(bookmarked ? WARNING : MUTED);
        bookmarkButton.setFont(fontSymbol(16f));
        bookmarkButton.setPreferredSize(new Dimension(30, 24));
        bookmarkButton.setToolTipText("Bookmark");
        bookmarkButton.addActionListener(e -> {
            if (bookmarkStore != null) {
                bookmarkStore.toggleBookmark(item.item_id);
                boolean nowBookmarked = isBookmarked(item.item_id);
                bookmarkButton.setText(nowBookmarked ? "\u2605" : "\u2606");
                bookmarkButton.setForeground(nowBookmarked ? WARNING : MUTED);
                if (showBookmarkedOnly && !nowBookmarked) {
                    renderItems();
                }
            }
        });

        header.add(bookmarkButton, BorderLayout.EAST);

        card.add(header);
        card.add(Box.createVerticalStrut(6));
        int rightPadding = compactRightPadding ? OFFER_VALUE_RIGHT_PADDING : VALUE_RIGHT_PADDING;
        LineComponents instaSellLine = buildLineComponents("Sell Price", formatGp(item.instasell_price), SUCCESS, rightPadding);
        LineComponents instaBuyLine = buildLineComponents("Buy Price", formatGp(item.instabuy_price), MUTED, rightPadding);
        card.add(instaSellLine.row);
        card.add(instaBuyLine.row);
        registerAgePair(item.instabuy_ts_ms, item.instasell_ts_ms, instaBuyLine, instaSellLine);
        Color roiColor = item.roi_percent != null && item.roi_percent < 0 ? DANGER : TEXT;
        Object[][] lines = new Object[][]{
            {"Last sell price", formatGp(item.last_sell_price), TEXT},
            {"Last buy price", formatGp(item.last_buy_price), TEXT},
            {"Margin", formatGp(item.margin), WARNING},
            {"Margin x limit", formatGp(item.margin_x_limit), WARNING},
            {"ROI", formatPercent(item.roi_percent), roiColor},
            {"GE limit remaining", formatLimit(item.ge_limit_remaining, item.ge_limit_total), TEXT}
        };
        for (Object[] line : lines) {
            card.add(buildLine((String) line[0], (String) line[1], (Color) line[2], rightPadding));
        }
        Long resetMs = item.ge_limit_reset_ms;
        if (item.ge_limit_total != null && item.ge_limit_total > 0
            && item.ge_limit_remaining != null
            && item.ge_limit_remaining >= item.ge_limit_total) {
            resetMs = 0L;
        }
        card.add(buildCountdownLine("GE limit reset", resetMs, asOfMs, SUCCESS, rightPadding));

        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
        installWheelForwarder(card);
        return card;
    }

    private JPanel buildLine(String label, String value, Color valueColor, int rightPadding) {
        return buildLineComponents(label, value, valueColor, rightPadding).row;
    }

    private LineComponents buildLineComponents(String label, String value, Color valueColor, int rightPadding) {
        JPanel row = new JPanel(new BorderLayout());
        row.setBackground(CARD);

        JLabel left = new JLabel(label + ":");
        left.setForeground(MUTED);
        left.setFont(font(10.5f));

        JLabel right = new JLabel(value, SwingConstants.RIGHT);
        right.setForeground(valueColor);
        right.setFont(fontSemiBold(12f));
        right.setBorder(new EmptyBorder(0, 0, 0, rightPadding));

        row.add(left, BorderLayout.WEST);
        row.add(right, BorderLayout.EAST);
        return new LineComponents(row, left, right);
    }

    private JButton buildRemoveButton(FlipHubItem item) {
        JButton removeButton = new JButton("X");
        removeButton.setFocusPainted(false);
        removeButton.setBorderPainted(false);
        removeButton.setContentAreaFilled(false);
        removeButton.setOpaque(false);
        removeButton.setForeground(DANGER);
        removeButton.setFont(fontBold(12f));
        removeButton.setMargin(new Insets(0, 0, 0, 0));
        removeButton.setToolTipText("Remove item");
        removeButton.setVisible(false);
        removeButton.addActionListener(e -> {
            if (hiddenItemStore != null && item != null && item.item_id > 0) {
                hiddenItemStore.hideItem(item.item_id);
                renderItems();
            }
        });
        return removeButton;
    }

    private void installRemoveHover(JLayeredPane iconLayer, JButton removeButton) {
        MouseAdapter adapter = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                removeButton.setVisible(true);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                updateRemoveVisibility(iconLayer, removeButton);
            }
        };
        iconLayer.addMouseListener(adapter);
        for (Component component : iconLayer.getComponents()) {
            if (component instanceof JComponent) {
                ((JComponent) component).addMouseListener(adapter);
            }
        }
    }

    private void updateRemoveVisibility(JComponent iconLayer, JButton removeButton) {
        Point pointer = MouseInfo.getPointerInfo() != null
            ? MouseInfo.getPointerInfo().getLocation()
            : null;
        if (pointer == null) {
            removeButton.setVisible(false);
            return;
        }
        SwingUtilities.convertPointFromScreen(pointer, iconLayer);
        removeButton.setVisible(iconLayer.contains(pointer));
    }

    private JPanel buildCountdownLine(String label, Long remainingMs, long asOfMs, Color valueColor, int rightPadding) {
        JPanel row = new JPanel(new BorderLayout());
        row.setBackground(CARD);

        JLabel left = new JLabel(label + ":");
        left.setForeground(MUTED);
        left.setFont(font(10.5f));

        JLabel right = new JLabel(formatDuration(remainingMs), SwingConstants.RIGHT);
        right.setForeground(valueColor);
        right.setFont(fontSemiBold(12f));
        right.setBorder(new EmptyBorder(0, 0, 0, rightPadding));

        row.add(left, BorderLayout.WEST);
        row.add(right, BorderLayout.EAST);

        if (remainingMs != null) {
            long baseTimeMs = asOfMs > 0 ? asOfMs : System.currentTimeMillis();
            CountdownEntry entry = new CountdownEntry(right, remainingMs, baseTimeMs);
            countdownEntries.add(entry);
            updateCountdownEntry(entry, System.currentTimeMillis());
        }

        return row;
    }

    private void registerAgePair(Long buyTimestampMs, Long sellTimestampMs,
                                 LineComponents buyLine, LineComponents sellLine) {
        long buyTs = buyTimestampMs != null ? buyTimestampMs : 0;
        long sellTs = sellTimestampMs != null ? sellTimestampMs : 0;
        JComponent[] components = new JComponent[] {
            buyLine.row, buyLine.left, buyLine.right,
            sellLine.row, sellLine.left, sellLine.right
        };
        AgePairEntry entry = new AgePairEntry(components, buyTs, sellTs);
        ageEntries.add(entry);
        for (JComponent component : components) {
            component.putClientProperty(AGE_ENTRY_KEY, entry);
            component.setToolTipText(null);
        }
        installAgeHoverTracking(components);
        updateAgeEntry(entry, System.currentTimeMillis());
    }

    private void setItemIcon(JLabel label, int itemId) {
        if (label == null) {
            return;
        }
        if (itemManager == null) {
            label.setIcon(null);
            return;
        }
        BufferedImage image = itemManager.getImage(itemId);
        if (image == null) {
            label.setIcon(null);
            return;
        }
        if (image instanceof AsyncBufferedImage) {
            AsyncBufferedImage async = (AsyncBufferedImage) image;
            async.addTo(label);
            label.setIcon(new ImageIcon(async));
            return;
        }
        ImageIcon cached = iconCache.get(itemId);
        if (cached == null) {
            BufferedImage resized = ImageUtil.resizeImage(image, 32, 32);
            cached = new ImageIcon(resized);
            iconCache.put(itemId, cached);
        }
        label.setIcon(cached);
    }

    private String resolveItemName(int itemId) {
        if (itemId <= 0) {
            return "Unknown Item";
        }
        if (itemManager != null) {
            try {
                String name = itemManager.getItemComposition(itemId).getName();
                if (name != null && !name.trim().isEmpty()) {
                    return name;
                }
            } catch (AssertionError ignored) {
            } catch (Exception ignored) {
            }
        }
        return "Item " + itemId;
    }

    private String formatGp(Integer value) {
        if (value == null) {
            return "N/A";
        }
        return formatGpValue(value.longValue());
    }

    private String formatGp(Long value) {
        if (value == null) {
            return "N/A";
        }
        return formatGpValue(value);
    }

    private String formatPercent(Double value) {
        if (value == null) {
            return "N/A";
        }
        return String.format(Locale.US, "%.2f%%", value);
    }

    private String formatGpPerHour(Double value) {
        if (value == null) {
            return "N/A";
        }
        long rounded = Math.round(value);
        return formatNumber(rounded) + " gp/hr";
    }

    private String formatGpValue(long value) {
        return formatNumber(value) + " gp";
    }

    private String formatNumber(long value) {
        NumberFormat formatter = NumberFormat.getIntegerInstance(Locale.US);
        return formatter.format(value);
    }

    private String formatLimit(Integer remaining, Integer total) {
        if (total == null || total == 0) {
            return "N/A";
        }
        int remainingVal = remaining != null ? remaining : 0;
        return remainingVal + " / " + total;
    }

    private String formatDuration(Long ms) {
        if (ms == null) {
            return "N/A";
        }
        long totalSeconds = Math.max(0, ms / 1000);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
    }

    private String buildRefreshText(long asOfMs, Long priceCacheMs) {
        String asOf = REFRESH_TIME_FORMATTER.format(Instant.ofEpochMilli(asOfMs));
        if (priceCacheMs != null) {
            String cache = REFRESH_TIME_FORMATTER.format(Instant.ofEpochMilli(priceCacheMs));
            return "Updated: " + asOf + " (Prices: " + cache + ")";
        }
        return "Updated: " + asOf;
    }

    private void hookSearchListener() {
        installDocumentListener(searchField, this::scheduleSearch);
    }

    private void installDocumentListener(JTextField field, Runnable onChange) {
        field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                onChange.run();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                onChange.run();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                onChange.run();
            }
        });
    }

    private void scheduleSearch() {
        if (searchTimer == null) {
            searchTimer = new Timer(300, e -> {
                if (listener != null) {
                    listener.onSearchChanged(searchField.getText());
                }
            });
            searchTimer.setRepeats(false);
        }
        searchTimer.restart();
    }

    private void stylePagerButton(JButton button) {
        button.setBackground(CARD_ALT);
        button.setForeground(TEXT);
        button.setBorder(roundedBorder(CHIP_ARC, SOFT_BORDER, new Insets(4, 10, 4, 10)));
        button.setFont(fontSemiBold(11f));
        button.setFocusPainted(false);
        button.setOpaque(true);
    }

    private boolean isBookmarked(int itemId) {
        return bookmarkStore != null && bookmarkStore.isBookmarked(itemId);
    }

    private boolean isHidden(int itemId) {
        return hiddenItemStore != null && hiddenItemStore.isHidden(itemId);
    }

    private void renderItems() {
        listPanel.removeAll();
        countdownEntries.clear();
        ageEntries.clear();
        hoveredAgeComponent = null;
        hoveredAgeEntry = null;
        hideAgeTooltip();

        if (offerPreviewItem != null) {
            listPanel.add(buildSectionHeader("Offer setup"));
            listPanel.add(buildItemCard(offerPreviewItem, offerAsOfMs, true));
        } else if (lastItems == null || lastItems.isEmpty()) {
            listPanel.add(buildEmptyStateCard());
        } else if (showBookmarkedOnly) {
            List<FlipHubItem> itemsToShow = new ArrayList<>();
            for (FlipHubItem item : lastItems) {
                if (!isHidden(item.item_id) && isBookmarked(item.item_id)) {
                    itemsToShow.add(item);
                }
            }
            if (itemsToShow.isEmpty()) {
                listPanel.add(buildEmptyStateCard());
            } else {
                listPanel.add(buildSectionHeader("Bookmarked items"));
                listPanel.add(Box.createVerticalStrut(6));
                addItemCards(itemsToShow, lastAsOfMs);
            }
        } else {
            addItemCards(lastItems, lastAsOfMs);
        }

        long refreshAsOf = lastAsOfMs > 0 ? lastAsOfMs : offerAsOfMs;
        Long refreshCacheMs = lastPriceCacheMs != null ? lastPriceCacheMs : offerPriceCacheMs;
        if (refreshAsOf > 0) {
            refreshLabel.setText(buildRefreshText(refreshAsOf, refreshCacheMs));
        }
        if (footerPanel != null) {
            footerPanel.setVisible(offerPreviewItem == null);
        }

        if (offerPreviewItem != null) {
            JScrollBar bar = scrollPane.getVerticalScrollBar();
            if (bar != null) {
                bar.setValue(0);
            }
        }

        ensureCountdownTimer();
        listPanel.revalidate();
        listPanel.repaint();
    }

    private JComponent buildEmptyStateCard() {
        return showBookmarkedOnly
            ? buildCard("No bookmarks", "Bookmark items to pin them here.")
            : buildCard("No flip history", "Make a trade to see your items here.");
    }

    private void addItemCards(List<FlipHubItem> items, long asOfMs) {
        if (items == null) {
            return;
        }
        for (FlipHubItem item : items) {
            if (item == null || isHidden(item.item_id)) {
                continue;
            }
            listPanel.add(buildItemCard(item, asOfMs, false));
            listPanel.add(Box.createVerticalStrut(8));
        }
    }

    private void updateStatsSummary() {
        if (statsTotalProfitValue == null || statsRoiValue == null) {
            return;
        }
        if (statsSummary == null) {
            setLabel(statsTotalProfitValue, "0 gp", WARNING);
            setLabel(statsRoiValue, "0.00%", TEXT);
            setLabel(statsFlipsValue, "0", null);
            setLabel(statsTaxValue, "0 gp", null);
            setLabel(statsSessionTimeValue, "00:00:00", null);
            setLabel(statsHourlyValue, "0 gp/hr", SUCCESS);
            return;
        }

        long totalProfit = statsSummary.total_profit_gp != null ? statsSummary.total_profit_gp : 0;
        setLabel(statsTotalProfitValue, formatGp(totalProfit), totalProfit >= 0 ? WARNING : DANGER);

        Double roi = statsSummary.roi_percent;
        setLabel(statsRoiValue, formatPercent(roi), roi != null && roi < 0 ? DANGER : TEXT);

        int flips = statsSummary.fill_count != null ? statsSummary.fill_count : 0;
        setLabel(statsFlipsValue, String.valueOf(flips), null);

        setLabel(statsTaxValue, formatGp(statsSummary.tax_paid_gp), null);
        setLabel(statsSessionTimeValue, formatDuration(statsSummary.active_ms), null);

        Double gpPerHour = statsSummary.gp_per_hour;
        setLabel(statsHourlyValue, formatGpPerHour(gpPerHour), gpPerHour != null && gpPerHour < 0 ? DANGER : SUCCESS);
    }

    private void setLabel(JLabel label, String text, Color color) {
        if (label == null) {
            return;
        }
        label.setText(text);
        if (color != null) {
            label.setForeground(color);
        }
    }

    private void renderStatsItems() {
        if (statsItemsListPanel == null) {
            return;
        }
        statsItemsListPanel.removeAll();

        List<StatsItem> items = statsItems != null ? statsItems : new ArrayList<>();
        List<StatsItem> filtered = new ArrayList<>();
        for (StatsItem item : items) {
            if (item == null) {
                continue;
            }
            String name = item.item_name != null && !item.item_name.trim().isEmpty()
                ? item.item_name
                : "Item " + item.item_id;
            if (!statsSearchQuery.isEmpty() && !name.toLowerCase(Locale.US).contains(statsSearchQuery)) {
                continue;
            }
            filtered.add(item);
        }

        StatsItemSort sort = (StatsItemSort) statsSortCombo.getSelectedItem();
        StatsItemSort effectiveSort = sort != null ? sort : StatsItemSort.COMPLETION;
        boolean hasSellTimestamp = false;
        for (StatsItem item : filtered) {
            if (item != null && item.last_sell_ts_ms != null && item.last_sell_ts_ms > 0) {
                hasSellTimestamp = true;
                break;
            }
        }
        if (effectiveSort != StatsItemSort.COMPLETION || hasSellTimestamp) {
            filtered.sort(buildStatsItemsComparator(effectiveSort));
        }

        if (filtered.isEmpty()) {
            if (statsSearchQuery.isEmpty()) {
                statsItemsListPanel.add(buildCard("No stats yet", "Make a trade to see your items here."));
            } else {
                statsItemsListPanel.add(buildCard("No matches", "Try a different search term."));
            }
        } else {
            for (StatsItem item : filtered) {
                statsItemsListPanel.add(buildStatsItemCard(item));
                statsItemsListPanel.add(Box.createVerticalStrut(6));
            }
        }

        statsItemsListPanel.revalidate();
        statsItemsListPanel.repaint();
    }

    private JPanel buildStatsItemCard(StatsItem item) {
        JPanel card = new RoundedPanel(CARD_ARC, CARD, SOFT_BORDER);
        card.setLayout(new BorderLayout(8, 0));
        card.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel iconLabel = new JLabel();
        iconLabel.setPreferredSize(new Dimension(32, 32));
        setItemIcon(iconLabel, item.item_id);

        JPanel center = new JPanel();
        center.setOpaque(false);
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));

        String name = item.item_name != null && !item.item_name.trim().isEmpty()
            ? item.item_name
            : "Item " + item.item_id;
        EllipsisLabel nameLabel = new EllipsisLabel(name);
        nameLabel.setForeground(TEXT);
        nameLabel.setFont(fontBold(12.5f));
        attachOpenItemPageHandler(nameLabel, item.item_id, name);

        JLabel metaLabel = new JLabel(buildStatsItemMeta(item));
        metaLabel.setForeground(MUTED);
        metaLabel.setFont(font(10.5f));

        center.add(nameLabel);
        center.add(metaLabel);

        long profit = item.total_profit_gp != null ? item.total_profit_gp : 0;
        JLabel profitLabel = new JLabel(formatGp(profit), SwingConstants.RIGHT);
        profitLabel.setForeground(profit >= 0 ? SUCCESS : DANGER);
        profitLabel.setFont(fontSemiBold(12f));

        card.add(iconLabel, BorderLayout.WEST);
        card.add(center, BorderLayout.CENTER);
        card.add(profitLabel, BorderLayout.EAST);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));
        card.setPreferredSize(new Dimension(0, 56));
        return card;
    }

    private String buildStatsItemMeta(StatsItem item) {
        String roi = formatPercent(item.roi_percent);
        int flips = item.fill_count != null ? item.fill_count : 0;
        int qty = item.total_qty != null ? item.total_qty : 0;
        return "ROI " + roi + " | Flips " + flips + " | Qty " + qty;
    }

    private Comparator<StatsItem> buildStatsItemsComparator(StatsItemSort sort) {
        StatsItemSort effective = sort != null ? sort : StatsItemSort.COMPLETION;
        if (effective == StatsItemSort.ROI) {
            return Comparator
                .comparingDouble(this::safeRoi)
                .reversed()
                .thenComparing(Comparator.comparingLong(this::safeProfit).reversed());
        }
        if (effective == StatsItemSort.PROFIT) {
            return Comparator
                .comparingLong(this::safeProfit)
                .reversed()
                .thenComparing(Comparator.comparingLong(this::safeLastSellTs).reversed());
        }
        return Comparator
            .comparingLong(this::safeLastSellTs)
            .reversed()
            .thenComparing(Comparator.comparingLong(this::safeProfit).reversed());
    }

    private long safeProfit(StatsItem item) {
        return item != null && item.total_profit_gp != null ? item.total_profit_gp : 0L;
    }

    private long safeLastSellTs(StatsItem item) {
        return item != null && item.last_sell_ts_ms != null ? item.last_sell_ts_ms : 0L;
    }

    private double safeRoi(StatsItem item) {
        return item != null && item.roi_percent != null ? item.roi_percent : 0.0;
    }

    private void ensureCountdownTimer() {
        if (countdownEntries.isEmpty() && ageEntries.isEmpty()) {
            hoveredAgeComponent = null;
            hoveredAgeEntry = null;
            hideAgeTooltip();
            if (countdownTimer != null) {
                countdownTimer.stop();
            }
            return;
        }
        if (countdownTimer == null) {
            countdownTimer = new Timer(1000, e -> updateCountdowns());
            countdownTimer.setRepeats(true);
        }
        if (!countdownTimer.isRunning()) {
            countdownTimer.start();
        }
        updateCountdowns();
    }

    private void updateCountdowns() {
        long now = System.currentTimeMillis();
        for (CountdownEntry entry : countdownEntries) {
            updateCountdownEntry(entry, now);
        }
        for (AgePairEntry entry : ageEntries) {
            updateAgeEntry(entry, now);
        }
        refreshAgeTooltip(now);
    }

    private void updateCountdownEntry(CountdownEntry entry, long now) {
        long remaining = entry.baseRemainingMs - (now - entry.baseTimeMs);
        if (remaining < 0) {
            remaining = 0;
        }
        entry.label.setText(formatDuration(remaining));
    }

    private void updateAgeEntry(AgePairEntry entry, long now) {
        if (entry == hoveredAgeEntry && ageTooltip != null) {
            updateAgeTooltipText(entry, now);
        }
    }

    private void installAgeHoverTracking(JComponent... components) {
        for (JComponent component : components) {
            component.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hoveredAgeComponent = component;
                    hoveredAgeX = e.getX();
                    hoveredAgeY = e.getY();
                    AgePairEntry entry = (AgePairEntry) component.getClientProperty(AGE_ENTRY_KEY);
                    if (entry == null) {
                        return;
                    }
                    if (hoveredAgeEntry != entry || ageTooltipPopup == null) {
                        hoveredAgeEntry = entry;
                        showAgeTooltip(entry, component, hoveredAgeX, hoveredAgeY, System.currentTimeMillis());
                    } else {
                        hoveredAgeEntry = entry;
                    }
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    AgePairEntry entry = (AgePairEntry) component.getClientProperty(AGE_ENTRY_KEY);
                    if (entry == null || entry != hoveredAgeEntry) {
                        return;
                    }
                    if (isPointerOverAny(entry)) {
                        return;
                    }
                    hoveredAgeComponent = null;
                    hoveredAgeEntry = null;
                    hideAgeTooltip();
                }
            });
            component.addMouseMotionListener(new MouseMotionAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    hoveredAgeComponent = component;
                    hoveredAgeX = e.getX();
                    hoveredAgeY = e.getY();
                }
            });
        }
    }

    private void refreshAgeTooltip(long now) {
        if (hoveredAgeEntry == null || ageTooltip == null) {
            return;
        }
        updateAgeTooltipText(hoveredAgeEntry, now);
    }

    private void showAgeTooltip(AgePairEntry entry, JComponent component, int x, int y, long now) {
        if (entry == null || component == null || !component.isShowing()) {
            return;
        }
        hideAgeTooltip();
        ageTooltip = component.createToolTip();
        updateAgeTooltipText(entry, now);

        Point screenPoint = new Point(x + AGE_TOOLTIP_OFFSET_X, y + AGE_TOOLTIP_OFFSET_Y);
        SwingUtilities.convertPointToScreen(screenPoint, component);
        ageTooltipPopup = PopupFactory.getSharedInstance().getPopup(component, ageTooltip, screenPoint.x, screenPoint.y);
        ageTooltipPopup.show();
    }

    private void hideAgeTooltip() {
        if (ageTooltipPopup != null) {
            ageTooltipPopup.hide();
            ageTooltipPopup = null;
        }
        ageTooltip = null;
    }

    private boolean isPointerOverAny(AgePairEntry entry) {
        if (entry == null) {
            return false;
        }
        for (JComponent component : entry.components) {
            if (isPointerOver(component)) {
                return true;
            }
        }
        return false;
    }

    private void updateAgeTooltipText(AgePairEntry entry, long now) {
        if (ageTooltip == null || entry == null) {
            return;
        }
        ageTooltip.setTipText(buildAgePairTooltip(entry, now));
        ageTooltip.revalidate();
        ageTooltip.doLayout();
        Dimension preferred = ageTooltip.getPreferredSize();
        int width = Math.max(preferred.width, AGE_TOOLTIP_MIN_WIDTH);
        ageTooltip.setPreferredSize(new Dimension(width, preferred.height));
        ageTooltip.setSize(width, preferred.height);
    }

    private String buildAgePairTooltip(AgePairEntry entry, long now) {
        String buyAge = entry.buyTimestampMs > 0
            ? formatAgeClock(now - entry.buyTimestampMs)
            : "N/A";
        String sellAge = entry.sellTimestampMs > 0
            ? formatAgeClock(now - entry.sellTimestampMs)
            : "N/A";
        return "<html><div style='font-size:10px;'>"
            + "<span style='color:#22C55E;'>Sell price age:&nbsp;</span>" + sellAge
            + "<br><span style='color:#22C55E;'>Buy price age:&nbsp;</span>" + buyAge
            + "</div></html>";
    }

    private String formatAgeClock(long ms) {
        long totalSeconds = Math.max(0, ms / 1000);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
    }

    private void installWheelForwarder(Component component) {
        component.addMouseWheelListener(wheelForwarder);
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                installWheelForwarder(child);
            }
        }
    }

    private JScrollPane getActiveScrollPane() {
        return statsTab.isSelected() ? statsScrollPane : scrollPane;
    }

    private JPanel buildSectionHeader(String text) {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(BG_ALT);
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, SOFT_BORDER));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
        JLabel label = new JLabel(text.toUpperCase(Locale.US));
        label.setForeground(MUTED);
        label.setFont(fontSemiBold(10.5f));
        header.add(label, BorderLayout.WEST);
        return header;
    }

    private void forwardWheelEvent(MouseWheelEvent e) {
        JScrollPane targetScroll = getActiveScrollPane();
        if (targetScroll == null) {
            return;
        }
        javax.swing.JViewport viewport = targetScroll.getViewport();
        if (viewport == null) {
            return;
        }
        Component view = viewport.getView();
        if (view == null) {
            return;
        }

        Dimension extent = viewport.getExtentSize();
        JScrollBar bar = targetScroll.getVerticalScrollBar();
        Dimension preferredSize = view.getPreferredSize();
        int preferredHeight = preferredSize != null ? preferredSize.height : 0;
        int maxYFromPreferred = Math.max(0, preferredHeight - extent.height);
        int maxYFromBar = bar != null ? Math.max(0, bar.getMaximum() - bar.getVisibleAmount()) : 0;
        int maxY = Math.max(maxYFromPreferred, maxYFromBar);
        if (maxY <= 0) {
            return;
        }

        int direction = e.getWheelRotation() > 0 ? 1 : -1;
        int increment = bar != null ? bar.getUnitIncrement(direction) : 0;
        if (increment <= 0) {
            increment = SCROLL_UNIT_INCREMENT;
        }
        int delta = (int) Math.round(e.getPreciseWheelRotation() * increment);
        if (delta == 0) {
            delta = direction * increment;
        }

        Point viewPos = viewport.getViewPosition();
        int newY = Math.max(0, Math.min(maxY, viewPos.y + delta));
        if (newY != viewPos.y) {
            viewport.setViewPosition(new Point(viewPos.x, newY));
        }
        e.consume();
    }

    private boolean isPointerOver(Component component) {
        if (component == null || !component.isShowing()) {
            return false;
        }
        PointerInfo pointerInfo = MouseInfo.getPointerInfo();
        if (pointerInfo == null) {
            return false;
        }
        Point location = pointerInfo.getLocation();
        SwingUtilities.convertPointFromScreen(location, component);
        return component.contains(location);
    }

    private void installGlobalWheelListener() {
        if (globalWheelListener != null) {
            return;
        }
        globalWheelListener = event -> {
            if (!(event instanceof MouseWheelEvent)) {
                return;
            }
            MouseWheelEvent wheelEvent = (MouseWheelEvent) event;
            if (!isShowing()) {
                return;
            }
            JScrollPane targetScroll = getActiveScrollPane();
            if (!isPointerOver(targetScroll) && !isPointerOver(this)) {
                return;
            }
            forwardWheelEvent(wheelEvent);
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(globalWheelListener, AWTEvent.MOUSE_WHEEL_EVENT_MASK);
    }

    private void uninstallGlobalWheelListener() {
        if (globalWheelListener != null) {
            Toolkit.getDefaultToolkit().removeAWTEventListener(globalWheelListener);
            globalWheelListener = null;
        }
    }

    private void attachOpenItemPageHandler(JComponent component, int itemId, String itemName) {
        if (component == null || itemId <= 0) {
            return;
        }
        String safeName = itemName != null ? itemName : "item";
        component.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        component.setToolTipText("Open " + safeName + " on FlipHub");
        component.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                openItemPage(itemId);
            }
        });
    }

    private void openItemPage(int itemId) {
        String itemUrl = DEFAULT_BASE_URL + "/item/" + itemId;
        openExternalUrl(itemUrl);
    }

    private void openExternalUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
            }
        } catch (Exception ex) {
        }
    }
}

