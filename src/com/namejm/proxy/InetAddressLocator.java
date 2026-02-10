package com.namejm.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.CountryResponse;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class InetAddressLocator implements AutoCloseable {
    final private static Logger logger = LoggerFactory.getLogger(InetAddressLocator.class);
    private volatile DatabaseReader reader;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public InetAddressLocator(String databasePath) throws IOException {
        reload(databasePath);
    }

    public synchronized void reload(String databasePath) throws IOException {
        if (closed.get()) {
            throw new IllegalStateException("InetAddressLocator is already closed.");
        }

        File database = new File(databasePath);
        if (!database.exists()) {
            throw new FileNotFoundException("GeoIP database file not found: " + databasePath);
        }
        DatabaseReader newReader = new DatabaseReader.Builder(database).build();
        replaceReader(newReader);
        logger.info("GeoIP database loaded successfully from: {}", databasePath);
    }

    public String getCountryCode(InetAddress inetAddress) {
        if (inetAddress == null) {
            return "UNKNOWN";
        }

        try {
            DatabaseReader currentReader = reader;
            if (currentReader == null) {
                return "UNKNOWN";
            }
            CountryResponse response = currentReader.country(inetAddress);
            String countryCode = response.getCountry().getIsoCode();
            if (countryCode == null || countryCode.isEmpty()) {
                return "UNKNOWN";
            }
            return countryCode;
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    public Locale getLocale(String ipAddress) {
        try {
            InetAddress inetAddress = InetAddress.getByName(ipAddress);
            String countryCode = getCountryCode(inetAddress);
            if ("UNKNOWN".equals(countryCode)) {
                return new Locale("", "UNKNOWN");
            }

            return new Locale("", countryCode);
        } catch (Exception e) {
            return new Locale("", "UNKNOWN");
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            replaceReader(null);
        }
    }

    private synchronized void replaceReader(DatabaseReader newReader) {
        DatabaseReader oldReader = reader;
        reader = newReader;
        if (oldReader != null) {
            try {
                oldReader.close();
            } catch (IOException e) {
                logger.warn("Failed to close previous GeoIP database reader", e);
            }
        }
    }
}
