package com.osrsfliphub;

import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;

public class OfferSnapshot {
    public final int slot;
    public final int itemId;
    public final int price;
    public final int totalQty;
    public final int filledQty;
    public final long spentGp;
    public final String state;
    public final boolean isBuy;

    private OfferSnapshot(int slot,
                          int itemId,
                          int price,
                          int totalQty,
                          int filledQty,
                          long spentGp,
                          String state,
                          boolean isBuy) {
        this.slot = slot;
        this.itemId = itemId;
        this.price = price;
        this.totalQty = totalQty;
        this.filledQty = filledQty;
        this.spentGp = spentGp;
        this.state = state;
        this.isBuy = isBuy;
    }

    public static OfferSnapshot fromOffer(int slot, GrandExchangeOffer offer, OfferSnapshot prev) {
        GrandExchangeOfferState offerState = offer.getState();
        String state = offerState != null ? offerState.name() : "EMPTY";

        boolean isBuy = false;
        if (offerState == GrandExchangeOfferState.BUYING || offerState == GrandExchangeOfferState.BOUGHT) {
            isBuy = true;
        } else if (offerState == GrandExchangeOfferState.SELLING || offerState == GrandExchangeOfferState.SOLD) {
            isBuy = false;
        } else if (prev != null) {
            isBuy = prev.isBuy;
        }

        return new OfferSnapshot(
            slot,
            offer.getItemId(),
            offer.getPrice(),
            offer.getTotalQuantity(),
            offer.getQuantitySold(),
            offer.getSpent(),
            state,
            isBuy
        );
    }
}
