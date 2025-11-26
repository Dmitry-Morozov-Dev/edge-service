package com.messenger.edge_service.model.utils;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties
@Getter
@Setter
public class RedisClusterSettings {
    private int connectTimeoutMs = 2000;
    private int commandTimeoutMs = 2000;
    private int shutdownTimeoutMs = 500;
    private int maxRedirects = 5;
    private int topologyRefreshPeriodicSec = 30;
    private boolean validateClusterNodeMembership = true;
}