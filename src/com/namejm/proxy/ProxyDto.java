package com.namejm.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// Lombok의 @Data 또는 @Getter/@Setter/@ToString 등을 사용하면 더 간결해짐
public class ProxyDto {
    private static final Logger logger = LoggerFactory.getLogger(ProxyDto.class);
    private static final Set<String> VALID_TYPES = new HashSet<>(Arrays.asList("in", "out"));

    private String type;
    private String name;
    private int bindPort;
    private String forwardHost;
    private int forwardPort;
    private List<String> allowedCountries;
    private List<LbTarget> lb;
    private int lbHealthCheckIntervalSeconds;
    private int transferTimeoutSeconds;
    private int clientSoTimeoutMillis;
    private int forwardConnectTimeoutMillis;
    private int forwardSoTimeoutMillis;
    private int healthCheckConnectTimeoutMillis;
    private int healthCheckInitialDelaySeconds;
    private int executorCorePoolSize;
    private int executorMaxPoolSize;
    private int executorKeepAliveSeconds;
    private int executorQueueCapacity;
    private int shutdownAwaitSeconds;
    private int healthFailThreshold;
    private int healthSuccessThreshold;

    public static class LbTarget {
        private String name;
        private String forwardHost;
        private int forwardPort;

        public LbTarget() {}

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getForwardHost() { return forwardHost; }
        public void setForwardHost(String forwardHost) { this.forwardHost = forwardHost; }
        public int getForwardPort() { return forwardPort; }
        public void setForwardPort(int forwardPort) { this.forwardPort = forwardPort; }

        @Override
        public String toString() {
            return "LbTarget{" +
                   "name='" + name + '\'' +
                   ", forwardHost='" + forwardHost + '\'' +
                   ", forwardPort=" + forwardPort +
                   '}';
        }
    }

    public ProxyDto() {}

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getBindPort() { return bindPort; }
    public void setBindPort(int bindPort) { this.bindPort = bindPort; }
    public String getForwardHost() { return forwardHost; }
    public void setForwardHost(String forwardHost) { this.forwardHost = forwardHost; }
    public int getForwardPort() { return forwardPort; }
    public void setForwardPort(int forwardPort) { this.forwardPort = forwardPort; }
    public List<String> getAllowedCountries() { return allowedCountries; }
    public void setAllowedCountries(List<String> allowedCountries) {
        if (allowedCountries != null) {
            this.allowedCountries = allowedCountries.stream()
                                                 .map(String::toLowerCase)
                                                 .map(String::trim)
                                                 .collect(Collectors.toList());
        } else {
            this.allowedCountries = List.of();
        }
    }
    public List<LbTarget> getLb() { return lb; }
    public void setLb(List<LbTarget> lb) {
        this.lb = lb == null ? List.of() : lb;
    }
    public int getLbHealthCheckIntervalSeconds() { return lbHealthCheckIntervalSeconds; }
    public void setLbHealthCheckIntervalSeconds(int lbHealthCheckIntervalSeconds) {
        this.lbHealthCheckIntervalSeconds = lbHealthCheckIntervalSeconds;
    }
    public int getTransferTimeoutSeconds() { return transferTimeoutSeconds; }
    public void setTransferTimeoutSeconds(int transferTimeoutSeconds) {
        this.transferTimeoutSeconds = transferTimeoutSeconds;
    }
    public int getClientSoTimeoutMillis() { return clientSoTimeoutMillis; }
    public void setClientSoTimeoutMillis(int clientSoTimeoutMillis) { this.clientSoTimeoutMillis = clientSoTimeoutMillis; }
    public int getForwardConnectTimeoutMillis() { return forwardConnectTimeoutMillis; }
    public void setForwardConnectTimeoutMillis(int forwardConnectTimeoutMillis) { this.forwardConnectTimeoutMillis = forwardConnectTimeoutMillis; }
    public int getForwardSoTimeoutMillis() { return forwardSoTimeoutMillis; }
    public void setForwardSoTimeoutMillis(int forwardSoTimeoutMillis) { this.forwardSoTimeoutMillis = forwardSoTimeoutMillis; }
    public int getHealthCheckConnectTimeoutMillis() { return healthCheckConnectTimeoutMillis; }
    public void setHealthCheckConnectTimeoutMillis(int healthCheckConnectTimeoutMillis) { this.healthCheckConnectTimeoutMillis = healthCheckConnectTimeoutMillis; }
    public int getHealthCheckInitialDelaySeconds() { return healthCheckInitialDelaySeconds; }
    public void setHealthCheckInitialDelaySeconds(int healthCheckInitialDelaySeconds) { this.healthCheckInitialDelaySeconds = healthCheckInitialDelaySeconds; }
    public int getExecutorCorePoolSize() { return executorCorePoolSize; }
    public void setExecutorCorePoolSize(int executorCorePoolSize) { this.executorCorePoolSize = executorCorePoolSize; }
    public int getExecutorMaxPoolSize() { return executorMaxPoolSize; }
    public void setExecutorMaxPoolSize(int executorMaxPoolSize) { this.executorMaxPoolSize = executorMaxPoolSize; }
    public int getExecutorKeepAliveSeconds() { return executorKeepAliveSeconds; }
    public void setExecutorKeepAliveSeconds(int executorKeepAliveSeconds) { this.executorKeepAliveSeconds = executorKeepAliveSeconds; }
    public int getExecutorQueueCapacity() { return executorQueueCapacity; }
    public void setExecutorQueueCapacity(int executorQueueCapacity) { this.executorQueueCapacity = executorQueueCapacity; }
    public int getShutdownAwaitSeconds() { return shutdownAwaitSeconds; }
    public void setShutdownAwaitSeconds(int shutdownAwaitSeconds) { this.shutdownAwaitSeconds = shutdownAwaitSeconds; }
    public int getHealthFailThreshold() { return healthFailThreshold; }
    public void setHealthFailThreshold(int healthFailThreshold) { this.healthFailThreshold = healthFailThreshold; }
    public int getHealthSuccessThreshold() { return healthSuccessThreshold; }
    public void setHealthSuccessThreshold(int healthSuccessThreshold) { this.healthSuccessThreshold = healthSuccessThreshold; }

