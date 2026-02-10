package com.namejm.proxy;

import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

class ProxyLifecycleManager {
    private final Logger logger;
    private final Object lock = new Object();
    private final List<ProxyMain> proxyInstances = new ArrayList<>();

    ProxyLifecycleManager(Logger logger) {
        this.logger = logger;
    }

    void applyConfig(List<ProxyDto> newConfig, InetAddressLocator inetAddressLocator, String reason) {
        synchronized (lock) {
            shutdownAllInternal(reason);
            for (ProxyDto proxyConfig : newConfig) {
                startProxy(proxyConfig, inetAddressLocator);
            }
        }
    }

    void shutdownAll(String reason) {
        synchronized (lock) {
            shutdownAllInternal(reason);
        }
    }

    private void shutdownAllInternal(String reason) {
        for (ProxyMain proxy : proxyInstances) {
            try {
                logger.info("Shutting down proxy [{}]: {}", reason, proxy.getConfig().getName());
                proxy.shutdown();
            } catch (Exception e) {
                logger.error("Error shutting down proxy: {}",
                    (proxy.getConfig() != null ? proxy.getConfig().getName() : "Unknown Proxy"), e);
            }
        }
        proxyInstances.clear();
    }

    private void startProxy(ProxyDto proxyConfig, InetAddressLocator inetAddressLocator) {
        try {
            logger.info("Starting ProxyMain for proxy: {}", proxyConfig.getName());
            ProxyMain proxyMain = new ProxyMain(proxyConfig, inetAddressLocator);
            proxyMain.start();
            proxyInstances.add(proxyMain);
            logger.info("ProxyMain started successfully for proxy: {}", proxyConfig.getName());
        } catch (IOException e) {
            logger.error("!!! Failed to start proxy '{}': {}", proxyConfig.getName(), e.getMessage(), e);
        } catch (Exception e) {
            logger.error("!!! Unexpected error during proxy startup '{}': {}", proxyConfig.getName(), e.getMessage(), e);
        }
    }
}
