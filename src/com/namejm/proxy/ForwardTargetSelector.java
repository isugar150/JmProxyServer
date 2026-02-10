package com.namejm.proxy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class ForwardTargetSelector {
    private final List<ForwardTarget> targets;
    private final AtomicInteger index = new AtomicInteger(0);

    public ForwardTargetSelector(List<ForwardTarget> targets) {
        this.targets = targets;
    }

    public List<ForwardTarget> selectCandidates(TargetHealthTracker healthTracker) {
        if (targets.isEmpty()) {
            return List.of();
        }

        int size = targets.size();
        int startIndex = Math.floorMod(index.getAndIncrement(), size);

        List<ForwardTarget> healthyTargets = new ArrayList<>();
        List<ForwardTarget> unhealthyTargets = new ArrayList<>();

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
}
