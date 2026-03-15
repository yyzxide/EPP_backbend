package com.epp.backend.handler;

import com.epp.backend.netty.ChannelManager;
import com.epp.backend.netty.EppMessage;
import com.epp.backend.service.StrategyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 策略拉取消息处理器
 * 
 * 业务流程:
 *   1. 客户端发送 Type=3 的拉取请求, Body 为 {"strategyId": "S1001"}
 *   2. 本处理器调用 StrategyService (L1/L2/L3 缓存) 获取策略 JSON
 *   3. 封装回包 EppMessage (Type=3), Body 为策略具体内容
 *   4. 通过 ChannelManager 将回包定向发回给该设备
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyPullMessageHandler implements IMessageHandler {

    private final StrategyService strategyService;
    private final ChannelManager channelManager;
    private final ObjectMapper objectMapper;

    @Override
    public String getType() {
        return "STRATEGY_PULL";
    }

    @Override
    public void handle(String deviceId, String payload) {
        log.info("收到策略拉取请求, deviceId: {}, payload: {}", deviceId, payload);

        try {
            // 1. 解析客户端请求体中的 strategyId
            // C++ 类比: 相当于解析一个简单的 JSON 结构体取出 key
            JsonNode rootNode = objectMapper.readTree(payload);
            String strategyId = rootNode.get("strategyId").asText();

            if (strategyId == null || strategyId.isEmpty()) {
                log.warn("拉取请求中缺失 strategyId, deviceId: {}", deviceId);
                return;
            }

            // 2. 调用三级缓存 Service 获取策略内容
            // 这里会依次走 Caffeine -> Redis -> MySQL 逻辑
            String strategyConfigJson = strategyService.getStrategy(strategyId);

            // 3. 构造回包对象
            // 协议约定: Type=3 既是拉取请求, 也是拉取回复
            EppMessage responseMsg = new EppMessage((byte) 3, deviceId, strategyConfigJson);

            // 4. 定向发送回原设备
            // C++ 类比: 相当于通过全局 Map 找到该 deviceId 对应的 fd, 执行 write()
            channelManager.pushCommand(deviceId, responseMsg);

            log.info("策略下发成功, deviceId: {}, strategyId: {}", deviceId, strategyId);

        } catch (Exception e) {
            log.error("处理策略拉取请求异常, deviceId: {}, payload: {}", deviceId, payload, e);
            // 生产环境下, 可以在这里给客户端回一个专门的 ERROR 消息包
        }
    }
}
