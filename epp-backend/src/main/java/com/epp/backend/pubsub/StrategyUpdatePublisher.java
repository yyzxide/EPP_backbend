package com.epp.backend.pubsub;

import com.epp.backend.config.RedisPubSubConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis Pub/Sub 策略更新发布方
 *
 * 职责：在策略更新完成后（DB 已写入、缓存已清除），
 * 向 Redis Channel 发布一条消息，触发所有实例的订阅者推送 Netty 通知。
 *
 * 为什么用 Pub/Sub 而不是 Kafka？
 *   Pub/Sub：实时性极高（毫秒级），消息不持久化，断线期间的消息会丢失。
 *            适合"广播通知"场景——设备收不到也没关系，下次心跳时会自动重同步策略。
 *   Kafka：  高吞吐、持久化、支持回放，适合"不能丢失"的安全指令场景（如隔离、阻断）。
 *
 * 双轨路由设计：
 *   低优先级（策略变更通知）→ Redis Pub/Sub（本文件）
 *   高优先级（安全指令）    → Kafka（KafkaProducerService，已实现）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyUpdatePublisher {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 发布策略更新事件到 Redis Channel
     *
     * @param strategyId 发生变更的策略 ID
     *
     * 底层原理：
     *   stringRedisTemplate.convertAndSend() 调用 Redis PUBLISH 命令：
     *   PUBLISH epp:strategy:update "strategyId"
     *   所有通过 SUBSCRIBE epp:strategy:update 订阅的客户端（即所有实例）都会收到该消息。
     *   C++类比：相当于往一个命名管道 write()，所有 read() 这个管道的进程都会被唤醒。
     */
    public void publishStrategyUpdate(String strategyId) {
        stringRedisTemplate.convertAndSend(
                RedisPubSubConfig.STRATEGY_UPDATE_CHANNEL,
                strategyId
        );
        log.info("策略更新事件已发布到 Redis Channel [{}], strategyId: {}",
                RedisPubSubConfig.STRATEGY_UPDATE_CHANNEL, strategyId);
    }
}