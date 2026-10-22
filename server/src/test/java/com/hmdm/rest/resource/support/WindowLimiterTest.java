/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource.support;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WindowLimiterTest {

    private static final long HOUR = 60L * 60L * 1000L;

    private final AtomicLong now = new AtomicLong(1_000_000L);

    private WindowLimiter limiter(int max) {
        return new WindowLimiter(now::get, HOUR, max);
    }

    @Test
    public void allowsTheFirstAndRefusesTheRestWithinTheWindow() {
        WindowLimiter once = limiter(1);
        assertTrue(once.tryAcquire("1234"));
        assertFalse(once.tryAcquire("1234"));
        now.addAndGet(HOUR - 1);
        assertFalse(once.tryAcquire("1234"));
    }

    @Test
    public void allowsAgainOnceTheWindowHasPassed() {
        WindowLimiter once = limiter(1);
        assertTrue(once.tryAcquire("1234"));
        now.addAndGet(HOUR);
        assertTrue(once.tryAcquire("1234"));
        assertFalse(once.tryAcquire("1234"));
    }

    @Test
    public void allowsUpToTheMaximumPerWindow() {
        WindowLimiter three = limiter(3);
        assertTrue(three.tryAcquire("c1"));
        now.addAndGet(10);
        assertTrue(three.tryAcquire("c1"));
        assertTrue(three.tryAcquire("c1"));
        assertFalse(three.tryAcquire("c1"));
        // The window slides: the first slot frees up an hour after it was used, the others 10 ms later.
        now.addAndGet(HOUR - 10);
        assertTrue(three.tryAcquire("c1"));
        assertFalse(three.tryAcquire("c1"));
    }

    @Test
    public void keysAreIndependent() {
        WindowLimiter once = limiter(1);
        assertTrue(once.tryAcquire("1234"));
        assertTrue(once.tryAcquire("5678"));
        assertFalse(once.tryAcquire("1234"));
    }

    @Test
    public void aRefusedAttemptDoesNotExtendTheWindow() {
        WindowLimiter once = limiter(1);
        assertTrue(once.tryAcquire("1234"));
        now.addAndGet(HOUR / 2);
        assertFalse(once.tryAcquire("1234"));
        now.addAndGet(HOUR / 2);
        assertTrue(once.tryAcquire("1234"));
    }

    @Test
    public void releaseGivesBackTheSlotJustTaken() {
        WindowLimiter two = limiter(2);
        assertTrue(two.tryAcquire("c1"));
        assertTrue(two.tryAcquire("c1"));
        two.release("c1");
        assertTrue(two.tryAcquire("c1"));
        assertFalse(two.tryAcquire("c1"));
        two.release("unknown"); // harmless
    }

    @Test
    public void forgetsExpiredKeysSoMemoryStaysBounded() {
        WindowLimiter once = limiter(1);
        for (int i = 0; i < WindowLimiter.PRUNE_THRESHOLD + 10; i++) {
            assertTrue(once.tryAcquire("dev-" + i));
        }
        now.addAndGet(HOUR);
        once.tryAcquire("fresh");
        assertEquals(1, once.size());
    }
}
