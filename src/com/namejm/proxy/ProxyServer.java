package com.namejm.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;

public class ProxyServer {
    private static final Logger logger = LoggerFactory.getLogger(ProxyServer.class);
    private static final String DEFAULT_CONFIG_PATH = "./config/application.yml";
    private static final String DEFAULT_GEO_IP_DB_PATH = "./config/GeoLite2-Country.mmdb";

    private static final ProxyConfigLoader configLoader = new ProxyConfigLoader(logger);
    private static final ProxyLifecycleManager lifecycleManager = new ProxyLifecycleManager(logger);

    private static InetAddressLocator inetAddressLocator;
    private static volatile boolean running = true;
    private static volatile Thread configWatcherThread;
    private static volatile long configWatchIntervalMillis = 2000L;
    private static volatile long configReloadDebounceMillis = 500L;
    private static volatile boolean hotReloadEnabled = true;
    private static volatile String activeGeoIpDbPath = DEFAULT_GEO_IP_DB_PATH;

    public static void main(String[] args) {
        System.out.println("       _           _____                      _____                          \n" +
                "      | |         |  __ \\                    / ____|                         \n" +
                "      | |_ __ ___ | |__) | __ _____  ___   _| (___   ___ _ ____   _____ _ __ \n" +
                "  _   | | '_ ` _ \\|  ___/ '__/ _ \\ \\/ / | | |\\___ \\ / _ \\ '__\\ \\ / / _ \\ '__|\n" +
                " | |__| | | | | | | |   | | | (_) >  <| |_| |____) |  __/ |   \\ V /  __/ |   \n" +
                "  \\____/|_| |_| |_|_|   |_|  \\___/_/\\_\\\\__, |_____/ \\___|_|    \\_/ \\___|_|   \n" +
                "                                        __/ |                                \n" +
                "                                       |___/                                 \n" +
                "Copyright © 2021 Jm's Corp All rights reserved.\n");

        String configPath = DEFAULT_CONFIG_PATH;
        if (args.length > 0) {
            configPath = args[0];
            logger.info("Using configuration file from argument: {}", configPath);
        } else {
            logger.info("Using default configuration file: {}", configPath);
        }

        try {
            ConfigLoadResult initialLoad = configLoader.load(configPath);
            if (initialLoad == null) {
                logger.error("Failed to load initial configuration from {}", configPath);
                System.exit(1);
                return;
            }

            if (!initializeGeoIpLocator(initialLoad.globalConfig)) {
                System.exit(1);
                return;
            }
            applyRuntimeOptions(initialLoad.globalConfig);
            lifecycleManager.applyConfig(initialLoad.validConfigs, inetAddressLocator, "initial load");
            addShutdownHook();
            if (hotReloadEnabled) {
                startConfigWatcher(configPath);
            } else {
                logger.info("Hot reload is disabled by configuration.");
            }
        } catch (Exception e) {
            logger.error(e.getMessage());
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String exceptionAsString = sw.toString();
            logger.error(exceptionAsString);
        }
    }

