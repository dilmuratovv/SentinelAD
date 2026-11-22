package uz.sentinel.ad.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Fired on the main thread whenever SentinelAD flags a possible duplication.
 *
 * <p>This is an observer event: it cannot be cancelled and listening to it never changes how SentinelAD
 * blocks or logs. Companion plugins (SentinelGuard, ...) may listen to it. The getters use only JDK types
 * so that a plugin can read them reflectively without compiling against SentinelAD.</p>
 */
public final class DupeFlagEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final String playerName;
    private final DupeType type;
    private final int confidence;
    private final String detail;
    private final boolean test;

    public DupeFlagEvent(@NotNull UUID playerId, @NotNull String playerName, @NotNull DupeType type,
                         int confidence, @NotNull String detail, boolean test) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.type = type;
        this.confidence = Math.max(0, Math.min(100, confidence));
        this.detail = detail;
        this.test = test;
    }

    public @NotNull UUID getPlayerId() { return playerId; }
    public @NotNull String getPlayerName() { return playerName; }
    public @NotNull DupeType getType() { return type; }
    /** Stable kebab-case type identifier, see {@link DupeType#id()}. */
    public @NotNull String getTypeId() { return type.id(); }
    /** Detection confidence, 0 (weak hint) to 100 (certain). */
    public int getConfidence() { return confidence; }
    public @NotNull String getDetail() { return detail; }
    /** True for flags created by {@code /sad testflag}; listeners should not punish for these. */
    public boolean isTest() { return test; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}
