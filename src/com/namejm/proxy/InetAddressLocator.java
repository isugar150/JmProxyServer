package com.namejm.proxy;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.CountryResponse;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Locale;

public class InetAddressLocator {
    private static DatabaseReader reader;

    public InetAddressLocator(String databasePath) {
        try {
            File database = new File(databasePath);
            reader = new DatabaseReader.Builder(database).build();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    public static Locale getLocale(String ipAddress) {
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
