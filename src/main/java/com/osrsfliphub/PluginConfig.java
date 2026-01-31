package com.osrsfliphub;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("fliphub")
public interface PluginConfig extends Config {
    @ConfigItem(
        keyName = "licenseKey",
        name = "License Key",
        description = "Paste your license key to connect the plugin"
    )
    default String licenseKey() {
        return "";
    }

    @ConfigItem(
        keyName = "unlinkNow",
        name = "Unlink (click)",
        description = "Clear link state and use local-only stats",
        position = 1
    )
    default boolean unlinkNow() {
        return false;
    }

    @ConfigItem(
        keyName = "linkCode",
        name = "Link Code (legacy)",
        description = "Legacy link code field",
        hidden = true
    )
    default String linkCode() {
        return "";
    }

    @ConfigItem(
        keyName = "deviceId",
        name = "Device ID",
        description = "Unique device identifier",
        hidden = true
    )
    default String deviceId() {
        return "";
    }

    @ConfigItem(
        keyName = "sessionToken",
        name = "Session Token",
        description = "Plugin session token",
        hidden = true
    )
    default String sessionToken() {
        return "";
    }

    @ConfigItem(
        keyName = "signingSecret",
        name = "Signing Secret",
        description = "HMAC signing secret",
        hidden = true
    )
    default String signingSecret() {
        return "";
    }

    @ConfigItem(
        keyName = "bookmarks",
        name = "Bookmarked Items",
        description = "Comma-separated item ids",
        hidden = true
    )
    default String bookmarks() {
        return "";
    }

    @ConfigItem(
        keyName = "hiddenItems",
        name = "Hidden Items",
        description = "Comma-separated item ids hidden from view",
        hidden = true
    )
    default String hiddenItems() {
        return "";
    }

    @ConfigItem(
        keyName = "geOfferUpdateTimes",
        name = "GE Offer Update Times",
        description = "Internal GE offer update timestamps",
        hidden = true
    )
    default String geOfferUpdateTimes() {
        return "";
    }

    @ConfigItem(
        keyName = "showGeOfferTimers",
        name = "Show GE Offer Timers",
        description = "Show how long since each GE offer last updated"
    )
    default boolean showGeOfferTimers() {
        return true;
    }
}