    // --- Graceful Shutdown Hook 추가 ---
    private static void addShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running = false;
            if (configWatcherThread != null) {
                configWatcherThread.interrupt();
            }
            logger.info("Shutdown hook triggered. Shutting down proxy servers...");
            lifecycleManager.shutdownAll("shutdown hook");
            logger.info("All proxy servers shut down.");
        }, "ProxyShutdownHook"));
    }

    private static void startConfigWatcher(String configPath) {
        File configFile = new File(configPath);
        long initialLastModified = configFile.exists() ? configFile.lastModified() : 0L;

        configWatcherThread = new Thread(() -> watchConfigChanges(configPath, configFile, initialLastModified), "ProxyConfigWatcher");
        configWatcherThread.setDaemon(false);
        configWatcherThread.start();
        logger.info("Config watcher started for: {}", configFile.getAbsolutePath());
    }

    private static void watchConfigChanges(String configPath, File configFile, long lastModified) {
        long currentLastModified = lastModified;
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                if (!hotReloadEnabled) {
                    logger.info("Hot reload disabled. Config watcher is stopping.");
                    break;
                }

                long fileLastModified = configFile.exists() ? configFile.lastModified() : 0L;
                if (fileLastModified > 0L && fileLastModified != currentLastModified) {
                    logger.info("Configuration change detected. Reloading: {}", configFile.getAbsolutePath());
                    Thread.sleep(configReloadDebounceMillis);

                    ConfigLoadResult reloaded = configLoader.load(configPath);
                    if (reloaded == null) {
                        logger.warn("Configuration reload failed. Keeping current runtime configuration.");
                        currentLastModified = fileLastModified;
                    } else if (reloaded.validConfigs.isEmpty() && !reloaded.parsedConfigs.isEmpty()) {
                        logger.warn("Reloaded configuration has no valid proxy entries. Keeping current runtime configuration.");
                        currentLastModified = fileLastModified;
                    } else {
                        refreshGeoIpLocatorIfNeeded(reloaded.globalConfig);
                        applyRuntimeOptions(reloaded.globalConfig);
                        lifecycleManager.applyConfig(reloaded.validConfigs, inetAddressLocator, "config reload");
                        currentLastModified = fileLastModified;
                        logger.info("Configuration reload applied successfully. Active proxies: {}", reloaded.validConfigs.size());
                    }
                }

                Thread.sleep(configWatchIntervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                logger.error("Unexpected error in config watcher loop", e);
            }
        }
        logger.info("Config watcher stopped.");
    }

    private static boolean initializeGeoIpLocator(GlobalConfig globalConfig) {
        String configuredPath = normalizeGeoIpDbPath(globalConfig != null ? globalConfig.geoIpDbPath : null);
        try {
            inetAddressLocator = new InetAddressLocator(configuredPath);
            activeGeoIpDbPath = configuredPath;
            return true;
        } catch (IOException e) {
            logger.error("Failed to load GeoIP database: {}", configuredPath, e);
            return false;
        }
    }

    private static void refreshGeoIpLocatorIfNeeded(GlobalConfig globalConfig) {
        String configuredPath = normalizeGeoIpDbPath(globalConfig != null ? globalConfig.geoIpDbPath : null);
        if (configuredPath.equals(activeGeoIpDbPath)) {
            return;
        }

        try {
            InetAddressLocator newLocator = new InetAddressLocator(configuredPath);
            inetAddressLocator = newLocator;
            activeGeoIpDbPath = configuredPath;
            logger.info("GeoIP database path updated: {}", configuredPath);
        } catch (IOException e) {
            logger.error("Failed to reload GeoIP database from {}. Keeping previous database: {}", configuredPath, activeGeoIpDbPath, e);
        }
    }

    private static void applyRuntimeOptions(GlobalConfig globalConfig) {
        if (globalConfig == null) {
            hotReloadEnabled = true;
            configWatchIntervalMillis = 2000L;
            configReloadDebounceMillis = 500L;
            return;
        }

        hotReloadEnabled = globalConfig.hotReloadEnabled != null ? globalConfig.hotReloadEnabled : true;

        if (globalConfig.hotReloadWatchIntervalMillis != null && globalConfig.hotReloadWatchIntervalMillis > 0L) {
            configWatchIntervalMillis = globalConfig.hotReloadWatchIntervalMillis;
        } else {
            configWatchIntervalMillis = 2000L;
        }

        if (globalConfig.hotReloadDebounceMillis != null && globalConfig.hotReloadDebounceMillis >= 0L) {
            configReloadDebounceMillis = globalConfig.hotReloadDebounceMillis;
        } else {
            configReloadDebounceMillis = 500L;
        }

        logger.info(
            "Runtime options applied: hotReloadEnabled={}, hotReloadWatchIntervalMillis={}, hotReloadDebounceMillis={}",
            hotReloadEnabled, configWatchIntervalMillis, configReloadDebounceMillis
        );
    }

    private static String normalizeGeoIpDbPath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return DEFAULT_GEO_IP_DB_PATH;
        }
        return path.trim();
    }
}
