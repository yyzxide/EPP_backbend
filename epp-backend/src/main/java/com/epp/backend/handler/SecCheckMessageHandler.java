package com.epp.backend.handler;

import java.time.LocalDateTime;
import org.springframework.stereotype.Component;
import com.epp.backend.entity.SecCheckRecord;
import com.epp.backend.mapper.SecCheckMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class SecCheckMessageHandler implements IMessageHandler {

    private final SecCheckMapper secCheckMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String getType() {
        return "SEC_CHECK";
    }

    @Override
    public void handle(String deviceId, String payload) {
        log.info("收到安全检查消息, deviceId:{}, payload:{}", deviceId, payload);
        try {
            // 1. 解析 payload JSON → SecCheckRecord 对象
            SecCheckRecord record = objectMapper.readValue(payload, SecCheckRecord.class);

            // 2. 补全 deviceId 和 checkTime（这两个不在 payload 里）
            record.setDeviceId(deviceId);
            record.setCheckTime(LocalDateTime.now());

            // 3. 存库
            secCheckMapper.insert(record);
            log.info("安全检查记录入库成功, id:{}", record.getId());

        } catch (Exception e) {
            log.error("处理安全检查消息失败, deviceId: {}", deviceId, e);
        }
    }
}
