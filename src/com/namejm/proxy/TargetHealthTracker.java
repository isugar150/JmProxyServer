package com.namejm.proxy;

import org.slf4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TargetHealthTracker {
    private final Map<String, Boolean> health = new HashMap<>();
    private final Map<String, Integer> failCounts = new HashMap<>();
    private final Map<String, Integer> successCounts = new HashMap<>();
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
            health.put(target.key(), true);
            failCounts.put(target.key(), 0);
            successCounts.put(target.key(), 0);
        }
    }

    public synchronized boolean isHealthy(ForwardTarget target) {
        return health.getOrDefault(target.key(), true);
    }

    public synchronized void markReachable(ForwardTarget target) {
        String key = target.key();
        failCounts.put(key, 0);
        int successCount = successCounts.getOrDefault(key, 0) + 1;
        successCounts.put(key, successCount);
        if (successCount >= successThreshold) {
            setHealth(target, true);
            successCounts.put(key, 0);
        }
    }

    public synchronized void markUnreachable(ForwardTarget target) {
        String key = target.key();
        successCounts.put(key, 0);
        int failCount = failCounts.getOrDefault(key, 0) + 1;
        failCounts.put(key, failCount);
        if (failCount >= failThreshold) {
            setHealth(target, false);
            failCounts.put(key, 0);
        }
    }

    private void setHealth(ForwardTarget target, boolean healthy) {
        String key = target.key();
        Boolean previous = health.put(key, healthy);
        if (previous == null || previous.booleanValue() != healthy) {
            logger.info("{} - Target state changed: {} ({}:{}) -> {}",
                proxyName,
                target.getName(),
                target.getHost(),
                target.getPort(),
                healthy ? "HEALTHY" : "UNHEALTHY");
        }
    }
}
