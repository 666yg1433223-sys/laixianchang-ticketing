package com.ticketing.booking.catalogue;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

@Configuration
@Profile("local-sim")
public class CatalogueCacheConfiguration {
    @Bean(destroyMethod="shutdown")
    RedissonClient catalogueRedisson(LettuceConnectionFactory connection) {
        // Reuse the validated Redis target and credential selected for sessions and messaging.
        // Learning mode therefore cannot fall back to the original machine's address or password file.
        return Redisson.create(redissonSettings(connection.getStandaloneConfiguration()));
    }

    static Config redissonSettings(RedisStandaloneConfiguration shared) {
        var config=new Config();
        config.setThreads(2).setNettyThreads(2);
        config.useSingleServer().setAddress("redis://"+shared.getHostName()+":"+shared.getPort())
            .setUsername(shared.getUsername())
            .setPassword(shared.getPassword().isPresent()?new String(shared.getPassword().get()):null)
            .setDatabase(shared.getDatabase())
            .setConnectionMinimumIdleSize(1).setConnectionPoolSize(4)
            .setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(2)
            .setConnectTimeout(1500).setTimeout(1500).setRetryAttempts(0);
        return config;
    }
}
