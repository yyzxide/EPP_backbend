package com.epp.backend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.epp.backend.entity.StrategyConfig;
import com.epp.backend.mapper.StrategyMapper;
import com.epp.backend.service.StrategyService;
import com.github.benmanes.caffeine.cache.Cache;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class StrategyServiceImpl implements StrategyService {

    @Resource
    private Cache<String, String> strategyCache;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private StrategyMapper strategyMapper;

    @Override
    public String getStrategy(String strategyId) {
        String cacheKey = "strategy:" + strategyId;
        // 1. 查 L1: Caffeine
        // 提示：Caffeine 的 getIfPresent 返回的是值本身（String），如果找不到则返回 null，而不是 Boolean 布尔值！
        String strategyInCaffeine = strategyCache.getIfPresent(cacheKey);
        if (strategyInCaffeine != null) {
            // L1 命中，直接返回内存里的数据
            return strategyInCaffeine;
        }

        // 2. L1 没找到，接下来你去查 L2 (Redis) 吧！
        String strategyRedis=stringRedisTemplate.opsForValue().get(cacheKey);
        if(strategyRedis != null)
        {
            strategyCache.put(cacheKey,strategyRedis);
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
            return "EMPTY";
        }
        String strategyJson=dbResult.getConfigJson();
        // 4. 把 DB 查到的结果写回 L2 (Redis) 和 L1 (Caffeine)，并返回
        stringRedisTemplate.opsForValue().set(cacheKey,strategyJson,Duration.ofHours(1));
        strategyCache.put(cacheKey,strategyJson);
        return strategyJson;
    }
}
