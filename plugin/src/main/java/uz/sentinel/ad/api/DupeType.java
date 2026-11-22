package uz.sentinel.ad.api;

import java.util.Locale;

/** Kinds of duplication SentinelAD can flag. {@link #id()} is the stable identifier other plugins should rely on. */
public enum DupeType {
    UUID_DUPLICATE,
    STACK_OVERSIZE,
    LEDGER_MISMATCH,
    SHULKER_BREAK,
    GRAVITY_BLOCK,
    VEHICLE,
    DISCONNECT,
    BUNDLE,
    STACK_GAIN,
    OTHER;

    /** Stable kebab-case identifier, e.g. {@code uuid-duplicate}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
