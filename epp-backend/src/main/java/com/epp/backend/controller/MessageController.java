package com.epp.backend.controller;

import com.epp.backend.common.CommonResult;
import com.epp.backend.handler.MessageDispatcher;
import com.epp.backend.mq.KafkaProducerService;
import com.epp.backend.netty.ChannelManager;
import com.epp.backend.netty.EppMessage;
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
    private final ChannelManager channelManager;

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
     * 主动向所有在线设备推送策略更新指令 (发令枪)
     */
    @PostMapping("/api/strategy/push")
    public CommonResult<String> pushStrategy(@RequestBody String strategyId) {
        // 1. 获取所有在线设备的名单
        java.util.Set<String> onlineDevices = channelManager.getAllOnlineDeviceIds();
        
        // 2. 构造“更新信号”消息 (Type=3 代表策略交互，Body 仅包含策略 ID)
        // 客户端收到后，应根据这个 ID 触发拉取逻辑
        EppMessage pushMsg = new EppMessage((byte) 3, "SYSTEM", "{\"newStrategyId\":\"" + strategyId + "\"}");

        // 3. 广播推送
        int count = 0;
        for (String deviceId : onlineDevices) {
            channelManager.pushCommand(deviceId, pushMsg);
            count++;
        }

        return CommonResult.success("已成功向 " + count + " 台在线设备推送更新指令");
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
