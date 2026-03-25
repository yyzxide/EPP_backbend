package com.epp.backend.controller;

import com.epp.backend.common.CommonResult;
import com.epp.backend.handler.MessageDispatcher;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部消息分发接口（供测试/内部服务调用，需要 X-Admin-Token）
 *
 * 所有消息类型统一走 MessageDispatcher：
 * - HEARTBEAT → HeartbeatMessageHandler → 直接更新 Redis/MySQL（实时，不经Kafka）
 * - SEC_CHECK  → SecCheckMessageHandler → 发 Kafka → SecCheckConsumer 批量入库
 */
@RequiredArgsConstructor
@RestController
public class MessageController {

    private final MessageDispatcher messageDispatcher;

    @PostMapping("/api/message/dispatch")
    public CommonResult<String> dispatch(@RequestBody MessageRequest request) {
        messageDispatcher.dispatch(request.getType(), request.getDeviceId(), request.getPayload());
        return CommonResult.success("消息已分发: " + request.getType());
    }

    @Data
    static class MessageRequest {
        private String type;
        private String deviceId;
        private String payload;
    }
}
