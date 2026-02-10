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

public class InetAddressLocator implements AutoCloseable {
    final private static Logger logger = LoggerFactory.getLogger(InetAddressLocator.class);
    private static volatile DatabaseReader reader;

    public InetAddressLocator(String databasePath) throws IOException {
        File database = new File(databasePath);
        if (!database.exists()) {
            throw new FileNotFoundException("GeoIP database file not found: " + databasePath);
        }
        DatabaseReader newReader = new DatabaseReader.Builder(database).build();
        replaceReader(newReader);
        logger.info("GeoIP database loaded successfully from: {}", databasePath);
    }

    public Locale getLocale(String ipAddress) {
        try {
            DatabaseReader currentReader = reader;
            if (currentReader == null) {
                return new Locale("", "UNKNOWN");
            }
            InetAddress inetAddress = InetAddress.getByName(ipAddress);
            CountryResponse response = currentReader.country(inetAddress);
            String countryCode = response.getCountry().getIsoCode();

            if (countryCode == null || countryCode.isEmpty()) {
                return new Locale("", "UNKNOWN");
            }

            // 국가 코드로 Locale 생성
            return new Locale("", countryCode);
        } catch (Exception e) {
            // 예외 발생 시 UNKNOWN 로케일 반환
            return new Locale("", "UNKNOWN");
        }
    }

    @Override
    public void close() {
        replaceReader(null);
    }

    private static synchronized void replaceReader(DatabaseReader newReader) {
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