    public boolean isInbound() {
        return "in".equalsIgnoreCase(type);
    }

    public boolean isOutbound() {
        return "out".equalsIgnoreCase(type);
    }

    public boolean hasLbTargets() {
        return lb != null && !lb.isEmpty();
    }

    public int getLbHealthCheckIntervalSecondsOrDefault() {
        return lbHealthCheckIntervalSeconds > 0 ? lbHealthCheckIntervalSeconds : 10;
    }

    public int getTransferTimeoutSecondsOrDefault() {
        return transferTimeoutSeconds >= 0 ? transferTimeoutSeconds : 0;
    }

    public int getClientSoTimeoutMillisOrDefault() {
        return clientSoTimeoutMillis > 0 ? clientSoTimeoutMillis : 0;
    }

    public int getForwardConnectTimeoutMillisOrDefault() {
        return forwardConnectTimeoutMillis > 0 ? forwardConnectTimeoutMillis : 5000;
    }

    public int getForwardSoTimeoutMillisOrDefault() {
        return forwardSoTimeoutMillis > 0 ? forwardSoTimeoutMillis : 0;
    }

    public int getHealthCheckConnectTimeoutMillisOrDefault() {
        return healthCheckConnectTimeoutMillis > 0 ? healthCheckConnectTimeoutMillis : 2000;
    }

    public int getHealthCheckInitialDelaySecondsOrDefault() {
        return healthCheckInitialDelaySeconds >= 0 ? healthCheckInitialDelaySeconds : 1;
    }

    public int getExecutorCorePoolSizeOrDefault() {
        return executorCorePoolSize > 0 ? executorCorePoolSize : Runtime.getRuntime().availableProcessors();
    }

    public int getExecutorMaxPoolSizeOrDefault(int corePoolSize) {
        if (executorMaxPoolSize <= 0) {
            return corePoolSize * 2;
        }
        return Math.max(executorMaxPoolSize, corePoolSize);
    }

    public int getExecutorKeepAliveSecondsOrDefault() {
        return executorKeepAliveSeconds > 0 ? executorKeepAliveSeconds : 60;
    }

