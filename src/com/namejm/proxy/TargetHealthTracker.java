package com.namejm.proxy;

import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class TargetHealthTracker {
    private static class TargetState {
        private final AtomicBoolean healthy = new AtomicBoolean(true);
        private final AtomicInteger failCount = new AtomicInteger(0);
        private final AtomicInteger successCount = new AtomicInteger(0);
    }

    private final Map<String, TargetState> states = new ConcurrentHashMap<>();
    private final int failThreshold;
    private final int successThreshold;
    private final Logger logger;
    private final String proxyName;

    public TargetHealthTracker(
        List<ForwardTarget> targets,
        int failThreshold,
        int successThreshold,
        Logger logger,
        String proxyName
    ) {
        this.failThreshold = failThreshold;
        this.successThreshold = successThreshold;
        this.logger = logger;
        this.proxyName = proxyName;

        for (ForwardTarget target : targets) {
            states.put(target.key(), new TargetState());
        }
    }

    public boolean isHealthy(ForwardTarget target) {
        TargetState state = states.get(target.key());
        return state == null || state.healthy.get();
    }

    public void markReachable(ForwardTarget target) {
        String key = target.key();
        TargetState state = states.computeIfAbsent(key, ignored -> new TargetState());
        state.failCount.set(0);
        int successCount = state.successCount.incrementAndGet();
        if (successCount >= successThreshold) {
            setHealth(target, state, true);
            state.successCount.set(0);
        }
    }

    public void markUnreachable(ForwardTarget target) {
        String key = target.key();
        TargetState state = states.computeIfAbsent(key, ignored -> new TargetState());
        state.successCount.set(0);
        int failCount = state.failCount.incrementAndGet();
        if (failCount >= failThreshold) {
            setHealth(target, state, false);
            state.failCount.set(0);
        }
    }

    private void setHealth(ForwardTarget target, TargetState state, boolean healthy) {
        boolean previous = state.healthy.getAndSet(healthy);
        if (previous != healthy) {
            logger.info("{} - Target state changed: {} ({}:{}) -> {}",
                proxyName,
                target.getName(),
                target.getHost(),
                target.getPort(),
                healthy ? "HEALTHY" : "UNHEALTHY");
        }
    }
}
