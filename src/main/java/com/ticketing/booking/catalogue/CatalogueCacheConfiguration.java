package com.ticketing.booking.catalogue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("local-sim")
public class CatalogueCacheConfiguration {
    @Bean(destroyMethod="shutdown")
    RedissonClient catalogueRedisson() throws Exception {
        var config=new Config();
        config.setThreads(2).setNettyThreads(2);
        config.useSingleServer().setAddress("redis://127.0.0.1:16379")
            .setPassword(Files.readString(Path.of("D:/Codex/ticketing-e0/infra/.local/redis-password")).trim())
            .setConnectionMinimumIdleSize(1).setConnectionPoolSize(4)
            .setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(2)
            .setConnectTimeout(1500).setTimeout(1500).setRetryAttempts(0);
        return Redisson.create(config);
    }
}
