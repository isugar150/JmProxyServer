package com.namejm.proxy;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.Socket;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

public class ConnectionPolicy {
    private static final int COUNTRY_CACHE_MAX_SIZE = 10_000;

    private final ProxyDto config;
    private final InetAddressLocator inetAddressLocator;
    private final ConcurrentHashMap<String, String> countryCodeCache = new ConcurrentHashMap<>();

    public static class PolicyDecision {
        private final boolean allowed;
        private final String remoteAddr;
        private final int remotePort;
        private final String countryCode;

        public PolicyDecision(boolean allowed, String remoteAddr, int remotePort, String countryCode) {
            this.allowed = allowed;
            this.remoteAddr = remoteAddr;
            this.remotePort = remotePort;
            this.countryCode = countryCode;
        }

        public boolean isAllowed() {
            return allowed;
        }

        public String getRemoteAddr() {
            return remoteAddr;
        }

        public int getRemotePort() {
            return remotePort;
        }

        public String getCountryCode() {
            return countryCode;
        }
    }

    public ConnectionPolicy(ProxyDto config, InetAddressLocator inetAddressLocator) {
        this.config = config;
        this.inetAddressLocator = inetAddressLocator;
    }

    public boolean isAllowed(Socket clientSocket) {
        return evaluate(clientSocket).isAllowed();
    }

    public PolicyDecision evaluate(Socket clientSocket) {
        List<String> allowedConditions = config.getAllowedCountries();
        InetAddress remoteInet = clientSocket.getInetAddress();
        String remoteAddr = remoteInet != null ? remoteInet.getHostAddress() : "UNKNOWN";
        int remotePort = clientSocket.getPort();

        if (allowedConditions == null || allowedConditions.isEmpty()) {
            return new PolicyDecision(config.isOutbound(), remoteAddr, remotePort, "UNKNOWN");
        }

        boolean loopback = remoteInet != null && remoteInet.isLoopbackAddress();
        boolean privateIp = remoteInet != null && isPrivateIP(remoteInet, remoteAddr);
        boolean needsCountryLookup = false;

        for (String condition : allowedConditions) {
            if (condition != null
                && !"any".equals(condition)
                && !"localhost".equals(condition)
                && !"private".equals(condition)) {
                needsCountryLookup = true;
                break;
            }
        }
        String country = needsCountryLookup ? resolveCountryCode(remoteAddr) : "UNKNOWN";

        for (String allowedCondition : allowedConditions) {
            if (allowedCondition == null) {
                continue;
            }

            switch (allowedCondition) {
                case "any":
                    return new PolicyDecision(true, remoteAddr, remotePort, country);
                case "localhost":
                    if (loopback) {
                        return new PolicyDecision(true, remoteAddr, remotePort, country);
                    }
                    break;
                case "private":
                    if (privateIp) {
                        return new PolicyDecision(true, remoteAddr, remotePort, country);
                    }
                    break;
                default:
                    if (!"UNKNOWN".equals(country) && allowedCondition.equalsIgnoreCase(country)) {
                        return new PolicyDecision(true, remoteAddr, remotePort, country);
                    }
            }
        }
        return new PolicyDecision(false, remoteAddr, remotePort, country);
    }

    public String resolveCountryCode(String ipAddress) {
        if (ipAddress == null || ipAddress.isEmpty() || "UNKNOWN".equals(ipAddress)) {
            return "UNKNOWN";
        }

        String cached = countryCodeCache.get(ipAddress);
        if (cached != null) {
            return cached;
        }

        try {
            Locale locale = inetAddressLocator.getLocale(ipAddress);
            String country = locale.getCountry();
            if (country == null || country.isEmpty()) {
                country = "UNKNOWN";
            }

            if (countryCodeCache.size() >= COUNTRY_CACHE_MAX_SIZE) {
                countryCodeCache.clear();
            }
            countryCodeCache.put(ipAddress, country);
            return country;
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    private boolean isPrivateIP(InetAddress inetAddress, String ipAddress) {
        try {
            if (inetAddress.isLoopbackAddress() || inetAddress.isSiteLocalAddress()) {
                return true;
            }

            if (inetAddress instanceof Inet6Address) {
                byte[] address = inetAddress.getAddress();
                return (address[0] & (byte) 0xFE) == (byte) 0xFC;
            }

            if (!ipAddress.contains(".")) {
                return false;
            }

            String[] octets = ipAddress.split("\\.");
            if (octets.length != 4) {
                return false;
            }
            int first = Integer.parseInt(octets[0]);
            int second = Integer.parseInt(octets[1]);

            return first == 10 || (first == 172 && second >= 16 && second <= 31) || (first == 192 && second == 168);
        } catch (Exception e) {
            return false;
        }
    }
}
