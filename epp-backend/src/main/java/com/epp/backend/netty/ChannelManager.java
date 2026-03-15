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

    public void remove(String deviceId) {
        channelMap.remove(deviceId);
        log.info("设备 {} 已移除 Channel，当前在线设备数: {}", deviceId, channelMap.size());
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
