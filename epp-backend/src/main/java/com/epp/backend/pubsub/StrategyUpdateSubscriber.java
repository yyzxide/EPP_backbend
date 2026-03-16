package com.epp.backend.pubsub;

import com.epp.backend.netty.ChannelManager;
import com.epp.backend.netty.EppMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Redis Pub/Sub 策略更新订阅方
 *
 * 每个服务实例都会订阅 "epp:strategy:update" Channel。
 * 任意实例发布消息后，所有实例（包括发布者自身）都会触发此方法，
 * 各自通过本地 ChannelManager 向自己管理的 Netty 连接推送通知。
 *
 * 方法名 "onStrategyUpdate" 必须与 RedisPubSubConfig 里
 * MessageListenerAdapter(subscriber, "onStrategyUpdate") 保持一致，
 * Spring 通过反射按名字找到这个方法并回调。
 * C++类比：相当于用函数名字符串注册了一个回调，Redis 消息到来时框架用 dlsym 找到函数指针并调用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyUpdateSubscriber {

    private final ChannelManager channelManager;

    /**
     * 收到策略更新广播时的处理逻辑
     *
     * @param message Redis 消息内容（即 publish 时的 strategyId）
     * @param channel Redis Channel 名称（即 "epp:strategy:update"）
     */
    public void onStrategyUpdate(String message, String channel) {
        String strategyId = message;
        log.info("收到策略更新广播, channel: {}, strategyId: {}", channel, strategyId);

        // 遍历本实例管理的所有在线设备，逐一推送 Netty 通知
        // 注意：每个实例只推送自己本地 ChannelManager 里的设备，不会重复推送
        Set<String> onlineDevices = channelManager.getAllOnlineDeviceIds();
        if (onlineDevices.isEmpty()) {
            log.info("当前实例无在线设备，跳过本地推送, strategyId: {}", strategyId);
            return;
        }

        // type=3 策略交互，body 携带事件类型和 strategyId，设备收到后自行拉取新配置
        EppMessage notifyMsg = new EppMessage(
                (byte) 3,
                "SYSTEM",
                "{\"event\":\"STRATEGY_UPDATED\",\"strategyId\":\"" + strategyId + "\"}"
        );

        int count = 0;
        for (String deviceId : onlineDevices) {
            channelManager.pushCommand(deviceId, notifyMsg);
            count++;
        }
        log.info("本实例已向 {} 台设备推送策略更新通知, strategyId: {}", count, strategyId);
    }
}