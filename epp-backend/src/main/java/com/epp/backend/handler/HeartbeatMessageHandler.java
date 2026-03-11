package com.epp.backend.handler;

import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import com.epp.backend.service.DeviceService;
import com.epp.backend.entity.DeviceInfo;

@Slf4j
@Component
@RequiredArgsConstructor
public class HeartbeatMessageHandler implements IMessageHandler {

    private final DeviceService deviceService;

    @Override
    public String getType() {
        return "HEARTBEAT";
    }

    @Override
    public void handle(String deviceId, String payload) {
        log.info("收到心跳信息，deviceId:{}, payload:{}", deviceId, payload);
        try{
        // 构造一个基本的 DeviceInfo 对象，只传 deviceId
        // （真实的 payload 可能包含 os_type 等，这里先用最简方式注册/心跳）
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId(deviceId);
        
        // 调用 Service 层复用之前的逻辑：更新 MySQL 心跳时间 + 刷新 Redis TTL
        deviceService.registerOrHeartbeat(deviceInfo);
        log.info("心跳处理完毕，已刷新 Redis 状态及 MySQL 时间，deviceId: {}", deviceId);
        
        }catch (Exception e)
        {
            log.error("处理心跳消息失败, deviceId: {}", deviceId, e);
        }
    }
}
