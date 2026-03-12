package com.epp.backend.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public class EppProtocolEncoder extends MessageToMessageEncoder<EppMessage> {

    @Override
    protected void encode(ChannelHandlerContext ctx, EppMessage msg, List<Object> out) {
        
        // 1. 准备 deviceId，必须凑够 32 字节（不够的右侧补0）
        byte[] rawIdBytes = msg.getDeviceId() == null ? new byte[0] : msg.getDeviceId().getBytes(StandardCharsets.UTF_8);
        byte[] deviceIdBytes = Arrays.copyOf(rawIdBytes, 32);

        // 2. 准备 body，转成字节数组
        byte[] bodyBytes = msg.getBody() == null ? new byte[0] : msg.getBody().getBytes(StandardCharsets.UTF_8);

        // 3. 计算长度：type(1字节) + deviceId(32字节) + body长度
        int length = 1 + 32 + bodyBytes.length;

        // 4. 从 Netty 内存池分配一个 ByteBuf (长度要加上表示长度本身的 4 字节)
        ByteBuf buf = ctx.alloc().buffer(4 + length);

        // 5. 严格按照协议顺序写入数据！这个顺序就是 C++ 客户端那边读的顺序
        buf.writeInt(length);                // 写入长度(4B)
        buf.writeByte(msg.getType());        // 写入类型(1B)
        buf.writeBytes(deviceIdBytes);       // 写入设备ID(32B)
        buf.writeBytes(bodyBytes);           // 写入内容体(变长)

        // 6. 把拼好的 buf 交给下游（最终发给网卡）
        out.add(buf);
    }
}
