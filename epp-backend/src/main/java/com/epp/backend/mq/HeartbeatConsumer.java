package com.epp.backend.mq;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.epp.backend.handler.HeartbeatMessageHandler;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.JsonNode;

@Component
@RequiredArgsConstructor
@Slf4j
public class HeartbeatConsumer {
    private final HeartbeatMessageHandler heartbeatMessageHandler;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "${epp.kafka.topic.heartbeat}", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(String message) {
        try {
            JsonNode node = objectMapper.readTree(message);
            String deviceId = node.get("deviceId").asText();
            String payload = node.get("payload").asText();
            heartbeatMessageHandler.handle(deviceId, payload);
        } catch (Exception e) {
            log.error("处理心跳消息失败, message: {}, error: {}", message, e.getMessage());
        }
    }
}
