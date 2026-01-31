package com.osrsfliphub;

import java.util.UUID;

public class GeEvent {
    public String event_id;
    public String event_type;
    public long ts_client_ms;
    public int slot;
    public int item_id;
    public boolean is_buy;
    public int price;
    public int total_qty;
    public int filled_qty;
    public long spent_gp;
    public String state;
    public String prev_state;
    public int delta_qty;
    public long delta_gp;
    public Integer world;
    public int schema_version = 1;

    public static GeEvent createBase(OfferSnapshot snap, OfferSnapshot prev, String eventType) {
        GeEvent e = new GeEvent();
        e.event_id = UUID.randomUUID().toString();
        e.event_type = eventType;
        e.ts_client_ms = System.currentTimeMillis();
        e.slot = snap.slot;
        e.item_id = snap.itemId;
        e.is_buy = snap.isBuy;
        e.price = snap.price;
        e.total_qty = snap.totalQty;
        e.filled_qty = snap.filledQty;
        e.spent_gp = snap.spentGp;
        e.state = snap.state;
        e.prev_state = prev != null ? prev.state : null;
        return e;
    }
}
