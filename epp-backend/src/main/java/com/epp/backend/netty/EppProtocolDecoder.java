package com.epp.backend.netty;

import java.nio.charset.StandardCharsets;
import java.util.List;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

import io.netty.channel.ChannelHandler;
import org.springframework.stereotype.Component;

@Component
@ChannelHandler.Sharable
public class EppProtocolDecoder extends MessageToMessageDecoder<ByteBuf> {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // 1. 读 1 字节类型
        byte type = in.readByte();

        // 2. 读 32 字节设备ID，trim() 去掉右侧填充的空格
        byte[] deviceIdBytes = new byte[32];
        in.readBytes(deviceIdBytes);
        String deviceId = new String(deviceIdBytes, StandardCharsets.UTF_8).trim();

        // 3. 剩余字节全部是 body（JSON 业务数据）
        byte[] bodyBytes = new byte[in.readableBytes()];
        in.readBytes(bodyBytes);
        String body = new String(bodyBytes, StandardCharsets.UTF_8);

        // 4. 每次都 new 新对象，不能复用（并发安全）
        out.add(new EppMessage(type, deviceId, body));
    }
}
