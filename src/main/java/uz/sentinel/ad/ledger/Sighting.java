package uz.sentinel.ad.ledger;

/**
 * One sealed item found while scanning a holder.
 *
 * @param serial the item's seal
 * @param slot   slot in the holder (-1 = cursor, 0 for single-item holders such as a ground item)
 * @param nested true when the item sits inside a shulker box or bundle (reported, never removed)
 */
public record Sighting(String serial, int slot, boolean nested) {}
