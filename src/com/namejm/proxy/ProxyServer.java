package com.namejm.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ProxyServer {
    final private static Logger logger = LoggerFactory.getLogger(ProxyServer.class);
    private static List<ProxyDto> config = null;
    private static InetAddressLocator inetAddressLocator;
    private static final List<ProxyMain> proxyInstances = new ArrayList<>();
    public static void main(String args[]){
        System.out.println("       _           _____                      _____                          \n" +
                "      | |         |  __ \\                    / ____|                         \n" +
                "      | |_ __ ___ | |__) | __ _____  ___   _| (___   ___ _ ____   _____ _ __ \n" +
                "  _   | | '_ ` _ \\|  ___/ '__/ _ \\ \\/ / | | |\\___ \\ / _ \\ '__\\ \\ / / _ \\ '__|\n" +
                " | |__| | | | | | | |   | | | (_) >  <| |_| |____) |  __/ |   \\ V /  __/ |   \n" +
                "  \\____/|_| |_| |_|_|   |_|  \\___/_/\\_\\\\__, |_____/ \\___|_|    \\_/ \\___|_|   \n" +
                "                                        __/ |                                \n" +
                "                                       |___/                                 \n" +
                "Copyright © 2021 Jm's Corp All rights reserved.\n");

        // --- 설정 파일 경로 처리 ---
        String configPath = "./config/application.yml"; // 기본 경로
        if (args.length > 0) {
            configPath = args[0]; // 첫 번째 인자를 설정 파일 경로로 사용
            logger.info("Using configuration file from argument: {}", configPath);
        } else {
            logger.info("Using default configuration file: {}", configPath);
        }

        // --- GeoIP 데이터베이스 로드 ---
        String geoIpDbPath = "./config/GeoLite2-Country.mmdb"; // 기본 경로 또는 설정에서 읽기
        try {
            inetAddressLocator = new InetAddressLocator(geoIpDbPath);
        } catch (IOException e) {
            logger.error("Failed to load GeoIP database: {}", geoIpDbPath, e);
            System.exit(1); // DB 로드 실패 시 종료
            return;
        }

        try {
            Yaml yaml = new Yaml();
            Map<String, Object> rawConfig; // SnakeYAML이 Map으로 파싱하도록 변경
            try {
                rawConfig = yaml.load(new FileReader(configPath));
            } catch (FileNotFoundException e) {
                logger.error("Configuration file not found at path: {}", configPath, e);
                System.exit(1);
                return;
            }

            // --- 설정 파싱 ---
            config = parseProxyConfig(rawConfig);
            if (config == null) {
                logger.error("Failed to parse proxy configurations.");
                System.exit(1);
                return;
            }

            for (int i = 0; i < config.size(); i++) {
                final ProxyDto proxyConfig = config.get(i);

                logger.info("Processing proxy config [{}]: {}", i, proxyConfig);

                // 설정 유효성 검사
                if (!isValidConfig(proxyConfig)) {
                     logger.warn("Skipping invalid proxy configuration: {}", proxyConfig.getName());
                     continue;
                }

                logger.info("Creating startup thread for proxy: {}", proxyConfig.getName());

                // ProxyMain 인스턴스 생성 및 시작
//                Thread proxyThread = new Thread(() -> {
//                    try {
//                        logger.info("Thread started for proxy: {}. Creating ProxyMain instance...", proxyConfig.getName());
//                        ProxyMain proxyMain = new ProxyMain(proxyConfig, inetAddressLocator);
//                        proxyInstances.add(proxyMain);
//                        logger.info("Starting ProxyMain for proxy: {}", proxyConfig.getName());
//                        proxyMain.start();
//                        logger.info("ProxyMain started successfully for proxy: {}", proxyConfig.getName());
//                    } catch (IOException e) {
//                        logger.error("!!! Failed to start proxy '{}': {}", proxyConfig.getName(), e.getMessage(), e);
//                    } catch (Exception e) {
//                        logger.error("!!! Unexpected error during proxy startup '{}': {}", proxyConfig.getName(), e.getMessage(), e);
//                    }
//                });
//                proxyThread.setName("ProxyStarter-" + proxyConfig.getName());
//                proxyThread.start();



                try {
                    logger.info("Starting ProxyMain synchronously for proxy: {}", proxyConfig.getName());
                    ProxyMain proxyMain = new ProxyMain(proxyConfig, inetAddressLocator);
                    synchronized(proxyInstances) { // Shutdown Hook 에서 사용하므로 동기화 유지
                         proxyInstances.add(proxyMain);
                    }
                    proxyMain.start();
                    logger.info("ProxyMain started successfully for proxy: {}", proxyConfig.getName());
                } catch (IOException e) {
                    logger.error("!!! Failed to start proxy '{}' synchronously: {}", proxyConfig.getName(), e.getMessage(), e);
                } catch (Exception e) {
                    logger.error("!!! Unexpected error during synchronous proxy startup '{}': {}", proxyConfig.getName(), e.getMessage(), e);
                }
            }

            // --- Graceful Shutdown 설정 ---
            addShutdownHook();
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
            logger.info("Shutdown hook triggered. Shutting down proxy servers...");
            synchronized (proxyInstances) {
                for (ProxyMain proxy : proxyInstances) {
                    try {
                        logger.info("Shutting down proxy: {}", proxy.getConfig().getName());
                        proxy.shutdown();
                    } catch (Exception e) {
                        logger.error("Error shutting down proxy: {}",
                                     (proxy.getConfig() != null ? proxy.getConfig().getName() : "Unknown Proxy"), e);
                    }
                }
            }
            logger.info("All proxy servers shut down.");
        }, "ProxyShutdownHook"));
    }

    // 설정 파싱 메서드
    private static List<ProxyDto> parseProxyConfig(Map<String, Object> rawConfig) {
        List<ProxyDto> proxyList = new ArrayList<>();
        Object proxyObj = rawConfig.get("proxy");

        if (!(proxyObj instanceof List)) {
            logger.error("'proxy' configuration should be a list.");
            return null;
        }

        List<?> rawProxyList = (List<?>) proxyObj;
        Yaml mapYaml = new Yaml();

        for (int i = 0; i < rawProxyList.size(); i++) {
            Object item = rawProxyList.get(i);
            if (!(item instanceof Map)) {
                logger.warn("Item at index {} in 'proxy' list is not a map, skipping.", i);
                continue;
            }
            try {
                Yaml dtoYaml = new Yaml(new Constructor(ProxyDto.class));
                ProxyDto proxyDto = dtoYaml.load(mapYaml.dump(item));

                if (proxyDto != null) {
                     proxyList.add(proxyDto);
                } else {
                     logger.warn("Failed to parse proxy configuration at index {}, skipping.", i);
                }
            } catch (Exception e) {
                logger.error("Error parsing proxy configuration at index {}: {}", i, e.getMessage(), e);
            }
        }
        return proxyList;
    }

    // 설정 유효성 검사 메서드
    private static boolean isValidConfig(ProxyDto proxyDto) {
        if (proxyDto == null) return false;
        boolean valid = proxyDto.isValid();
        if (!valid) {
            logger.error("Proxy configuration validation failed for: {}", proxyDto.getName() != null ? proxyDto.getName() : "Unnamed Proxy");
        }
        return valid;
    }
}
