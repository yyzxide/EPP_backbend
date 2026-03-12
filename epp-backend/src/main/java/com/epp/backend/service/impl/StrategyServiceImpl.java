package com.epp.backend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.epp.backend.entity.StrategyConfig;
import com.epp.backend.mapper.StrategyMapper;
import com.epp.backend.service.StrategyService;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyServiceImpl implements StrategyService {

    private final Cache<String, String> strategyCache;
    private final StringRedisTemplate stringRedisTemplate;
    private final StrategyMapper strategyMapper;

    @Override
    public String getStrategy(String strategyId) {
        String cacheKey = "strategy:" + strategyId;
        // 1. 查 L1: Caffeine
        // 提示：Caffeine 的 getIfPresent 返回的是值本身（String），如果找不到则返回 null，而不是 Boolean 布尔值！
        String strategyInCaffeine = strategyCache.getIfPresent(cacheKey);
        if (strategyInCaffeine != null) {
            if("EMPTY".equals(strategyInCaffeine))
            {
                throw new RuntimeException("策略不存在："+strategyId);
            }
            // L1 命中，直接返回内存里的数据
            log.info("L1 Caffeine 命中, strategyId: {}", strategyId);
            return strategyInCaffeine;
        }

        // 2. L1 没找到，接下来你去查 L2 (Redis) 吧！
        String strategyRedis=stringRedisTemplate.opsForValue().get(cacheKey);
        if(strategyRedis != null)
        {
            if("EMPTY".equals(strategyRedis))
            {
                throw new RuntimeException("策略不存在："+strategyId);
            }
            strategyCache.put(cacheKey,strategyRedis);
            log.info("L2 Redis 命中, strategyId: {}", strategyId);
            return strategyRedis;
        }
        // 3. Redis 没命中，再去查 DB
        LambdaQueryWrapper<StrategyConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(StrategyConfig::getStrategyId,strategyId);

        StrategyConfig dbResult = strategyMapper.selectOne(queryWrapper);
        if(dbResult == null)
        {
            stringRedisTemplate.opsForValue().set(cacheKey,"EMPTY",Duration.ofMinutes(5));
            strategyCache.put(cacheKey,"EMPTY");
            log.warn("策略不存在, strategyId: {}", strategyId);
            throw new RuntimeException("策略不存在: " + strategyId);
        }
        String strategyJson=dbResult.getConfigJson();
        // 4. 把 DB 查到的结果写回 L2 (Redis) 和 L1 (Caffeine)，并返回
        stringRedisTemplate.opsForValue().set(cacheKey,strategyJson,Duration.ofHours(1));
        strategyCache.put(cacheKey,strategyJson);
        log.info("L3 MySQL 命中, strategyId: {}", strategyId);
        return strategyJson;
    }
}
