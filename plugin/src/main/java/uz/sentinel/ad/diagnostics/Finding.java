package uz.sentinel.ad.diagnostics;

import uz.sentinel.ad.api.DupeType;

import java.time.Instant;
import java.util.UUID;

/** One flagged duplication. */
public record Finding(Instant time, UUID playerId, String playerName, DupeType type,
                      int confidence, String detail, boolean test) {

    /** Single log line, without a trailing newline. */
    public String toLogLine() {
        return time + " player=" + playerName + " uuid=" + playerId + " type=" + type.id()
                + " confidence=" + confidence + (test ? " test=true" : "") + " detail=" + detail.replace('\n', ' ').replace('\r', ' ');
    }
}
