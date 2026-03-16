package com.epp.backend.config;

import com.epp.backend.pubsub.StrategyUpdateSubscriber;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

/**
 * Redis Pub/Sub 配置
 *
 * 解决的问题：集群路由孤岛
 *
 * 单实例时，StrategyServiceImpl.updateStrategy() 可以直接用本地 ChannelManager 广播。
 * 但部署多台实例后（K8s 3副本），管理员请求打到实例A，设备B连接在实例C，
 * 实例A 的 ChannelManager 根本不知道设备B的存在，推送失效。
 *
 * 解决方案：Redis Pub/Sub 广播总线
 *   实例A 发布策略变更事件 → Redis Channel → 所有实例（包括A自己）收到消息
 *   → 各实例用自己本地的 ChannelManager 推送给自己管理的设备
 *   → 无论设备连在哪个实例，都能收到推送
 *
 * 架构流程：
 *   PUT /api/strategy/{id}
 *     → StrategyServiceImpl.updateStrategy()
 *       → 写 MySQL + 清缓存
 *       → StrategyUpdatePublisher.publish(strategyId)   ← 发布到 Redis Channel
 *
 *   Redis Channel: "epp:strategy:update"
 *     → 所有订阅实例的 StrategyUpdateSubscriber.onMessage()
 *       → 各自的 ChannelManager.pushCommand() 向本地设备推送
 *
 * C++类比：相当于一个进程间的 eventfd 广播机制，任何一个进程 write(eventfd)，
 *          所有 poll(eventfd) 的进程都会被唤醒，各自处理自己的事情。
 */
@Configuration
public class RedisPubSubConfig {

    /**
     * 策略更新 Channel 名称（所有实例共用同一个 Channel）
     */
    public static final String STRATEGY_UPDATE_CHANNEL = "epp:strategy:update";

    /**
     * MessageListenerAdapter：把 Redis 原始消息适配成 Java 方法调用
     *
     * 工作原理：
     *   Redis 收到消息 → 调用 StrategyUpdateSubscriber 的 "onStrategyUpdate" 方法
     *   相当于给 StrategyUpdateSubscriber 注册了一个回调，Redis 消息到来时自动触发。
     *
     * C++类比：相当于把一个函数指针注册到 Redis 客户端的消息回调链上。
     */
    @Bean
    public MessageListenerAdapter strategyUpdateListenerAdapter(StrategyUpdateSubscriber subscriber) {
        return new MessageListenerAdapter(subscriber, "onStrategyUpdate");
    }

    /**
     * RedisMessageListenerContainer：Redis 订阅容器
     *
     * 内部维护一个后台线程，持续监听 Redis Channel 的消息。
     * 收到消息后，路由给对应的 MessageListenerAdapter 处理。
     *
     * C++类比：相当于一个专门跑 redis_subscribe() 的独立线程，
     *          阻塞等待消息，收到后分发给注册的 handler。
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            MessageListenerAdapter strategyUpdateListenerAdapter) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        // 订阅策略更新 Channel
        container.addMessageListener(
                strategyUpdateListenerAdapter,
                new PatternTopic(STRATEGY_UPDATE_CHANNEL)
        );

        return container;
    }
}