package com.epp.backend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.epp.backend.entity.DeviceInfo;
import com.epp.backend.mapper.DeviceMapper;
import com.epp.backend.service.DeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

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
        redisTemplate.opsForValue().set(
                REDIS_DEVICE_ONLINE_KEY + existing.getDeviceId(),
                "1",
                90,
                TimeUnit.SECONDS
        );

        return existing;
    }

    @Override
    public String getDeviceStatus(String deviceId) {
        String key = REDIS_DEVICE_ONLINE_KEY + deviceId;
        
        // 1. 检查 Redis (第一级缓存)
        Boolean isOnline = redisTemplate.hasKey(key);
        if (Boolean.TRUE.equals(isOnline)) {
            return "Online (From Redis)";
        }

        // 2. 检查数据库 (第二级存储)
        DeviceInfo device = this.getOne(new LambdaQueryWrapper<DeviceInfo>()
                .eq(DeviceInfo::getDeviceId, deviceId));
        
        if (device == null) {
            return "Unknown Device";
        }

        // 3. 补偿逻辑: 5 分钟内有心跳则补回 Redis
        if (device.getLastHeartbeatTime() != null && 
            device.getLastHeartbeatTime().isAfter(LocalDateTime.now().minusMinutes(5))) {
            
            redisTemplate.opsForValue().set(key, "1", 90, TimeUnit.SECONDS);
            return "Online (From DB & Refreshed Redis)";
        }

        return "Offline";
    }
}
