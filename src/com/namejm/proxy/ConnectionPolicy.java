package com.namejm.proxy;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ConnectionPolicy {
    private static final int COUNTRY_CACHE_MAX_SIZE = 10_000;
    private static final int COUNTRY_CACHE_SHARDS = 16;
    private static final int COUNTRY_CACHE_SHARD_MAX_SIZE = Math.max(1, COUNTRY_CACHE_MAX_SIZE / COUNTRY_CACHE_SHARDS);

    private final ProxyDto config;
    private final InetAddressLocator inetAddressLocator;
    private final List<Map<String, String>> countryCodeCaches = new ArrayList<>(COUNTRY_CACHE_SHARDS);
    private final Object[] countryCodeCacheLocks = new Object[COUNTRY_CACHE_SHARDS];

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
        for (int i = 0; i < COUNTRY_CACHE_SHARDS; i++) {
            countryCodeCacheLocks[i] = new Object();
            countryCodeCaches.add(new LinkedHashMap<String, String>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > COUNTRY_CACHE_SHARD_MAX_SIZE;
                }
            });
        }
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
        String country = needsCountryLookup ? resolveCountryCode(remoteInet, remoteAddr) : "UNKNOWN";

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

        try {
            InetAddress inetAddress = InetAddress.getByName(ipAddress);
            return resolveCountryCode(inetAddress, ipAddress);
        } catch (UnknownHostException e) {
            return "UNKNOWN";
        }
    }

    private String resolveCountryCode(InetAddress inetAddress, String cacheKey) {
        if (inetAddress == null || cacheKey == null || cacheKey.isEmpty() || "UNKNOWN".equals(cacheKey)) {
            return "UNKNOWN";
        }

        int shard = cacheShard(cacheKey);
        synchronized (countryCodeCacheLocks[shard]) {
            String cached = countryCodeCaches.get(shard).get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        try {
            String country = inetAddressLocator.getCountryCode(inetAddress);
            synchronized (countryCodeCacheLocks[shard]) {
                countryCodeCaches.get(shard).put(cacheKey, country);
            }
            return country;
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    private int cacheShard(String key) {
        return (key.hashCode() & Integer.MAX_VALUE) % COUNTRY_CACHE_SHARDS;
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
