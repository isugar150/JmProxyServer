package com.namejm.proxy;

import java.util.List;

class ConfigLoadResult {
    final List<ProxyDto> parsedConfigs;
    final List<ProxyDto> validConfigs;
    final GlobalConfig globalConfig;

    ConfigLoadResult(List<ProxyDto> parsedConfigs, List<ProxyDto> validConfigs, GlobalConfig globalConfig) {
        this.parsedConfigs = parsedConfigs;
        this.validConfigs = validConfigs;
        this.globalConfig = globalConfig;
    }
}
