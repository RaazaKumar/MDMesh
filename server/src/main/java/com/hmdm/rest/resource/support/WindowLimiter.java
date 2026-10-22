/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource.support;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Allows at most {@code max} actions per key within any sliding time window. A refused attempt takes no slot, so it
 * does not extend the window.
 *
 * <p>In memory, so it is per server process: a restart forgets it, and several nodes would each allow {@code max}.
 * MDMesh runs a single server node.</p>
 */
public final class WindowLimiter {

    /** Past this many keys, keys with only expired slots are dropped on the next acquire, so memory follows the active keys. */
    static final int PRUNE_THRESHOLD = 1000;

    private final LongSupplier clock;
    private final long windowMs;
    private final int max;
    /** Per key, the times its slots were taken, oldest first. Each deque is only touched inside a map compute. */
    private final Map<String, Deque<Long>> taken = new ConcurrentHashMap<>();

    /**
     * @param clock    the current time in milliseconds (injectable for tests)
     * @param windowMs how long a taken slot stays taken
     * @param max      slots per key within one window
     */
    public WindowLimiter(LongSupplier clock, long windowMs, int max) {
        this.clock = clock;
        this.windowMs = windowMs;
        this.max = max;
    }

    /** @return true, taking a slot, when the key has used fewer than {@code max} slots within the last window. */
    public boolean tryAcquire(String key) {
        long now = clock.getAsLong();
        boolean[] acquired = {false};
        taken.compute(key, (k, times) -> {
            Deque<Long> t = times == null ? new ArrayDeque<>() : times;
            dropExpired(t, now);
            if (t.size() < max) {
                t.addLast(now);
                acquired[0] = true;
            }
            return t.isEmpty() ? null : t;
        });
        if (taken.size() > PRUNE_THRESHOLD) {
            pruneExpired(now);
        }
        return acquired[0];
    }

    /** Gives back the key's most recent slot: for an action that was allowed but then could not be carried out. */
    public void release(String key) {
        taken.computeIfPresent(key, (k, t) -> {
            t.pollLast();
            return t.isEmpty() ? null : t;
        });
    }

    /** The number of keys remembered right now. */
    int size() {
        return taken.size();
    }

    private void pruneExpired(long now) {
        for (String key : taken.keySet()) {
            taken.computeIfPresent(key, (k, t) -> {
                dropExpired(t, now);
                return t.isEmpty() ? null : t;
            });
        }
    }

    private void dropExpired(Deque<Long> times, long now) {
        while (!times.isEmpty() && now - times.peekFirst() >= windowMs) {
            times.pollFirst();
        }
    }
}
