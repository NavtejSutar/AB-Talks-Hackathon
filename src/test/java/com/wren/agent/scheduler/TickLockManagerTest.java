package com.wren.agent.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

public class TickLockManagerTest {

    private TickLockManager lockManager;

    @BeforeEach
    public void setUp() {
        lockManager = new TickLockManager();
    }

    @Test
    public void testLockAcquisitionAndReleaseAcrossThreads() throws InterruptedException {
        UUID agentId = UUID.randomUUID();

        // 1. Initial lock acquisition on main thread
        boolean acquired = lockManager.tryAcquire(agentId);
        assertThat(acquired).isTrue();
        assertThat(lockManager.isLocked(agentId)).isTrue();

        // 2. Second attempt for same agent on a DIFFERENT thread should fail
        AtomicBoolean otherThreadAcquired = new AtomicBoolean(true);
        CountDownLatch latch = new CountDownLatch(1);

        Thread otherThread = new Thread(() -> {
            otherThreadAcquired.set(lockManager.tryAcquire(agentId));
            latch.countDown();
        });
        otherThread.start();
        latch.await(2, TimeUnit.SECONDS);

        assertThat(otherThreadAcquired.get()).isFalse();

        // 3. Release lock on main thread
        lockManager.release(agentId);
        assertThat(lockManager.isLocked(agentId)).isFalse();

        // 4. Subsequent acquire on other thread should succeed
        AtomicBoolean reAcquireSuccess = new AtomicBoolean(false);
        CountDownLatch reAcquireLatch = new CountDownLatch(1);

        Thread secondThread = new Thread(() -> {
            reAcquireSuccess.set(lockManager.tryAcquire(agentId));
            if (reAcquireSuccess.get()) {
                lockManager.release(agentId);
            }
            reAcquireLatch.countDown();
        });
        secondThread.start();
        reAcquireLatch.await(2, TimeUnit.SECONDS);

        assertThat(reAcquireSuccess.get()).isTrue();
        assertThat(lockManager.isLocked(agentId)).isFalse();
    }

    @Test
    public void testIndependentLocksForDifferentAgents() {
        UUID agent1 = UUID.randomUUID();
        UUID agent2 = UUID.randomUUID();

        assertThat(lockManager.tryAcquire(agent1)).isTrue();
        assertThat(lockManager.tryAcquire(agent2)).isTrue();

        assertThat(lockManager.isLocked(agent1)).isTrue();
        assertThat(lockManager.isLocked(agent2)).isTrue();

        lockManager.release(agent1);
        assertThat(lockManager.isLocked(agent1)).isFalse();
        assertThat(lockManager.isLocked(agent2)).isTrue();

        lockManager.release(agent2);
        assertThat(lockManager.isLocked(agent2)).isFalse();
    }
}
