package io.github.pgallo.blackboard.control;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Lease bookkeeping with an explicit clock, so expiry can be tested without sleeping. Not thread safe. */
final class LeaseTable {

    private final long leaseMs;
    private final Map<String, Long> expiresAt = new HashMap<>();

    LeaseTable(long leaseMs) {
        this.leaseMs = leaseMs;
    }

    void grant(String id, long now) {
        expiresAt.put(id, now + leaseMs);
    }

    boolean renew(String id, long now) {
        if (!expiresAt.containsKey(id)) return false;
        expiresAt.put(id, now + leaseMs);
        return true;
    }

    void revoke(String id) {
        expiresAt.remove(id);
    }

    boolean holds(String id) {
        return expiresAt.containsKey(id);
    }

    /** Removes and returns every lease that expired at or before {@code now}. */
    List<String> expire(long now) {
        List<String> out = new ArrayList<>();
        expiresAt.entrySet().removeIf(e -> {
            if (e.getValue() <= now) {
                out.add(e.getKey());
                return true;
            }
            return false;
        });
        out.sort(null);
        return out;
    }
}
