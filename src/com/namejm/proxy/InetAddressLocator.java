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

public class InetAddressLocator {
    final private static Logger logger = LoggerFactory.getLogger(InetAddressLocator.class);
    private static DatabaseReader reader;

    public InetAddressLocator(String databasePath) throws IOException {
        File database = new File(databasePath);
        if (!database.exists()) {
            throw new FileNotFoundException("GeoIP database file not found: " + databasePath);
        }
        // DatabaseReader 생성 실패 시 IOException 발생
        this.reader = new DatabaseReader.Builder(database).build();
        logger.info("GeoIP database loaded successfully from: {}", databasePath);
    }

    public Locale getLocale(String ipAddress) {
        try {
            InetAddress inetAddress = InetAddress.getByName(ipAddress);
            CountryResponse response = reader.country(inetAddress);
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

}
