package com.namejm.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Lombok의 @Data 또는 @Getter/@Setter/@ToString 등을 사용하면 더 간결해짐
public class ProxyDto {
    private static final Logger logger = LoggerFactory.getLogger(ProxyDto.class);
    private static final Set<String> VALID_TYPES = new HashSet<>(Arrays.asList("in"));

    private String type;
    private String name;
    private int bindPort;
    private String forwardHost;
    private int forwardPort;
    private List<String> allowedCountries;

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
                                                 .toList();
        } else {
            this.allowedCountries = List.of();
        }
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
            logger.error("Invalid proxy type '{}' for '{}'. Only 'in' is currently supported.", type, name);
            valid = false;
        }
        if (bindPort <= 0 || bindPort > 65535) {
            logger.error("Invalid bindPort '{}' for proxy '{}'. Port must be between 1 and 65535.", bindPort, name);
            valid = false;
        }
        if (forwardHost == null || forwardHost.trim().isEmpty()) {
            logger.error("forwardHost is missing or empty for proxy '{}'.", name);
            valid = false;
        }
        if (forwardPort <= 0 || forwardPort > 65535) {
            logger.error("Invalid forwardPort '{}' for proxy '{}'. Port must be between 1 and 65535.", forwardPort, name);
            valid = false;
        }
        if (allowedCountries == null || allowedCountries.isEmpty()) {
            logger.warn("allowedCountries is empty for proxy '{}'. No connections will be allowed unless 'any' is added.", name);
        } else {
            for (String country : allowedCountries) {
                if("Private".equalsIgnoreCase(country) || "localhost".equalsIgnoreCase(country) || "Any".equalsIgnoreCase(country)) {
                    valid = true;
                    break;
                }
                if (country == null || country.isEmpty()) {
                    logger.error("Empty value found in allowedCountries for proxy '{}'.", name);
                    valid = false;
                    break;
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
