package com.epp.backend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.epp.backend.entity.StrategyConfig;
import com.epp.backend.exception.BadRequestException;
import com.epp.backend.exception.ResourceNotFoundException;
import com.epp.backend.mapper.StrategyMapper;
import com.epp.backend.monitor.EppMetrics;
import com.epp.backend.pubsub.StrategyUpdatePublisher;
import com.epp.backend.service.StrategyService;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyServiceImpl implements StrategyService {

    private final Cache<String, String> strategyCache;
    private final StringRedisTemplate stringRedisTemplate;
    private final StrategyMapper strategyMapper;
    private final EppMetrics eppMetrics;
    private final StrategyUpdatePublisher strategyUpdatePublisher;

    // 锁池：替代 String.intern()，防止恶意并发查询导致常量池 OOM
    private final ConcurrentHashMap<String, Object> lockMap = new ConcurrentHashMap<>();

    @Override
    public String getStrategy(String strategyId) {
        String cacheKey = "strategy:" + strategyId;

        // 1. 查 L1: Caffeine
        // 提示：Caffeine 的 getIfPresent 返回的是值本身（String），如果找不到则返回 null，而不是 Boolean 布尔值！
        String strategyInCaffeine = strategyCache.getIfPresent(cacheKey);
        if (strategyInCaffeine != null) {
            if ("EMPTY".equals(strategyInCaffeine)) {
                throw new ResourceNotFoundException("策略不存在：" + strategyId);
            }
            // L1 命中，直接返回内存里的数据
            log.info("L1 Caffeine 命中, strategyId: {}", strategyId);
            eppMetrics.recordCacheHit();  // 【埋点】L1 命中
            return strategyInCaffeine;
        }

        // 2. L1 没找到，接下来你去查 L2 (Redis) 吧！
        String strategyRedis = stringRedisTemplate.opsForValue().get(cacheKey);
        if (strategyRedis != null) {
            if ("EMPTY".equals(strategyRedis)) {
                throw new ResourceNotFoundException("策略不存在：" + strategyId);
            }
            strategyCache.put(cacheKey, strategyRedis);
            log.info("L2 Redis 命中, strategyId: {}", strategyId);
            eppMetrics.recordCacheHit();  // 【埋点】L2 命中
            return strategyRedis;
        }

        // 3. Redis 没命中，再去查 DB (加锁防止击穿)
        // 使用 ConcurrentHashMap 维护锁对象，避免 .intern() 导致常量池内存泄漏
        Object lock = lockMap.computeIfAbsent(strategyId, k -> new Object());
        synchronized (lock) {
            try {
                // 二次检查，防止重复查库
                strategyInCaffeine = strategyCache.getIfPresent(cacheKey);
                if (strategyInCaffeine != null) {
                    return strategyInCaffeine;
                }

                LambdaQueryWrapper<StrategyConfig> queryWrapper = new LambdaQueryWrapper<>();
                queryWrapper.eq(StrategyConfig::getStrategyId, strategyId);

                StrategyConfig dbResult = strategyMapper.selectOne(queryWrapper);
                if (dbResult == null) {
                    stringRedisTemplate.opsForValue().set(cacheKey, "EMPTY", Duration.ofMinutes(5));
                    strategyCache.put(cacheKey, "EMPTY");
                    log.warn("策略不存在, strategyId: {}", strategyId);
                    throw new ResourceNotFoundException("策略不存在: " + strategyId);
                }
                String strategyJson = dbResult.getConfigJson();
                // 4. 把 DB 查到的结果写回 L2 (Redis) 和 L1 (Caffeine)，并返回
                stringRedisTemplate.opsForValue().set(cacheKey, strategyJson, Duration.ofHours(1));
                strategyCache.put(cacheKey, strategyJson);
                log.info("L3 MySQL 命中, strategyId: {}", strategyId);
                eppMetrics.recordCacheMiss();  // 【埋点】L3 穿透到 MySQL
                return strategyJson;
            } finally {
                // 查完库（无论成功失败）都要释放锁对象，防止 lockMap 无限膨胀
                lockMap.remove(strategyId);
            }
        }
    }

    /**
     * 更新策略 — Cache Aside Pattern 完整闭环
     *
     * 执行顺序：
     *   1. 写入 MySQL（持久化）
     *   2. 主动删除 Redis 缓存（让下次读穿透到 DB，保证一致性）
     *   3. 主动删除 Caffeine 缓存（同上）
     *   4. 通过 Netty 向所有在线设备广播 type=3 变更通知
     *      设备收到后触发拉取逻辑，重新从 /api/strategy/{id} 拉取最新配置
     *
     * 为什么是"删除"而不是"更新"缓存？
     *   如果先写 DB 再更新缓存，在高并发下可能出现"旧值覆盖新值"的竞态。
     *   删除缓存是更安全的做法：下次读时从 DB 拉最新值，再写回缓存。
     *   C++类比：相当于写完文件后让其他进程的 page cache 失效，强制重新读磁盘。
     */
    @Override
    public void updateStrategy(String strategyId, String configJson) {
        if (!StringUtils.hasText(strategyId)) {
            throw new BadRequestException("strategyId 不能为空");
        }
        if (!StringUtils.hasText(configJson)) {
            throw new BadRequestException("configJson 不能为空");
        }

        String cacheKey = "strategy:" + strategyId;
        long version = System.currentTimeMillis();

        // 1. 写入 MySQL：用 upsert 语义（有则更新，无则插入）
        StrategyConfig existing = strategyMapper.selectOne(
                new LambdaQueryWrapper<StrategyConfig>()
                        .eq(StrategyConfig::getStrategyId, strategyId));

        if (existing != null) {
            // 更新已有策略
            strategyMapper.update(null, new LambdaUpdateWrapper<StrategyConfig>()
                    .eq(StrategyConfig::getStrategyId, strategyId)
                    .set(StrategyConfig::getVersion, version)
                    .set(StrategyConfig::getConfigJson, configJson));
            log.info("策略已更新到 MySQL, strategyId: {}", strategyId);
        } else {
            // 插入新策略
            StrategyConfig newConfig = new StrategyConfig();
            newConfig.setStrategyId(strategyId);
            newConfig.setVersion(version);
            newConfig.setConfigJson(configJson);
            strategyMapper.insert(newConfig);
            log.info("新策略已写入 MySQL, strategyId: {}", strategyId);
        }

        // 2. 主动删除 Redis 缓存（Cache Aside：写后删缓存，不更新）
        stringRedisTemplate.delete(cacheKey);
        log.info("Redis 缓存已失效, key: {}", cacheKey);

        // 3. 主动删除 Caffeine 缓存
        strategyCache.invalidate(cacheKey);
        log.info("Caffeine 缓存已失效, key: {}", cacheKey);

        // 4. 通过 Redis Pub/Sub 向所有实例广播策略变更通知
        //    单实例时效果与直接调 ChannelManager 相同；
        //    多实例（集群）时，所有实例都会收到消息，各自推送给自己管理的设备，
        //    解决了"集群路由孤岛"问题——设备连在哪个实例都能收到通知。
        //    【注意】Pub/Sub 消息不持久化，如果某实例订阅线程挂掉会丢消息，
        //    但策略推送是"最终一致性"场景：设备心跳时会重新拉取，可以容忍偶发丢失。
        strategyUpdatePublisher.publishStrategyUpdate(strategyId);
        eppMetrics.recordStrategyPush();  // 【埋点】策略推送完成
    }
}
