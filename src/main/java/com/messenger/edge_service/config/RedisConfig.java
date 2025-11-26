package com.messenger.edge_service.config;

import com.messenger.edge_service.model.utils.RedisClusterSettings;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
public class RedisConfig {

    @Bean
    @ConfigurationProperties(prefix = "redis.pubsub.config")
    public RedisClusterSettings pubSubSettings() {
        return new RedisClusterSettings();
    }

    @Bean
    @ConfigurationProperties(prefix = "redis.cache.config")
    public RedisClusterSettings cacheSettings() {
        return new RedisClusterSettings();
    }

    @Primary
    @Bean("pubSubRedisConnectionFactory")
    public ReactiveRedisConnectionFactory pubSubRedisConnectionFactory(
            @Value("${redis.pubsub.host:redis-pubsub}") String host,
            @Value("${redis.pubsub.port:6379}") int port,
            RedisClusterSettings pubSubSettings) {

        return createStandaloneFactory(host, port, pubSubSettings);
    }

    @Primary
    @Bean("pubSubRedisTemplate")
    public ReactiveRedisTemplate<String, String> pubSubRedisTemplate(
            @Qualifier("pubSubRedisConnectionFactory") ReactiveRedisConnectionFactory factory) {
        return createTemplate(factory);
    }

    @Bean("cacheRedisConnectionFactory")
    public ReactiveRedisConnectionFactory cacheRedisConnectionFactory(
            @Value("${redis.cache.host:redis-cache}") String host,
            @Value("${redis.cache.port:6379}") int port,
            RedisClusterSettings cacheSettings) {

        return createStandaloneFactory(host, port, cacheSettings);
    }

    @Bean("cacheRedisTemplate")
    public ReactiveRedisTemplate<String, String> cacheRedisTemplate(
            @Qualifier("cacheRedisConnectionFactory") ReactiveRedisConnectionFactory factory) {
        return createTemplate(factory);
    }

    private ReactiveRedisConnectionFactory createStandaloneFactory(String host, int port, RedisClusterSettings s) {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);

        SocketOptions socketOptions = SocketOptions.builder()
                .connectTimeout(Duration.ofMillis(s.getConnectTimeoutMs()))
                .keepAlive(true)
                .tcpNoDelay(true)
                .build();

        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(s.getCommandTimeoutMs()))
                .shutdownTimeout(Duration.ofMillis(s.getShutdownTimeoutMs()))
                .clientOptions(ClientOptions.builder().socketOptions(socketOptions).build())
                .build();

        return new LettuceConnectionFactory(config, clientConfig);
    }

    private ReactiveRedisTemplate<String, String> createTemplate(ReactiveRedisConnectionFactory factory) {
        var ctx = RedisSerializationContext.<String, String>newSerializationContext(new StringRedisSerializer()).build();
        return new ReactiveRedisTemplate<>(factory, ctx);
    }
}