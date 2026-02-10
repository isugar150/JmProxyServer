package com.namejm.proxy;

class GlobalConfig {
    Integer executorCorePoolSize;
    Integer executorMaxPoolSize;
    Integer executorKeepAliveSeconds;
    Integer executorQueueCapacity;
    Integer shutdownAwaitSeconds;
    String geoIpDbPath;
    Boolean hotReloadEnabled;
    Long hotReloadWatchIntervalMillis;
    Long hotReloadDebounceMillis;
}
