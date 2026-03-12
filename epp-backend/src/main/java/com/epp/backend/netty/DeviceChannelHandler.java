package com.epp.backend.netty;

import com.epp.backend.handler.MessageDispatcher;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 终端业务处理器
 * C++类比: 相当于 Socket Server 的消息分发回调函数
 */
@Slf4j
@Component
@ChannelHandler.Sharable
@RequiredArgsConstructor
public class DeviceChannelHandler extends SimpleChannelInboundHandler<EppMessage> {

    private final MessageDispatcher messageDispatcher;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, EppMessage msg) throws Exception {
        log.info("收到终端消息, deviceId: {}, type: {}", msg.getDeviceId(), msg.getType());

        // 1. 将协议中的 byte 类型转换为业务中的 String 类型
        String typeStr = switch (msg.getType()) {
            case 1 -> "HEARTBEAT";
            case 2 -> "SEC_CHECK";
            case 3 -> "STRATEGY_PULL";
            default -> "UNKNOWN";
        };

        // 2. 将消息分发给对应的 Handler 处理
        // 我们在 MessageDispatcher 里已经用策略模式管好了所有的 Handler
        messageDispatcher.dispatch(typeStr, msg.getDeviceId(), msg.getBody());
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        log.info("📢 终端连接成功: {}", ctx.channel().remoteAddress());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.info("🔌 终端断开连接: {}", ctx.channel().remoteAddress());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("Netty 链路发生异常", cause);
        ctx.close();
    }
}
