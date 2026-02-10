package com.namejm.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private static final AtomicBoolean reloadInProgress = new AtomicBoolean(false);
    private static final ExecutorService reloadExecutor = new ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>(1),
        r -> {
            Thread t = new Thread(r);
            t.setName("ProxyConfigReloadWorker");
            t.setDaemon(true);
            return t;
        },
        new ThreadPoolExecutor.DiscardOldestPolicy()
    );

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
                lifecycleManager.shutdownExecutor();
                System.exit(1);
                return;
            }
            if (initialLoad.validConfigs.isEmpty()) {
                logger.error("No valid proxy entries found in initial configuration. Startup is aborted.");
                lifecycleManager.shutdownExecutor();
                System.exit(1);
                return;
            }

            if (!initializeGeoIpLocator(initialLoad.globalConfig)) {
                lifecycleManager.shutdownExecutor();
                System.exit(1);
                return;
            }
            applyRuntimeOptions(initialLoad.globalConfig);
            lifecycleManager.applyConfig(initialLoad.validConfigs, inetAddressLocator, "initial load");
            if (lifecycleManager.getActiveProxyCount() == 0) {
                logger.error("No proxy instance could be started from the initial configuration. Startup is aborted.");
                closeGeoIpLocator();
                lifecycleManager.shutdownExecutor();
                System.exit(1);
                return;
            }
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
            lifecycleManager.shutdownExecutor();
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
            try {
                lifecycleManager.shutdownAll("shutdown hook");
            } catch (Exception e) {
                logger.error("Error during lifecycle shutdown", e);
            } finally {
                closeGeoIpLocator();
                reloadExecutor.shutdownNow();
                lifecycleManager.shutdownExecutor();
            }
            logger.info("All proxy servers shut down.");
        }, "ProxyShutdownHook"));
    }

    private static void startConfigWatcher(String configPath) {
        Path configFilePath = Paths.get(configPath).toAbsolutePath().normalize();
        configWatcherThread = new Thread(() -> watchConfigChanges(configFilePath), "ProxyConfigWatcher");
        configWatcherThread.setDaemon(false);
        configWatcherThread.start();
        logger.info("Config watcher started for: {}", configFilePath);
    }

    private static void watchConfigChanges(Path configFilePath) {
        Path directory = configFilePath.getParent();
        if (directory == null) {
            logger.error("Config watcher cannot start: parent directory not found for {}", configFilePath);
            return;
        }
        String fileName = configFilePath.getFileName().toString();

        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            directory.register(
                watchService,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE
            );

            while (running && !Thread.currentThread().isInterrupted()) {
                if (!hotReloadEnabled) {
                    logger.info("Hot reload disabled. Config watcher is stopping.");
                    break;
                }

                WatchKey key = watchService.poll(configWatchIntervalMillis, TimeUnit.MILLISECONDS);
                if (key == null) {
                    continue;
                }

                boolean configChanged = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }
                    Object context = event.context();
                    if (context instanceof Path) {
                        Path changed = (Path) context;
                        if (fileName.equals(changed.getFileName().toString())) {
                            configChanged = true;
                            break;
                        }
                    }
                }

                boolean valid = key.reset();
                if (!valid) {
                    logger.warn("Config watcher key became invalid. Stopping watcher.");
                    break;
                }

                if (!configChanged) {
                    continue;
                }

                logger.info("Configuration change detected. Reloading: {}", configFilePath);
                Thread.sleep(configReloadDebounceMillis);
                triggerAsyncReload(configFilePath);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("Unexpected error in config watcher loop", e);
        }

        logger.info("Config watcher stopped.");
    }

    private static void triggerAsyncReload(Path configFilePath) {
        try {
            reloadExecutor.submit(() -> reloadConfiguration(configFilePath));
        } catch (Exception e) {
            logger.warn("Reload task submission failed. Skipping this change event.", e);
        }
    }

    private static void reloadConfiguration(Path configFilePath) {
        if (!reloadInProgress.compareAndSet(false, true)) {
            logger.debug("Reload already in progress. Coalescing duplicate config change event.");
            return;
        }

        try {
            ConfigLoadResult reloaded = configLoader.load(configFilePath.toString());
            if (reloaded == null) {
                logger.warn("Configuration reload failed. Keeping current runtime configuration and retrying on next watch cycle.");
                return;
            }
            if (reloaded.validConfigs.isEmpty()) {
                logger.warn("Reloaded configuration has no valid proxy entries. Keeping current runtime configuration and retrying on next watch cycle.");
                return;
            }

            refreshGeoIpLocatorIfNeeded(reloaded.globalConfig);
            applyRuntimeOptions(reloaded.globalConfig);
            lifecycleManager.applyConfig(reloaded.validConfigs, inetAddressLocator, "config reload");
            logger.info("Configuration reload applied successfully. Active proxies: {}", reloaded.validConfigs.size());
        } catch (Exception e) {
            logger.error("Unexpected error during async configuration reload", e);
        } finally {
            reloadInProgress.set(false);
        }
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
            if (inetAddressLocator == null) {
                inetAddressLocator = new InetAddressLocator(configuredPath);
            } else {
                inetAddressLocator.reload(configuredPath);
            }
            activeGeoIpDbPath = configuredPath;
            logger.info("GeoIP database path updated: {}", configuredPath);
        } catch (IOException e) {
            logger.error("Failed to reload GeoIP database from {}. Keeping previous database: {}", configuredPath, activeGeoIpDbPath, e);
        }
    }

    private static void closeGeoIpLocator() {
        try {
            if (inetAddressLocator != null) {
                inetAddressLocator.close();
                inetAddressLocator = null;
            }
        } catch (Exception e) {
            logger.warn("Failed to close GeoIP locator", e);
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
