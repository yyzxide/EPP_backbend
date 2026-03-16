package com.epp.backend.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;

import java.nio.charset.StandardCharsets;
import java.util.List;

import io.netty.channel.ChannelHandler;
import org.springframework.stereotype.Component;

@Component
@ChannelHandler.Sharable
public class EppProtocolEncoder extends MessageToMessageEncoder<EppMessage> {

    @Override
    protected void encode(ChannelHandlerContext ctx, EppMessage msg, List<Object> out) {
        
        String deviceId = msg.getDeviceId() == null ? "" : msg.getDeviceId();
        String body = msg.getBody() == null ? "" : msg.getBody();

        // 1. 预先计算字符串转换后的最大字节数（UTF-8 中一个字符最多占 3 字节）
        //    ByteBufUtil.utf8MaxBytes 能避免直接调用 .getBytes() 产生临时 byte[] 数组
        int bodyMaxBytes = ByteBufUtil.utf8MaxBytes(body);
        
        // 协议总长预估 = type(1字节) + deviceId(固定32字节) + bodyMaxBytes
        int maxLen = 1 + 32 + bodyMaxBytes;

        // 2. 从 Netty 内存池分配一个 ByteBuf (长度加上 4 字节的 length 字段本身)
        ByteBuf buf = ctx.alloc().buffer(4 + maxLen);

        // 3. 先跳过前 4 个字节，因为此时我们还不知道 body 编码后的确切真实长度
        buf.writeInt(0);

        // 4. 写入类型(1B)
        buf.writeByte(msg.getType());

        // 5. 写入 deviceId(固定 32B，不足右侧补 0，相当于 C++ 的 memset(buf, 0, 32) + memcpy)
        int deviceIdBytesWritten = ByteBufUtil.writeUtf8(buf, deviceId);
        if (deviceIdBytesWritten < 32) {
            buf.writeZero(32 - deviceIdBytesWritten);
        } else if (deviceIdBytesWritten > 32) {
            // 防御性编程：理论上不会走到这里，除非 deviceId 非常乱。如果超过了需要截断
            buf.writerIndex(buf.writerIndex() - (deviceIdBytesWritten - 32));
        }

        // 6. 写入 body (变长)，返回实际写入的字节数
        int bodyActualBytes = ByteBufUtil.writeUtf8(buf, body);

        // 7. 回填真正的总长度
        // 实际包体长度 = type(1) + deviceId(32) + bodyActualBytes
        int actualLen = 1 + 32 + bodyActualBytes;
        
        // 利用 setInt 在 buf 首部（第 0 字节位置）覆写之前填的 0
        buf.setInt(0, actualLen);

        // 8. 把拼好的 buf 交给下游
        out.add(buf);
    }
}
