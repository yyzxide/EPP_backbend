package com.epp.backend.netty;

import io.netty.channel.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ChannelManager 并发竞态测试
 *
 * 核心目标：验证快速重连场景下，CAS remove(key, value) 不会误删新连接。
 *
 * 问题背景：
 *   设备断线重连时，旧 Channel 的 channelInactive 可能晚于新 Channel 的 add() 到达。
 *   如果 remove 用的是无条件 remove(key)，会把新连接从 Map 里删掉，造成"幽灵状态"。
 *   ChannelManager.remove(deviceId, channel) 用 ConcurrentHashMap.remove(key, value) 解决此问题。
 */
class ChannelManagerConcurrencyTest {

    private ChannelManager channelManager;

    @BeforeEach
    void setUp() {
        channelManager = new ChannelManager();
    }

    /**
     * 快速重连竞态：旧 Channel 的 inactive 事件晚于新 Channel 注册到达
     * 预期：remove(deviceId, oldChannel) 不应影响新 Channel 的注册状态
     */
    @Test
    void testFastReconnect_OldChannelInactive_ShouldNotRemoveNewChannel() {
        String deviceId = "test-device-001";
        Channel oldChannel = mock(Channel.class);
        Channel newChannel = mock(Channel.class);
        when(newChannel.isActive()).thenReturn(true);

        // T1: 新连接注册
        channelManager.add(deviceId, newChannel);

        // T2: 旧 channel 的 inactive 事件迟到 — 尝试用旧 channel 去 remove
        channelManager.remove(deviceId, oldChannel);

        // 验证：新 channel 依然在线，可以 pushCommand
        int onlineCount = channelManager.getOnlineCount();
        assertEquals(1, onlineCount, "快速重连后新 Channel 不应被误删，在线数应为 1");
    }

    /**
     * 正常下线：remove 传入的 channel 与 map 里一致，应正确移除
     */
    @Test
    void testNormalDisconnect_RemovesCorrectly() {
        String deviceId = "test-device-002";
        Channel channel = mock(Channel.class);

        channelManager.add(deviceId, channel);
        assertEquals(1, channelManager.getOnlineCount());

        // 正常下线：传入同一个 channel 对象
        channelManager.remove(deviceId, channel);
        assertEquals(0, channelManager.getOnlineCount(), "正常下线后在线数应为 0");
    }

    /**
     * 高并发多设备同时注册：验证 ConcurrentHashMap 线程安全
     */
    @Test
    void testConcurrentAdd_MultipleDevices_AllRegistered() throws InterruptedException {
        int deviceCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(32);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(deviceCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < deviceCount; i++) {
            final String deviceId = "device-" + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Channel channel = mock(Channel.class);
                    channelManager.add(deviceId, channel);
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(0, errors.get(), "并发注册不应抛出异常");
        assertEquals(deviceCount, channelManager.getOnlineCount(),
                "100 个设备并发注册后，在线数应为 100");
    }

    /**
     * 并发重连：同一设备多次重连，最终只保留最后一次连接
     */
    @Test
    void testConcurrentReconnect_SameDevice_OnlyLatestChannelSurvives() throws InterruptedException {
        String deviceId = "device-reconnect";
        int reconnectCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch doneLatch = new CountDownLatch(reconnectCount);

        for (int i = 0; i < reconnectCount; i++) {
            executor.submit(() -> {
                try {
                    Channel channel = mock(Channel.class);
                    channelManager.add(deviceId, channel);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // 无论重连多少次，同一设备在 Map 中只存一个 Channel
        assertEquals(1, channelManager.getOnlineCount(),
                "同一设备反复重连后，在线数应始终为 1");
    }
}
