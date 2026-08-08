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
 * Rate limiter + circuit-breaker for the Gemini API.
 *
 * <p><b>Rate limiting</b>: enforces {@code wren.llm.gemini-rpm} (default 5) requests per
 * 60-second sliding window. Callers invoke {@link #acquirePermit()} before each Gemini call.
 *
 * <p><b>Circuit breaker</b>: after {@code wren.llm.gemini-circuit-failure-threshold} (default 2)
 * consecutive failures (429, 503, timeout), the circuit opens for
 * {@code wren.llm.gemini-circuit-open-seconds} (default 120 s).
 * While open, {@link #isCircuitOpen()} returns {@code true} and callers must
 * skip the LLM call and mark candidates {@code LLM_UNAVAILABLE} instead of waiting/retrying.
 * The circuit resets automatically after the open period.
 *
 * <p>Callers must invoke {@link #recordSuccess()} or {@link #recordFailure()} after each call
 * to keep the circuit state accurate.
 */
@Component
public class GeminiRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(GeminiRateLimiter.class);

    // ---- rate limiting ----
    private final int requestsPerMinute;
    private final Semaphore semaphore;
    private final AtomicLong windowStartMs = new AtomicLong(0);
    private final AtomicInteger usedInWindow = new AtomicInteger(0);

    // ---- circuit breaker ----
    private final int failureThreshold;
    private final long circuitOpenMs;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong circuitOpenUntilMs = new AtomicLong(0);

    public GeminiRateLimiter(
            @Value("${wren.llm.gemini-rpm:5}") int requestsPerMinute,
            @Value("${wren.llm.gemini-circuit-failure-threshold:2}") int failureThreshold,
            @Value("${wren.llm.gemini-circuit-open-seconds:120}") int circuitOpenSeconds) {
        this.requestsPerMinute = requestsPerMinute;
        this.semaphore = new Semaphore(requestsPerMinute, true);
        this.failureThreshold = failureThreshold;
        this.circuitOpenMs = circuitOpenSeconds * 1000L;
        log.info("GeminiRateLimiter initialised: rpm={} circuit-threshold={} circuit-open={}s",
                requestsPerMinute, failureThreshold, circuitOpenSeconds);
    }

    // ---- rate limiting API ----

    /**
     * Acquires one Gemini request permit, blocking until one is available in the current window.
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
            long elapsed = System.currentTimeMillis() - windowStartMs.get();
            long sleepMs = Math.max(0, 60_000L - elapsed) + 200L; // +200 ms safety margin
            log.info("GeminiRateLimiter: quota exhausted ({}/{} used). Waiting {}ms for window reset.",
                    requestsPerMinute, requestsPerMinute, sleepMs);
            TimeUnit.MILLISECONDS.sleep(sleepMs);
            maybeResetWindow();
            semaphore.acquire();
        }
    }

    public int getRequestsPerMinute() {
        return requestsPerMinute;
    }

    // ---- circuit-breaker API ----

    /**
     * Returns {@code true} if the circuit is currently open (Gemini should not be called).
     * The circuit auto-resets after the configured open period.
     */
    public boolean isCircuitOpen() {
        long openUntil = circuitOpenUntilMs.get();
        if (openUntil == 0) {
            return false;
        }
        if (System.currentTimeMillis() >= openUntil) {
            // Auto-reset
            if (circuitOpenUntilMs.compareAndSet(openUntil, 0)) {
                consecutiveFailures.set(0);
                log.info("GeminiRateLimiter: circuit CLOSED (open period expired)");
            }
            return false;
        }
        return true;
    }

    /**
     * Records a successful LLM call — resets consecutive failure count.
     */
    public void recordSuccess() {
        int prev = consecutiveFailures.getAndSet(0);
        if (prev > 0) {
            log.info("GeminiRateLimiter: circuit recovered after {} consecutive failures", prev);
        }
    }

    /**
     * Records a failed LLM call (429, 503, timeout, etc.).
     * Opens the circuit if the failure threshold is reached.
     */
    public void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        log.warn("GeminiRateLimiter: consecutive failure #{}", failures);
        if (failures >= failureThreshold) {
            long openUntil = System.currentTimeMillis() + circuitOpenMs;
            circuitOpenUntilMs.set(openUntil);
            log.warn("GeminiRateLimiter: circuit OPENED after {} failures — will stay open for {}ms",
                    failures, circuitOpenMs);
        }
    }

    // ----- internal helpers -----

    private void maybeResetWindow() {
        long now = System.currentTimeMillis();
        long windowStart = windowStartMs.get();
        if (windowStart == 0 || now - windowStart >= 60_000L) {
            if (windowStartMs.compareAndSet(windowStart, now)) {
                int drained = semaphore.drainPermits();
                semaphore.release(requestsPerMinute);
                usedInWindow.set(0);
                log.debug("GeminiRateLimiter: window reset (reclaimed {} unused permits, refilled to {})",
                        drained, requestsPerMinute);
            }
        }
    }
}
