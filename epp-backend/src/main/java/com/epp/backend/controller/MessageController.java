package com.epp.backend.controller;

import com.epp.backend.common.CommonResult;
import com.epp.backend.handler.MessageDispatcher;
import com.epp.backend.mq.KafkaProducerService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Data;
import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
public class MessageController {

    private final KafkaProducerService kafkaProducerService;
    private final ObjectMapper objectMapper;
    private final MessageDispatcher messageDispatcher;

    @Value("${epp.kafka.topic.heartbeat}")
    private String heartbeattopic;

    @PostMapping("/api/message/dispatch")
    public CommonResult<String> dispatch(@RequestBody MessageRequest request) throws JsonProcessingException{
        if("HEARTBEAT".equals(request.getType()))
        {
            String json=objectMapper.writeValueAsString(request);
            kafkaProducerService.sendMessage(heartbeattopic,json);
            return CommonResult.success("消息已接收");
        }
        messageDispatcher.dispatch(request.getType(), request.getDeviceId(), request.getPayload());
        return CommonResult.success("消息已分发: " + request.getType());
    }

    /**
     * 消息分发请求体 DTO
     * 内部静态类，不用单独建文件
     */
    @Data
    static class MessageRequest {
        private String type;
        private String deviceId;
        private String payload;
    }
}
