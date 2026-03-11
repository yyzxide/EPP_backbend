package com.epp.backend.controller;

import com.epp.backend.common.CommonResult;
import com.epp.backend.handler.MessageDispatcher;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
public class MessageController {

    private final MessageDispatcher messageDispatcher;

    @PostMapping("/api/message/dispatch")
    public CommonResult<String> dispatch(@RequestBody MessageRequest request) {
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
