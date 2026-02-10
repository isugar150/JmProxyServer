package com.namejm.proxy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class ForwardTargetSelector {
    public static final String STRATEGY_ROUND_ROBIN = "round_robin";
    public static final String STRATEGY_IP_HASH = "ip_hash";

    private final List<ForwardTarget> targets;
    private final AtomicInteger index = new AtomicInteger(0);

    public ForwardTargetSelector(List<ForwardTarget> targets) {
        this.targets = targets;
    }

    public List<ForwardTarget> selectCandidates(TargetHealthTracker healthTracker, String lbStrategy, String clientIp) {
        if (targets.isEmpty()) {
            return List.of();
        }

        int size = targets.size();
        int startIndex = resolveStartIndex(size, lbStrategy, clientIp);

        List<ForwardTarget> healthyTargets = new ArrayList<>(size);
        List<ForwardTarget> unhealthyTargets = new ArrayList<>(size);

        for (int offset = 0; offset < size; offset++) {
            int current = (startIndex + offset) % size;
            ForwardTarget target = targets.get(current);
            if (healthTracker.isHealthy(target)) {
                healthyTargets.add(target);
            } else {
                unhealthyTargets.add(target);
            }
        }

        if (!healthyTargets.isEmpty()) {
            healthyTargets.addAll(unhealthyTargets);
            return healthyTargets;
        }
        return unhealthyTargets;
    }

    private int resolveStartIndex(int size, String lbStrategy, String clientIp) {
        if (STRATEGY_IP_HASH.equalsIgnoreCase(lbStrategy) && clientIp != null && !clientIp.isEmpty()) {
            return Math.floorMod(clientIp.hashCode(), size);
        }
        return Math.floorMod(index.getAndIncrement(), size);
    }
}
