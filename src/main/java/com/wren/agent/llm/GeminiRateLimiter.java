package com.wren.agent.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple sliding-window rate limiter for the Gemini API.
 *
 * <p>Enforces {@code wren.llm.gemini-rpm} (default 5) requests per 60-second window.
 * Callers invoke {@link #acquirePermit()} before each Gemini call; the call blocks
 * until a slot is available within the current window.
 *
 * <p>This is intentionally lightweight — it uses a single Semaphore whose permits
 * are reset each minute. Permits are never lost across resets: the window slides
 * forward every 60 seconds from the first call.
 */
@Component
public class GeminiRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(GeminiRateLimiter.class);

    private final int requestsPerMinute;
    private final Semaphore semaphore;
    private final AtomicLong windowStartMs = new AtomicLong(0);
    private final AtomicInteger usedInWindow = new AtomicInteger(0);

    public GeminiRateLimiter(
            @Value("${wren.llm.gemini-rpm:5}") int requestsPerMinute) {
        this.requestsPerMinute = requestsPerMinute;
        this.semaphore = new Semaphore(requestsPerMinute, true);
        log.info("GeminiRateLimiter initialised: {} requests/minute", requestsPerMinute);
    }

    /**
     * Acquires one Gemini request permit, blocking until one is available.
     * Resets the window every 60 seconds.
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public void acquirePermit() throws InterruptedException {
        maybeResetWindow();

        int used = usedInWindow.incrementAndGet();
        log.debug("GeminiRateLimiter: permit {}/{} in current window", used, requestsPerMinute);

        boolean acquired = semaphore.tryAcquire();
        if (!acquired) {
            // Window is exhausted — compute how long until it resets and sleep
            long elapsed = System.currentTimeMillis() - windowStartMs.get();
            long sleepMs = Math.max(0, 60_000L - elapsed) + 200; // +200 ms safety margin
            log.info("GeminiRateLimiter: quota exhausted ({}/{} used). Waiting {}ms for window reset.",
                    requestsPerMinute, requestsPerMinute, sleepMs);
            TimeUnit.MILLISECONDS.sleep(sleepMs);
            // After sleeping, a new window should be available
            maybeResetWindow();
            semaphore.acquire(); // block until truly available
        }
    }

    /** Returns the configured requests-per-minute cap. */
    public int getRequestsPerMinute() {
        return requestsPerMinute;
    }

    // ----- internal helpers -----

    private void maybeResetWindow() {
        long now = System.currentTimeMillis();
        long windowStart = windowStartMs.get();
        if (windowStart == 0 || now - windowStart >= 60_000L) {
            // CAS — only one thread resets the window
            if (windowStartMs.compareAndSet(windowStart, now)) {
                int drained = semaphore.drainPermits();
                semaphore.release(requestsPerMinute); // refill to full
                usedInWindow.set(0);
                log.debug("GeminiRateLimiter: window reset (reclaimed {} unused permits, refilled to {})",
                        drained, requestsPerMinute);
            }
        }
    }
}
