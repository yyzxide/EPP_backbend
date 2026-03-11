package com.epp.backend.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Caffeine Cache Configuration
 * C++类比: 相当于在这里注册了一个全局的单例 std::unordered_map 工厂
 */
@Configuration
public class CacheConfig {

    @Bean
    public Cache<String, String> strategyCache() {
        // C++类比: 初始化一块带读写锁的、有最大容量限制和超时淘汰机制的内存区域
        return Caffeine.newBuilder()
                .initialCapacity(100)         // 初始容量
                .maximumSize(10000)           // 最大容量(防OOM)
                .expireAfterWrite(Duration.ofMinutes(10)) // 写入10分钟后过期
                .build();
    }
}