    public int getExecutorQueueCapacityOrDefault() {
        return executorQueueCapacity > 0 ? executorQueueCapacity : 500;
    }

    public int getShutdownAwaitSecondsOrDefault() {
        return shutdownAwaitSeconds > 0 ? shutdownAwaitSeconds : 10;
    }

    public int getHealthFailThresholdOrDefault() {
        return healthFailThreshold > 0 ? healthFailThreshold : 3;
    }

    public int getHealthSuccessThresholdOrDefault() {
        return healthSuccessThreshold > 0 ? healthSuccessThreshold : 2;
    }

    @Override
    public String toString() {
        return "ProxyDto{" +
               "type='" + type + '\'' +
               ", name='" + name + '\'' +
               ", bindPort=" + bindPort +
               ", forwardHost='" + forwardHost + '\'' +
               ", forwardPort=" + forwardPort +
               ", allowedCountries=" + allowedCountries +
               ", lbHealthCheckIntervalSeconds=" + lbHealthCheckIntervalSeconds +
               ", transferTimeoutSeconds=" + transferTimeoutSeconds +
               ", clientSoTimeoutMillis=" + clientSoTimeoutMillis +
               ", forwardConnectTimeoutMillis=" + forwardConnectTimeoutMillis +
               ", forwardSoTimeoutMillis=" + forwardSoTimeoutMillis +
               ", healthCheckConnectTimeoutMillis=" + healthCheckConnectTimeoutMillis +
               ", healthCheckInitialDelaySeconds=" + healthCheckInitialDelaySeconds +
               ", executorCorePoolSize=" + executorCorePoolSize +
               ", executorMaxPoolSize=" + executorMaxPoolSize +
               ", executorKeepAliveSeconds=" + executorKeepAliveSeconds +
               ", executorQueueCapacity=" + executorQueueCapacity +
               ", shutdownAwaitSeconds=" + shutdownAwaitSeconds +
               ", healthFailThreshold=" + healthFailThreshold +
               ", healthSuccessThreshold=" + healthSuccessThreshold +
               ", lb=" + lb +
               '}';
    }

