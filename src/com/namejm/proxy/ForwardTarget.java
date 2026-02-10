package com.namejm.proxy;

import java.util.ArrayList;
import java.util.List;

public class ForwardTarget {
    private final String name;
    private final String host;
    private final int port;

    public ForwardTarget(String name, String host, int port) {
        this.name = name;
        this.host = host;
        this.port = port;
    }

    public String getName() {
        return name;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String key() {
        return name + "|" + host + ":" + port;
    }

    public static List<ForwardTarget> fromConfig(ProxyDto config) {
        List<ForwardTarget> targets = new ArrayList<>();
        if (config.hasLbTargets()) {
            for (ProxyDto.LbTarget lbTarget : config.getLb()) {
                targets.add(new ForwardTarget(
                    lbTarget.getName(),
                    lbTarget.getForwardHost(),
                    lbTarget.getForwardPort()
                ));
            }
        } else {
            targets.add(new ForwardTarget("single", config.getForwardHost(), config.getForwardPort()));
        }
        return targets;
    }
}
