package com.ticketing.booking.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RedisConfiguration {
    @Bean(destroyMethod = "destroy")
    LettuceConnectionFactory redisConnectionFactory(Bootstrap.Ready ready, Environment environment) throws Exception {
        var config = redisSettings(environment);
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2))
            .shutdownTimeout(Duration.ofSeconds(1)).build();
        return new LettuceConnectionFactory(config,client);
    }

    static RedisStandaloneConfiguration redisSettings(Environment environment) throws Exception {
        if (LearningBootstrap.enabled(environment)) {
            LearningBootstrap.validateEnvironment(environment);
            var config = new RedisStandaloneConfiguration("127.0.0.1", 16389);
            config.setPassword(LearningBootstrap.requiredSecret(environment, "TICKETING_REDIS_PASSWORD"));
            return config;
        }
        var config = new RedisStandaloneConfiguration("127.0.0.1", 16379);
        config.setPassword(Files.readString(Path.of("D:/Codex/ticketing-e0/infra/.local/redis-password")).trim());
        return config;
    }
    @Bean StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory connection) {
        return new StringRedisTemplate(connection);
    }
}
