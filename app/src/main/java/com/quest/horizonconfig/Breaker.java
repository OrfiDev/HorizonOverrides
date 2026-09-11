package com.quest.horizonconfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A crash-loop safety breaker, in the spirit of Android's RescueParty but far narrower, and never
 * touching a real value.
 *
 * <p>It runs inside Horizon and watches who reads config values. Every consumer re-reads MobileConfig when
 * its process starts, so a consumer that crash-loops (the fingerprint of a value that breaks it on
 * startup) shows up here as the same caller appearing under a fast-changing pid. When a caller
 * churns {@link #LIMIT} pids within {@link #WINDOW_MS}, the breaker trips; Horizon then stops
 * applying overrides for the rest of this boot, so the next start reads real server values and the
 * loop ends. The user's configured values are untouched — just no longer applied — and come back on
 * the next reboot, or when re-enabled from the settings screen.
 *
 * <p>State is in-memory only: nothing here writes, which matters because remote preferences are
 * read-only from inside a hooked system process. Persisting a trip is unnecessary — a reboot that
 * still hits the bad value simply trips again.
 */
final class Breaker {
    private static final long WINDOW_MS = 30_000L;
    private static final int LIMIT = 4;
    // Boot and any manual restart storm churn processes; only arm after things settle, so a trip
    // means a value genuinely crash-loops a consumer, not ordinary startup.
    private static final long GRACE_MS = 120_000L;

    // Caller uid -> (pid -> first time that pid was seen), pruned to the window.
    private final Map<Integer, Map<Integer, Long>> pidsByUid = new LinkedHashMap<>();
    private final long armAt = System.currentTimeMillis() + GRACE_MS;
    private volatile boolean tripped;
    private volatile String reason;

    /** Records a config read by {@code uid}/{@code pid}. Returns true the moment it trips. */
    synchronized boolean onConsumerRead(int uid, int pid) {
        if (tripped || System.currentTimeMillis() < armAt) return false;
        long now = System.currentTimeMillis();
        Map<Integer, Long> pids = pidsByUid.computeIfAbsent(uid, key -> new LinkedHashMap<>());
        pids.values().removeIf(seen -> now - seen > WINDOW_MS);
        pids.putIfAbsent(pid, now);
        if (pids.size() >= LIMIT) {
            tripped = true;
            reason = "uid " + uid + " restarted " + pids.size() + " times in "
                    + (WINDOW_MS / 1000) + "s; overrides disabled for this boot";
            pids.clear();
            return true;
        }
        return false;
    }

    boolean isTripped() {
        return tripped;
    }

    String reason() {
        return reason;
    }

    /** Re-arms the breaker, e.g. after the user turns overrides back on. */
    synchronized void reset() {
        tripped = false;
        reason = null;
        pidsByUid.clear();
    }
}