    /**
     * 설정 값의 유효성을 검사하는 메서드.
     * @return 설정이 유효하면 true, 그렇지 않으면 false.
     */
    public boolean isValid() {
        boolean valid = true;
        if (name == null || name.trim().isEmpty()) {
            logger.error("Proxy name is missing or empty.");
            valid = false;
        }
        if (!VALID_TYPES.contains(type != null ? type.toLowerCase() : "")) {
            logger.error("Invalid proxy type '{}' for '{}'. Only 'in' or 'out' is supported.", type, name);
            valid = false;
        }
        if (bindPort <= 0 || bindPort > 65535) {
            logger.error("Invalid bindPort '{}' for proxy '{}'. Port must be between 1 and 65535.", bindPort, name);
            valid = false;
        }
        if (lbHealthCheckIntervalSeconds < 0) {
            logger.error("Invalid lbHealthCheckIntervalSeconds '{}' for proxy '{}'. Must be 0 or greater.", lbHealthCheckIntervalSeconds, name);
            valid = false;
        }
        if (transferTimeoutSeconds < 0) {
            logger.error("Invalid transferTimeoutSeconds '{}' for proxy '{}'. Must be 0 or greater.", transferTimeoutSeconds, name);
            valid = false;
        }
        if (clientSoTimeoutMillis < 0) {
            logger.error("Invalid clientSoTimeoutMillis '{}' for proxy '{}'. Must be 0 or greater.", clientSoTimeoutMillis, name);
            valid = false;
        }
        if (forwardConnectTimeoutMillis < 0) {
            logger.error("Invalid forwardConnectTimeoutMillis '{}' for proxy '{}'. Must be 0 or greater.", forwardConnectTimeoutMillis, name);
            valid = false;
        }
        if (forwardSoTimeoutMillis < 0) {
            logger.error("Invalid forwardSoTimeoutMillis '{}' for proxy '{}'. Must be 0 or greater.", forwardSoTimeoutMillis, name);
            valid = false;
        }
        if (healthCheckConnectTimeoutMillis < 0) {
            logger.error("Invalid healthCheckConnectTimeoutMillis '{}' for proxy '{}'. Must be 0 or greater.", healthCheckConnectTimeoutMillis, name);
            valid = false;
        }
        if (healthCheckInitialDelaySeconds < 0) {
            logger.error("Invalid healthCheckInitialDelaySeconds '{}' for proxy '{}'. Must be 0 or greater.", healthCheckInitialDelaySeconds, name);
            valid = false;
        }
        if (executorCorePoolSize < 0) {
            logger.error("Invalid executorCorePoolSize '{}' for proxy '{}'. Must be 0 or greater.", executorCorePoolSize, name);
            valid = false;
        }
        if (executorMaxPoolSize < 0) {
            logger.error("Invalid executorMaxPoolSize '{}' for proxy '{}'. Must be 0 or greater.", executorMaxPoolSize, name);
            valid = false;
        }
        if (executorKeepAliveSeconds < 0) {
            logger.error("Invalid executorKeepAliveSeconds '{}' for proxy '{}'. Must be 0 or greater.", executorKeepAliveSeconds, name);
            valid = false;
        }
        if (executorQueueCapacity < 0) {
            logger.error("Invalid executorQueueCapacity '{}' for proxy '{}'. Must be 0 or greater.", executorQueueCapacity, name);
            valid = false;
        }
        if (shutdownAwaitSeconds < 0) {
            logger.error("Invalid shutdownAwaitSeconds '{}' for proxy '{}'. Must be 0 or greater.", shutdownAwaitSeconds, name);
            valid = false;
        }
        if (healthFailThreshold < 0) {
            logger.error("Invalid healthFailThreshold '{}' for proxy '{}'. Must be 0 or greater.", healthFailThreshold, name);
            valid = false;
        }
        if (healthSuccessThreshold < 0) {
            logger.error("Invalid healthSuccessThreshold '{}' for proxy '{}'. Must be 0 or greater.", healthSuccessThreshold, name);
            valid = false;
        }
        if (hasLbTargets()) {
            for (LbTarget target : lb) {
                if (target == null) {
                    logger.error("A null entry exists in lb for proxy '{}'.", name);
                    valid = false;
                    continue;
                }
                if (target.getName() == null || target.getName().trim().isEmpty()) {
                    logger.error("lb target name is missing for proxy '{}'. Example: lb1, lb2.", name);
                    valid = false;
                }
                if (target.getForwardHost() == null || target.getForwardHost().trim().isEmpty()) {
                    logger.error("lb target host is missing for proxy '{}', target '{}'.", name, target.getName());
                    valid = false;
                }
                if (target.getForwardPort() <= 0 || target.getForwardPort() > 65535) {
                    logger.error("Invalid lb target port '{}' for proxy '{}', target '{}'.",
                        target.getForwardPort(), name, target.getName());
                    valid = false;
                }
            }
        } else {
            if (forwardHost == null || forwardHost.trim().isEmpty()) {
                logger.error("forwardHost is missing or empty for proxy '{}'.", name);
                valid = false;
            }
            if (forwardPort <= 0 || forwardPort > 65535) {
                logger.error("Invalid forwardPort '{}' for proxy '{}'. Port must be between 1 and 65535.", forwardPort, name);
                valid = false;
            }
        }
        if (allowedCountries == null || allowedCountries.isEmpty()) {
            if (isInbound()) {
                logger.warn("allowedCountries is empty for inbound proxy '{}'. No connections will be allowed unless 'any' is added.", name);
            } else {
                logger.info("allowedCountries is empty for outbound proxy '{}'. All client sources are allowed.", name);
            }
        } else {
            for (String country : allowedCountries) {
                if (country == null || country.isEmpty()) {
                    logger.error("Empty value found in allowedCountries for proxy '{}'.", name);
                    valid = false;
                    break;
                }
                if("private".equalsIgnoreCase(country) || "localhost".equalsIgnoreCase(country) || "any".equalsIgnoreCase(country)) {
                    continue;
                }
                if (!country.matches("^[a-zA-Z]{2}$")) {
                    logger.error("Invalid entry '{}' in allowedCountries for proxy '{}'. Must be a 2-letter country code or a reserved word (any, localhost, private).", country, name);
                    valid = false;
                }
            }
        }

        return valid;
    }
}
