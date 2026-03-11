package com.epp.backend.handler;

import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class HeartbeatMessageHandler implements IMessageHandler{
    public String getType()
    {
        return "HEARTBEAT";
    }
    
    public void handle(String deviceId, String payload)
    {
        log.info("收到心跳信息，deviceId:{},payload:{}",deviceId,payload);
    }
}
