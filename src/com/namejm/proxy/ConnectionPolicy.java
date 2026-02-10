package com.namejm.proxy;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.Socket;
import java.util.List;
import java.util.Locale;

public class ConnectionPolicy {
    private final ProxyDto config;
    private final InetAddressLocator inetAddressLocator;

    public ConnectionPolicy(ProxyDto config, InetAddressLocator inetAddressLocator) {
        this.config = config;
        this.inetAddressLocator = inetAddressLocator;
    }

    public boolean isAllowed(Socket clientSocket) {
        List<String> allowedConditions = config.getAllowedCountries();
        if (allowedConditions == null || allowedConditions.isEmpty()) {
            return config.isOutbound();
        }

        String remoteAddr = clientSocket.getInetAddress().getHostAddress();
        String country = resolveCountryCode(remoteAddr);

        for (String allowedCondition : allowedConditions) {
            switch (allowedCondition.toLowerCase()) {
                case "any":
                    return true;
                case "localhost":
                    if (isLoopbackAddress(remoteAddr)) return true;
                    break;
                case "private":
                    if (isPrivateIP(remoteAddr)) return true;
                    break;
                default:
                    if (!"UNKNOWN".equals(country) && allowedCondition.equalsIgnoreCase(country)) {
                        return true;
                    }
            }
        }
        return false;
    }

    public String resolveCountryCode(String ipAddress) {
        try {
            Locale locale = inetAddressLocator.getLocale(ipAddress);
            return locale.getCountry();
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    private boolean isPrivateIP(String ipAddress) {
        try {
            InetAddress inetAddress = InetAddress.getByName(ipAddress);
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

    private boolean isLoopbackAddress(String ipAddress) {
        try {
            return InetAddress.getByName(ipAddress).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }
}
