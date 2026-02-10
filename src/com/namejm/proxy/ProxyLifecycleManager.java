package com.namejm.proxy;

import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;

class ProxyLifecycleManager {
    private final Logger logger;
    private final Object lock = new Object();
    private final Map<String, ProxyMain> proxyInstances = new HashMap<>();

    ProxyLifecycleManager(Logger logger) {
        this.logger = logger;
    }

    void applyConfig(List<ProxyDto> newConfig, InetAddressLocator inetAddressLocator, String reason) {
        synchronized (lock) {
            Map<String, ProxyDto> desiredConfigs = deduplicateByName(newConfig);
            Set<String> activeNames = new HashSet<>(proxyInstances.keySet());

            for (String activeName : activeNames) {
                if (!desiredConfigs.containsKey(activeName)) {
                    stopProxy(activeName, reason + " - removed");
                }
            }

            for (Map.Entry<String, ProxyDto> desired : desiredConfigs.entrySet()) {
                String name = desired.getKey();
                ProxyDto newProxyConfig = desired.getValue();
                ProxyMain runningProxy = proxyInstances.get(name);

                if (runningProxy == null) {
                    ProxyMain started = startProxy(newProxyConfig, inetAddressLocator);
                    if (started != null) {
                        proxyInstances.put(name, started);
                    }
                    continue;
                }

                if (isSameConfig(runningProxy.getConfig(), newProxyConfig)) {
                    logger.info("Keeping proxy unchanged [{}]: {}", reason, name);
                    continue;
                }

                stopProxy(name, reason + " - updated");
                ProxyMain started = startProxy(newProxyConfig, inetAddressLocator);
                if (started != null) {
                    proxyInstances.put(name, started);
                }
            }
        }
    }

    void shutdownAll(String reason) {
        synchronized (lock) {
            shutdownAllInternal(reason);
        }
    }

    private void shutdownAllInternal(String reason) {
        List<String> names = new ArrayList<>(proxyInstances.keySet());
        for (String name : names) {
            try {
                stopProxy(name, reason);
            } catch (Exception e) {
                logger.error("Error shutting down proxy: {}", name, e);
            }
        }
    }

    private ProxyMain startProxy(ProxyDto proxyConfig, InetAddressLocator inetAddressLocator) {
        try {
            logger.info("Starting ProxyMain for proxy: {}", proxyConfig.getName());
            ProxyMain proxyMain = new ProxyMain(proxyConfig, inetAddressLocator);
            proxyMain.start();
            logger.info("ProxyMain started successfully for proxy: {}", proxyConfig.getName());
            return proxyMain;
        } catch (IOException e) {
            logger.error("!!! Failed to start proxy '{}': {}", proxyConfig.getName(), e.getMessage(), e);
        } catch (Exception e) {
            logger.error("!!! Unexpected error during proxy startup '{}': {}", proxyConfig.getName(), e.getMessage(), e);
        }
        return null;
    }

    private void stopProxy(String name, String reason) {
        ProxyMain proxy = proxyInstances.remove(name);
        if (proxy == null) {
            return;
        }
        try {
            logger.info("Shutting down proxy [{}]: {}", reason, name);
            proxy.shutdown();
        } catch (Exception e) {
            logger.error("Error shutting down proxy: {}", name, e);
        }
    }

    private Map<String, ProxyDto> deduplicateByName(List<ProxyDto> newConfig) {
        Map<String, ProxyDto> deduplicated = new HashMap<>();
        for (ProxyDto proxyConfig : newConfig) {
            if (proxyConfig == null || proxyConfig.getName() == null || proxyConfig.getName().trim().isEmpty()) {
                logger.warn("Skipping unnamed proxy entry during applyConfig.");
                continue;
            }
            if (deduplicated.containsKey(proxyConfig.getName())) {
                logger.warn("Duplicate proxy name detected: {}. Keeping the first definition.", proxyConfig.getName());
                continue;
            }
            deduplicated.put(proxyConfig.getName(), proxyConfig);
        }
        return deduplicated;
    }

    private boolean isSameConfig(ProxyDto currentConfig, ProxyDto newConfig) {
        if (currentConfig == null || newConfig == null) {
            return false;
        }
        return currentConfig.toString().equals(newConfig.toString());
    }
}
