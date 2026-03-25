package com.epp.backend.handler;

import com.epp.backend.entity.SecCheckRecord;
import com.epp.backend.mq.KafkaProducerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 安全检查消息处理器 (Netty路径)
 *
 * 不直接入库，而是发到 Kafka topic，由 SecCheckConsumer 批量入库。
 *
 * 为什么这样设计：
 * - 安全检查记录是"事件日志"，延迟几秒入库没有影响
 * - 通过 Kafka 批量 saveBatch() 比逐条 insert 效率高得多
 * - Netty IO 线程不阻塞在 DB 写入上
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecCheckMessageHandler implements IMessageHandler {

    private final KafkaProducerService kafkaProducerService;
    private final ObjectMapper objectMapper;

    @Value("${epp.kafka.topic.seccheck}")
    private String seccheckTopic;

    @Override
    public String getType() {
        return "SEC_CHECK";
    }

    @Override
    public void handle(String deviceId, String payload) {
        log.info("收到安全检查消息, deviceId:{}", deviceId);
        try {
            // 解析 payload，补全 deviceId 和 checkTime，序列化后投入 Kafka
            SecCheckRecord record = objectMapper.readValue(payload, SecCheckRecord.class);
            record.setDeviceId(deviceId);
            record.setCheckTime(LocalDateTime.now());

            String json = objectMapper.writeValueAsString(record);
            kafkaProducerService.sendMessage(seccheckTopic, json);
            log.debug("安全检查消息已投递 Kafka, deviceId:{}", deviceId);

        } catch (Exception e) {
            log.error("处理安全检查消息失败, deviceId:{}", deviceId, e);
        }
    }
}
