package com.epp.backend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.epp.backend.entity.DeviceInfo;
import com.epp.backend.exception.ResourceNotFoundException;
import com.epp.backend.mapper.DeviceMapper;
import com.epp.backend.service.DeviceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceServiceImpl extends ServiceImpl<DeviceMapper, DeviceInfo> implements DeviceService {

    private final StringRedisTemplate redisTemplate;
    private static final String REDIS_DEVICE_ONLINE_KEY = "epp:device:online:";

    @Override
    @Transactional
    public DeviceInfo registerOrHeartbeat(DeviceInfo deviceInfo) {
        // 1. 查找设备是否已存在
        DeviceInfo existing = this.getOne(new LambdaQueryWrapper<DeviceInfo>()
                .eq(DeviceInfo::getDeviceId, deviceInfo.getDeviceId()));

        if (existing == null) {
            // 新设备注册
            deviceInfo.setStatus(1);
            deviceInfo.setLastHeartbeatTime(LocalDateTime.now());
            this.save(deviceInfo);
            existing = deviceInfo;
        } else {
            // 已有设备心跳更新
            existing.setIpAddress(deviceInfo.getIpAddress());
            existing.setOsType(deviceInfo.getOsType());
            existing.setStatus(1);
            existing.setLastHeartbeatTime(LocalDateTime.now());
            // 如果设备传了版本号，也要同步 (增量同步逻辑预留)
            if (deviceInfo.getStrategyVersion() != null) {
                existing.setStrategyVersion(deviceInfo.getStrategyVersion());
            }
            this.updateById(existing);
        }

        // 2. 在 Redis 中标记在线状态, 设置过期时间为 90 秒 (心跳周期通常为 60s)
        // 【关键】Redis 不参与 Spring 的 @Transactional 事务管理。
        // 如果 MySQL 事务因异常回滚，Redis 的 SETEX 不会跟着回滚，导致：
        //   - MySQL 里设备没注册成功，但 Redis 里已经标记为"在线"
        //   - 这是一个脏数据不一致的 Bug
        // 解决方案：注册事务提交后的回调，确保只有 MySQL 成功 commit 之后才写 Redis。
        final String deviceId = existing.getDeviceId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                redisTemplate.opsForValue().set(
                        REDIS_DEVICE_ONLINE_KEY + deviceId,
                        "1",
                        90,
                        TimeUnit.SECONDS
                );
                log.info("MySQL 事务已提交，Redis 在线状态已更新, deviceId: {}", deviceId);
            }
        });

        return existing;
    }

    @Override
    public DeviceInfo getDeviceStatus(String deviceId) {
        String key = REDIS_DEVICE_ONLINE_KEY + deviceId;

        // 1. 检查 Redis (第一级缓存): Redis 有记录说明设备在线
        Boolean isOnline = redisTemplate.hasKey(key);

        // 2. 查数据库获取完整设备信息
        DeviceInfo device = this.getOne(new LambdaQueryWrapper<DeviceInfo>()
                .eq(DeviceInfo::getDeviceId, deviceId));

        if (device == null) {
            // 设备不存在, 抛异常由 GlobalExceptionHandler 统一处理
            throw new ResourceNotFoundException("设备不存在: " + deviceId);
        }

        // 3. 根据 Redis 状态 + 最后心跳时间综合判断在线状态
        if (Boolean.TRUE.equals(isOnline)) {
            // Redis 有记录: 在线
            device.setStatus(1);
        } else if (device.getLastHeartbeatTime() != null &&
                   device.getLastHeartbeatTime().isAfter(LocalDateTime.now().minusMinutes(5))) {
            // Redis 无记录但 5 分钟内有心跳: 补偿写回 Redis, 视为在线
            redisTemplate.opsForValue().set(key, "1", 90, TimeUnit.SECONDS);
            device.setStatus(1);
        } else {
            // 离线
            device.setStatus(0);
        }

        return device;
    }
}
