package com.namejm.proxy;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

class ProxyConfigLoader {
    private final Logger logger;

    ProxyConfigLoader(Logger logger) {
        this.logger = logger;
    }

    ConfigLoadResult load(String configPath) {
        Map<String, Object> rawConfig = loadRawConfig(configPath);
        if (rawConfig == null) {
            return null;
        }

        GlobalConfig globalConfig = parseGlobalConfig(rawConfig);
        ParseResult parseResult = parseProxyConfig(rawConfig, globalConfig);
        if (parseResult == null || parseResult.parsedConfigs == null) {
            return null;
        }

        List<ProxyDto> validConfigs = new ArrayList<>();
        boolean hasInvalidProxy = parseResult.hasParseError;
        for (ProxyDto proxyConfig : parseResult.parsedConfigs) {
            logger.info("Processing proxy config: {}", proxyConfig);
            if (!isValidConfig(proxyConfig)) {
                logger.warn("Skipping invalid proxy configuration: {}", proxyConfig.getName());
                hasInvalidProxy = true;
                continue;
            }
            validConfigs.add(proxyConfig);
        }

        if (hasInvalidProxy) {
            logger.warn("Invalid proxy entries detected. Applying only valid entries (partial apply enabled).");
        }

        return new ConfigLoadResult(parseResult.parsedConfigs, validConfigs, globalConfig);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadRawConfig(String configPath) {
        Yaml yaml = new Yaml();
        try (FileReader reader = new FileReader(configPath)) {
            Object loaded = yaml.load(reader);
            if (!(loaded instanceof Map)) {
                logger.error("Top-level YAML structure must be a map. Path: {}", configPath);
                return null;
            }
            return (Map<String, Object>) loaded;
        } catch (FileNotFoundException e) {
            logger.error("Configuration file not found at path: {}", configPath, e);
        } catch (IOException e) {
            logger.error("Failed to read configuration file: {}", configPath, e);
        } catch (Exception e) {
            logger.error("Failed to parse YAML configuration: {}", configPath, e);
        }
        return null;
    }

    private ParseResult parseProxyConfig(Map<String, Object> rawConfig, GlobalConfig globalConfig) {
        List<ProxyDto> proxyList = new ArrayList<>();
        boolean hasParseError = false;
        Object proxyObj = rawConfig.get("proxy");

        if (!(proxyObj instanceof List)) {
            logger.error("'proxy' configuration should be a list.");
            return null;
        }

        List<?> rawProxyList = (List<?>) proxyObj;

        for (int i = 0; i < rawProxyList.size(); i++) {
            Object item = rawProxyList.get(i);
            if (!(item instanceof Map)) {
                logger.warn("Item at index {} in 'proxy' list is not a map, skipping.", i);
                hasParseError = true;
                continue;
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> itemMap = (Map<String, Object>) item;
                ProxyDto proxyDto = mapToProxyDto(itemMap);

                if (proxyDto != null) {
                    applyGlobalConfig(proxyDto, globalConfig);
                    proxyList.add(proxyDto);
                } else {
                    logger.warn("Failed to parse proxy configuration at index {}, skipping.", i);
                    hasParseError = true;
                }
            } catch (Exception e) {
                logger.error("Error parsing proxy configuration at index {}: {}", i, e.getMessage(), e);
                hasParseError = true;
            }
        }
        return new ParseResult(proxyList, hasParseError);
    }

    private ProxyDto mapToProxyDto(Map<String, Object> itemMap) {
        ProxyDto dto = new ProxyDto();
        dto.setType(parseString(itemMap.get("type")));
        dto.setName(parseString(itemMap.get("name")));
        String proxyName = dto.getName() != null ? dto.getName() : "unknown";
        boolean[] hasMappingError = new boolean[] {false};

        dto.setBindPort(parseInt(itemMap.get("bindPort"), "proxy[" + proxyName + "].bindPort", hasMappingError));
        dto.setForwardHost(parseString(itemMap.get("forwardHost")));
        dto.setForwardPort(parseInt(itemMap.get("forwardPort"), "proxy[" + proxyName + "].forwardPort", hasMappingError));
        dto.setAllowedCountries(parseStringList(itemMap.get("allowedCountries")));
        dto.setLb(parseLbTargets(itemMap.get("lb"), proxyName, hasMappingError));
        dto.setLbHealthCheckIntervalSeconds(parseInt(itemMap.get("lbHealthCheckIntervalSeconds"), "proxy[" + proxyName + "].lbHealthCheckIntervalSeconds", hasMappingError));
        dto.setTransferTimeoutSeconds(parseInt(itemMap.get("transferTimeoutSeconds"), "proxy[" + proxyName + "].transferTimeoutSeconds", hasMappingError));
        dto.setClientSoTimeoutMillis(parseInt(itemMap.get("clientSoTimeoutMillis"), "proxy[" + proxyName + "].clientSoTimeoutMillis", hasMappingError));
        dto.setForwardConnectTimeoutMillis(parseInt(itemMap.get("forwardConnectTimeoutMillis"), "proxy[" + proxyName + "].forwardConnectTimeoutMillis", hasMappingError));
        dto.setForwardSoTimeoutMillis(parseInt(itemMap.get("forwardSoTimeoutMillis"), "proxy[" + proxyName + "].forwardSoTimeoutMillis", hasMappingError));
        dto.setHealthCheckConnectTimeoutMillis(parseInt(itemMap.get("healthCheckConnectTimeoutMillis"), "proxy[" + proxyName + "].healthCheckConnectTimeoutMillis", hasMappingError));
        dto.setHealthCheckInitialDelaySeconds(parseInt(itemMap.get("healthCheckInitialDelaySeconds"), "proxy[" + proxyName + "].healthCheckInitialDelaySeconds", hasMappingError));
        dto.setExecutorCorePoolSize(parseInt(itemMap.get("executorCorePoolSize"), "proxy[" + proxyName + "].executorCorePoolSize", hasMappingError));
        dto.setExecutorMaxPoolSize(parseInt(itemMap.get("executorMaxPoolSize"), "proxy[" + proxyName + "].executorMaxPoolSize", hasMappingError));
        dto.setExecutorKeepAliveSeconds(parseInt(itemMap.get("executorKeepAliveSeconds"), "proxy[" + proxyName + "].executorKeepAliveSeconds", hasMappingError));
        dto.setExecutorQueueCapacity(parseInt(itemMap.get("executorQueueCapacity"), "proxy[" + proxyName + "].executorQueueCapacity", hasMappingError));
        dto.setShutdownAwaitSeconds(parseInt(itemMap.get("shutdownAwaitSeconds"), "proxy[" + proxyName + "].shutdownAwaitSeconds", hasMappingError));
        dto.setHealthFailThreshold(parseInt(itemMap.get("healthFailThreshold"), "proxy[" + proxyName + "].healthFailThreshold", hasMappingError));
        dto.setHealthSuccessThreshold(parseInt(itemMap.get("healthSuccessThreshold"), "proxy[" + proxyName + "].healthSuccessThreshold", hasMappingError));
        dto.setLbStrategy(parseString(itemMap.get("lbStrategy")));
        dto.setHalfCloseLingerSeconds(parseInt(itemMap.get("halfCloseLingerSeconds"), "proxy[" + proxyName + "].halfCloseLingerSeconds", hasMappingError));
        dto.setMaxActiveRelays(parseInt(itemMap.get("maxActiveRelays"), "proxy[" + proxyName + "].maxActiveRelays", hasMappingError));
        if (hasMappingError[0]) {
            return null;
        }
        return dto;
    }

    private String parseString(Object value) {
        if (value == null) {
            return null;
        }
        return String.valueOf(value).trim();
    }

    private int parseInt(Object value, String fieldName, boolean[] hasMappingError) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException e) {
                logger.error("Invalid integer value '{}' for {}.", value, fieldName);
                hasMappingError[0] = true;
                return 0;
            }
        }
        logger.error("Invalid integer type '{}' for {}.", value.getClass().getName(), fieldName);
        hasMappingError[0] = true;
        return 0;
    }

    private List<String> parseStringList(Object value) {
        if (!(value instanceof List)) {
            return Collections.emptyList();
        }
        List<?> raw = (List<?>) value;
        List<String> out = new ArrayList<>(raw.size());
        for (Object item : raw) {
            if (item == null) {
                continue;
            }
            out.add(String.valueOf(item));
        }
        return out;
    }

    private List<ProxyDto.LbTarget> parseLbTargets(Object value, String proxyName, boolean[] hasMappingError) {
        if (!(value instanceof List)) {
            if (value != null) {
                logger.error("Invalid type '{}' for proxy[{}].lb. Expected list.", value.getClass().getName(), proxyName);
                hasMappingError[0] = true;
            }
            return Collections.emptyList();
        }
        List<?> raw = (List<?>) value;
        List<ProxyDto.LbTarget> targets = new ArrayList<>(raw.size());
        for (Object item : raw) {
            if (!(item instanceof Map)) {
                logger.error("Invalid lb entry for proxy[{}]: expected map but got '{}'.", proxyName,
                    item == null ? "null" : item.getClass().getName());
                hasMappingError[0] = true;
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> lbMap = (Map<String, Object>) item;
            ProxyDto.LbTarget target = new ProxyDto.LbTarget();
            target.setName(parseString(lbMap.get("name")));
            target.setForwardHost(parseString(lbMap.get("forwardHost")));
            target.setForwardPort(parseInt(lbMap.get("forwardPort"), "proxy[" + proxyName + "].lb.forwardPort", hasMappingError));
            targets.add(target);
        }
        return targets;
    }

    private GlobalConfig parseGlobalConfig(Map<String, Object> rawConfig) {
        GlobalConfig globalConfig = new GlobalConfig();
        Object globalObj = rawConfig.get("global");
        if (!(globalObj instanceof Map)) {
            return globalConfig;
        }
        Map<?, ?> globalMap = (Map<?, ?>) globalObj;

        globalConfig.executorCorePoolSize = parseInteger(globalMap.get("executorCorePoolSize"), "global.executorCorePoolSize");
        globalConfig.executorMaxPoolSize = parseInteger(globalMap.get("executorMaxPoolSize"), "global.executorMaxPoolSize");
        globalConfig.executorKeepAliveSeconds = parseInteger(globalMap.get("executorKeepAliveSeconds"), "global.executorKeepAliveSeconds");
        globalConfig.executorQueueCapacity = parseInteger(globalMap.get("executorQueueCapacity"), "global.executorQueueCapacity");
        globalConfig.shutdownAwaitSeconds = parseInteger(globalMap.get("shutdownAwaitSeconds"), "global.shutdownAwaitSeconds");
        Object geoIpDbPath = globalMap.get("geoIpDbPath");
        globalConfig.geoIpDbPath = geoIpDbPath != null ? String.valueOf(geoIpDbPath).trim() : null;
        globalConfig.hotReloadEnabled = parseBoolean(globalMap.get("hotReloadEnabled"), "global.hotReloadEnabled");
        globalConfig.hotReloadWatchIntervalMillis = parseLong(globalMap.get("hotReloadWatchIntervalMillis"), "global.hotReloadWatchIntervalMillis");
        globalConfig.hotReloadDebounceMillis = parseLong(globalMap.get("hotReloadDebounceMillis"), "global.hotReloadDebounceMillis");

        return globalConfig;
    }

    private Integer parseInteger(Object value, String fieldName) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            String text = (String) value;
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException e) {
                logger.error("Invalid integer value '{}' for {}", value, fieldName);
                return null;
            }
        }
        logger.error("Invalid value type '{}' for {}. Expected integer.", value.getClass().getName(), fieldName);
        return null;
    }

    private Long parseLong(Object value, String fieldName) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            String text = (String) value;
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                logger.error("Invalid long value '{}' for {}", value, fieldName);
                return null;
            }
        }
        logger.error("Invalid value type '{}' for {}. Expected long.", value.getClass().getName(), fieldName);
        return null;
    }

    private Boolean parseBoolean(Object value, String fieldName) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            String normalized = ((String) value).trim().toLowerCase();
            if ("true".equals(normalized) || "y".equals(normalized) || "yes".equals(normalized) || "1".equals(normalized)) {
                return true;
            }
            if ("false".equals(normalized) || "n".equals(normalized) || "no".equals(normalized) || "0".equals(normalized)) {
                return false;
            }
            logger.error("Invalid boolean value '{}' for {}", value, fieldName);
            return null;
        }
        if (value instanceof Number) {
            int n = ((Number) value).intValue();
            if (n == 0) {
                return false;
            }
            if (n == 1) {
                return true;
            }
            logger.error("Invalid numeric boolean value '{}' for {}. Use 0 or 1.", value, fieldName);
            return null;
        }
        logger.error("Invalid value type '{}' for {}. Expected boolean.", value.getClass().getName(), fieldName);
        return null;
    }

    private void applyGlobalConfig(ProxyDto proxyDto, GlobalConfig globalConfig) {
        if (proxyDto.getExecutorCorePoolSize() == 0 && globalConfig.executorCorePoolSize != null) {
            proxyDto.setExecutorCorePoolSize(globalConfig.executorCorePoolSize);
        }
        if (proxyDto.getExecutorMaxPoolSize() == 0 && globalConfig.executorMaxPoolSize != null) {
            proxyDto.setExecutorMaxPoolSize(globalConfig.executorMaxPoolSize);
        }
        if (proxyDto.getExecutorKeepAliveSeconds() == 0 && globalConfig.executorKeepAliveSeconds != null) {
            proxyDto.setExecutorKeepAliveSeconds(globalConfig.executorKeepAliveSeconds);
        }
        if (proxyDto.getExecutorQueueCapacity() == 0 && globalConfig.executorQueueCapacity != null) {
            proxyDto.setExecutorQueueCapacity(globalConfig.executorQueueCapacity);
        }
        if (proxyDto.getShutdownAwaitSeconds() == 0 && globalConfig.shutdownAwaitSeconds != null) {
            proxyDto.setShutdownAwaitSeconds(globalConfig.shutdownAwaitSeconds);
        }
    }

    private boolean isValidConfig(ProxyDto proxyDto) {
        if (proxyDto == null) {
            return false;
        }
        boolean valid = proxyDto.isValid();
        if (!valid) {
            logger.error(
                "Proxy configuration validation failed for: {}",
                proxyDto.getName() != null ? proxyDto.getName() : "Unnamed Proxy"
            );
        }
        return valid;
    }

    private static class ParseResult {
        private final List<ProxyDto> parsedConfigs;
        private final boolean hasParseError;

        private ParseResult(List<ProxyDto> parsedConfigs, boolean hasParseError) {
            this.parsedConfigs = parsedConfigs;
            this.hasParseError = hasParseError;
        }
    }
}
