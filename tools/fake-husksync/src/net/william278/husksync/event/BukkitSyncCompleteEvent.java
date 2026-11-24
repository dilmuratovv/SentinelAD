package net.william278.husksync.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/** Test stand-in with the same class name as HuskSync's event (HuskSync's own also extends a Bukkit PlayerEvent). */
public final class BukkitSyncCompleteEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    public BukkitSyncCompleteEvent(Player player) { super(player); }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
