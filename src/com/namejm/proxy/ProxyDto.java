package com.namejm.proxy;

import java.util.Map;

public class ProxyDto {
    private String type;
    private String name;
    private int bindPort;
    private String forwardHost;
    private int forwardPort;
    private String[] allowedCountries;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getBindPort() {
        return bindPort;
    }

    public void setBindPort(int bindPort) {
        this.bindPort = bindPort;
    }

    public String getForwardHost() {
        return forwardHost;
    }

    public void setForwardHost(String forwardHost) {
        this.forwardHost = forwardHost;
    }

    public int getForwardPort() {
        return forwardPort;
    }

    public void setForwardPort(int forwardPort) {
        this.forwardPort = forwardPort;
    }

    public String[] getAllowedCountries() {
        return allowedCountries;
    }

    public void setAllowedCountries(String[] allowedCountries) {
        this.allowedCountries = allowedCountries;
    }

    public ProxyDto hashMapToDto(Map<String, Object> param){
        this.type = String.valueOf(param.get("type"));
        this.name = String.valueOf(param.get("name"));
        this.bindPort = Integer.parseInt(param.get("bindPort").toString());
        this.forwardHost = String.valueOf(param.get("forwardHost"));
        this.forwardPort = Integer.parseInt(param.get("forwardPort").toString());
        String[] temp = String.valueOf(param.get("allowedCountries")).substring(0, String.valueOf(param.get("allowedCountries")).length() - 1).substring(1).split(",");
        for(int i = 0; i<temp.length; i++){
            temp[i] = temp[i].trim();
        }
        this.allowedCountries = temp;
        return this;
    }
}
