package com.epp.backend.netty;

import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class ChannelManager {

    // 不用 @RequiredArgsConstructor，这个 Map 是自己 new 的，不是注入的
    private final ConcurrentHashMap<String, Channel> channelMap = new ConcurrentHashMap<>();

    public void add(String deviceId, Channel channel) {
        // Java Map 用 .get() 取值，.put() 存值，不是 [] 下标
        Channel old = channelMap.put(deviceId, channel);
        if (old != null && old != channel) {
            // 设备重连：旧的 TCP 连接（fd）可能还处于半开状态（拔网线场景），
            // 不主动关闭的话，底层 fd 会泄漏，直到系统 fd 上限耗尽。
            // close() 会触发旧 Channel 的 channelInactive，但此时 Map 里已经是新 Channel 了，
            // channelInactive 里的 remove() 调用不会误删新连接（因为 remove 是幂等的且已被新 Channel 覆盖）。
            // C++类比: 相当于发现同一个 deviceId 用了新的 socket fd，立刻 close(old_fd) 防止 fd 泄漏
            log.warn("设备 {} 重新连接，旧 Channel 未正常关闭，强制释放旧连接，防止 fd 泄漏", deviceId);
            old.close();
        }
        log.info("设备 {} 已注册 Channel，当前在线设备数: {}", deviceId, channelMap.size());
    }

    /**
     * 条件删除：只有当 map 里存的 Channel 与传入的 channel 是同一个对象，才真正删除。
     *
     * 为什么要这样做？— 防止快速重连场景下的竞态问题：
     *
     * 问题时序（旧 remove(String deviceId) 的 Bug）：
     *   T1: 设备重连 → add(deviceId, newChannel) → map 写入 newChannel → close(oldChannel)
     *   T2: oldChannel 触发 channelInactive → remove(deviceId) → map.remove(deviceId)
     *       ← 此时删掉的是 newChannel！设备变成"幽灵状态"：TCP 活着但服务端认为离线
     *
     * 修复后时序：
     *   T2: channelInactive → remove(deviceId, oldChannel)
     *       → channelMap.remove(deviceId, oldChannel) 原子判断：map 里是 newChannel ≠ oldChannel
     *       → 条件不满足，什么都不删，newChannel 安然无恙 ✓
     *
     * ConcurrentHashMap.remove(key, value) 是原子操作，内部用 CAS 实现，
     * 相当于 C++ 里的 compare_exchange_strong：只有 map[key] == value 才执行删除。
     */
    public void remove(String deviceId, Channel channel) {
        boolean removed = channelMap.remove(deviceId, channel);
        if (removed) {
            log.info("设备 {} Channel 已移除，当前在线设备数: {}", deviceId, channelMap.size());
        } else {
            log.info("设备 {} 的旧 Channel 已被新连接覆盖，跳过删除（快速重连保护）", deviceId);
        }
    }

    public void pushCommand(String deviceId, EppMessage msg) {
        Channel channel = channelMap.get(deviceId);
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(msg);
            log.info("已向设备 {} 推送指令，type: {}", deviceId, msg.getType());
        } else {
            log.warn("设备 {} 不在线，无法推送指令", deviceId);
        }
    }

    /**
     * 获取当前在线连接数
     */
    public int getOnlineCount() {
        return channelMap.size();
    }

    /**
     * 获取所有在线设备的 ID 列表
     * 用于广播指令或批量推送
     */
    public java.util.Set<String> getAllOnlineDeviceIds() {
        return channelMap.keySet();
    }
}
