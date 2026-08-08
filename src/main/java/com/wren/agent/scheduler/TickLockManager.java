package com.wren.agent.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class TickLockManager {

    private static final Logger log = LoggerFactory.getLogger(TickLockManager.class);
    private final ConcurrentHashMap<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * Tries to acquire the per-agent tick lock.
     * Returns true if lock was acquired, false if another tick is already running for this agent.
     */
    public boolean tryAcquire(UUID agentId) {
        ReentrantLock lock = locks.computeIfAbsent(agentId, k -> new ReentrantLock());
        boolean acquired = lock.tryLock();
        if (acquired) {
            log.debug("Acquired tick lock for agent {}", agentId);
        } else {
            log.warn("Failed to acquire tick lock for agent {} — tick already in progress", agentId);
        }
        return acquired;
    }

    /**
     * Releases the per-agent tick lock if held by current thread.
     */
    public void release(UUID agentId) {
        ReentrantLock lock = locks.get(agentId);
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
            log.debug("Released tick lock for agent {}", agentId);
        }
    }

    /**
     * Checks if a tick is currently running for the agent.
     */
    public boolean isLocked(UUID agentId) {
        ReentrantLock lock = locks.get(agentId);
        return lock != null && lock.isLocked();
    }
}
