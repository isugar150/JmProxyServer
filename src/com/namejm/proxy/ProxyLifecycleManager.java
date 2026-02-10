package com.namejm.proxy;

import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

class ProxyLifecycleManager {
    private final Logger logger;
    private final Map<String, ProxyMain> proxyInstances = new ConcurrentHashMap<>();

    ProxyLifecycleManager(Logger logger) {
        this.logger = logger;
    }

    void applyConfig(List<ProxyDto> newConfig, InetAddressLocator inetAddressLocator, String reason) {
        applyConfigInternal(newConfig, inetAddressLocator, reason);
    }

    void shutdownAll(String reason) {
        List<String> names = new ArrayList<>(proxyInstances.keySet());
        for (String name : names) {
            stopProxy(name, reason);
        }
    }

    int getActiveProxyCount() {
        return proxyInstances.size();
    }

    // Executor 기반 lifecycle을 제거했으므로 호환용 no-op으로 유지
    void shutdownExecutor() {
    }

    private void applyConfigInternal(List<ProxyDto> newConfig, InetAddressLocator inetAddressLocator, String reason) {
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

            if (runningProxy.getConfig().getBindPort() != newProxyConfig.getBindPort()) {
                ProxyMain started = startProxy(newProxyConfig, inetAddressLocator);
                if (started != null) {
                    try {
                        logger.info("Shutting down proxy [{}]: {}", reason + " - updated (old instance)", name);
                        runningProxy.shutdown();
                        proxyInstances.put(name, started);
                    } catch (Exception e) {
                        logger.error("Error shutting down old proxy during update: {}", name, e);
                        try {
                            started.shutdown();
                        } catch (Exception shutdownNewEx) {
                            logger.error("Error shutting down newly started proxy after old-stop failure: {}", name, shutdownNewEx);
                        }
                        proxyInstances.put(name, runningProxy);
                    }
                } else {
                    logger.error(
                        "Failed to update proxy '{}' (port change {} -> {}). Keeping previous instance running.",
                        name,
                        runningProxy.getConfig().getBindPort(),
                        newProxyConfig.getBindPort()
                    );
                }
                continue;
            }

            ProxyDto previousConfig = runningProxy.getConfig();
            if (!stopProxy(name, reason + " - updated")) {
                logger.error("Update aborted for proxy '{}': failed to stop previous instance.", name);
                continue;
            }

            ProxyMain started = startProxy(newProxyConfig, inetAddressLocator);
            if (started != null) {
                proxyInstances.put(name, started);
                continue;
            }

            logger.error("Update failed for proxy '{}'. Attempting rollback to previous configuration.", name);
            ProxyMain rolledBack = startProxy(previousConfig, inetAddressLocator);
            if (rolledBack != null) {
                proxyInstances.put(name, rolledBack);
                logger.info("Rollback completed for proxy '{}'. Previous instance restored.", name);
            } else {
                logger.error("Rollback failed for proxy '{}'. Proxy remains stopped.", name);
            }
        }
    }

    private ProxyMain startProxy(ProxyDto proxyConfig, InetAddressLocator inetAddressLocator) {
        ProxyMain proxyMain = null;
        try {
            logger.info("Starting ProxyMain for proxy: {}", proxyConfig.getName());
            proxyMain = new ProxyMain(proxyConfig, inetAddressLocator);
            proxyMain.start();
            logger.info("ProxyMain started successfully for proxy: {}", proxyConfig.getName());
            return proxyMain;
        } catch (IOException e) {
            logger.error("!!! Failed to start proxy '{}': {}", proxyConfig.getName(), e.getMessage(), e);
            shutdownFailedStart(proxyMain, proxyConfig.getName());
        } catch (Exception e) {
            logger.error("!!! Unexpected error during proxy startup '{}': {}", proxyConfig.getName(), e.getMessage(), e);
            shutdownFailedStart(proxyMain, proxyConfig.getName());
        }
        return null;
    }

    private void shutdownFailedStart(ProxyMain proxyMain, String proxyName) {
        if (proxyMain == null) {
            return;
        }
        try {
            proxyMain.shutdown();
        } catch (Exception shutdownEx) {
            logger.warn("Failed to clean up partially started proxy '{}': {}", proxyName, shutdownEx.getMessage(), shutdownEx);
        }
    }

    private boolean stopProxy(String name, String reason) {
        ProxyMain proxy = proxyInstances.get(name);
        if (proxy == null) {
            return true;
        }
        try {
            logger.info("Shutting down proxy [{}]: {}", reason, name);
            proxy.shutdown();
            proxyInstances.remove(name, proxy);
            return true;
        } catch (Exception e) {
            logger.error("Error shutting down proxy: {}", name, e);
            return false;
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
        if (!Objects.equals(currentConfig.getType(), newConfig.getType())) return false;
        if (!Objects.equals(currentConfig.getName(), newConfig.getName())) return false;
        if (currentConfig.getBindPort() != newConfig.getBindPort()) return false;
        if (!Objects.equals(currentConfig.getForwardHost(), newConfig.getForwardHost())) return false;
        if (currentConfig.getForwardPort() != newConfig.getForwardPort()) return false;
        if (!Objects.equals(currentConfig.getAllowedCountries(), newConfig.getAllowedCountries())) return false;
        if (!Objects.equals(currentConfig.getLbStrategyOrDefault(), newConfig.getLbStrategyOrDefault())) return false;

        if (currentConfig.getLbHealthCheckIntervalSeconds() != newConfig.getLbHealthCheckIntervalSeconds()) return false;
        if (currentConfig.getTransferTimeoutSeconds() != newConfig.getTransferTimeoutSeconds()) return false;
        if (currentConfig.getClientSoTimeoutMillis() != newConfig.getClientSoTimeoutMillis()) return false;
        if (currentConfig.getForwardConnectTimeoutMillis() != newConfig.getForwardConnectTimeoutMillis()) return false;
        if (currentConfig.getForwardSoTimeoutMillis() != newConfig.getForwardSoTimeoutMillis()) return false;
        if (currentConfig.getHealthCheckConnectTimeoutMillis() != newConfig.getHealthCheckConnectTimeoutMillis()) return false;
        if (currentConfig.getHealthCheckInitialDelaySeconds() != newConfig.getHealthCheckInitialDelaySeconds()) return false;
        if (currentConfig.getExecutorCorePoolSize() != newConfig.getExecutorCorePoolSize()) return false;
        if (currentConfig.getExecutorMaxPoolSize() != newConfig.getExecutorMaxPoolSize()) return false;
        if (currentConfig.getExecutorKeepAliveSeconds() != newConfig.getExecutorKeepAliveSeconds()) return false;
        if (currentConfig.getExecutorQueueCapacity() != newConfig.getExecutorQueueCapacity()) return false;
        if (currentConfig.getShutdownAwaitSeconds() != newConfig.getShutdownAwaitSeconds()) return false;
        if (currentConfig.getHealthFailThreshold() != newConfig.getHealthFailThreshold()) return false;
        if (currentConfig.getHealthSuccessThreshold() != newConfig.getHealthSuccessThreshold()) return false;
        if (currentConfig.getHalfCloseLingerSeconds() != newConfig.getHalfCloseLingerSeconds()) return false;
        if (currentConfig.getMaxActiveRelays() != newConfig.getMaxActiveRelays()) return false;

        List<ProxyDto.LbTarget> currentLb = currentConfig.getLb();
        List<ProxyDto.LbTarget> newLb = newConfig.getLb();
        if (currentLb == null || newLb == null) {
            return currentLb == newLb;
        }
        if (currentLb.size() != newLb.size()) {
            return false;
        }
        for (int i = 0; i < currentLb.size(); i++) {
            ProxyDto.LbTarget c = currentLb.get(i);
            ProxyDto.LbTarget n = newLb.get(i);
            if (c == null || n == null) {
                if (c != n) return false;
                continue;
            }
            if (!Objects.equals(c.getName(), n.getName())) return false;
            if (!Objects.equals(c.getForwardHost(), n.getForwardHost())) return false;
            if (c.getForwardPort() != n.getForwardPort()) return false;
        }
        return true;
    }
}
