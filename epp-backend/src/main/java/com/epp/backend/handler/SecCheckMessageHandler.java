package com.epp.backend.handler;

import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class SecCheckMessageHandler implements IMessageHandler{
    public String getType()
    {
        return "SEC_CHECK";
    }
    
    public void handle(String deviceId,String payload)
    {
        log.info("收到安全检查消息, deviceId:{}, payload:{}", deviceId, payload);
    }
}
