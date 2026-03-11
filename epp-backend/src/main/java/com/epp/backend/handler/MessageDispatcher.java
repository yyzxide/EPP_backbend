package com.epp.backend.handler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class MessageDispatcher implements InitializingBean {

    private final List<IMessageHandler> handlers;
    private final Map<String, IMessageHandler> handlerMap = new HashMap<>();

    // 构造函数注入：Spring 自动把所有 IMessageHandler 实现类收集成 List 传进来
    public MessageDispatcher(List<IMessageHandler> handlers) {
        this.handlers = handlers;
    }

    @Override
    public void afterPropertiesSet() {
        for (IMessageHandler handler : handlers) {
            handlerMap.put(handler.getType(), handler);
        }
        log.info("已注册消息处理器: {}", handlerMap.keySet());
    }

    public void dispatch(String type, String deviceId, String payload) {
        IMessageHandler handler = handlerMap.get(type);
        if(handler == null)
        {
            log.info("未知类型,deviceId:{},payload:{}",deviceId,payload);
            return;
        }
        handler.handle(deviceId, payload);
    }
}
