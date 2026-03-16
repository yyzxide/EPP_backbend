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

        // 2. 读 32 字节设备ID，trim() 去掉右侧填充的空字符/空格
        //    【GC优化】使用 readCharSequence 直接从 ByteBuf 底层内存读取字符序列，
        //    不再 new byte[32] 中间数组，避免高并发下频繁触发 Young GC。
        //    C++类比：相当于直接从 recv buffer 的指针位置做 memcpy + 编码转换，
        //    而不是先 malloc 一块临时内存再 memcpy 两次。
        String deviceId = in.readCharSequence(32, StandardCharsets.UTF_8).toString().trim();

        // 3. 剩余字节全部是 body（JSON 业务数据）
        //    【GC优化】同上，直接从 ByteBuf 读字符序列，零中间对象。
        //    在 10 万 QPS 场景下，每次请求少分配 2 个 byte[]（deviceId + body），
        //    相当于每秒减少 20 万次堆内存分配，显著降低 GC 压力。
        String body = in.readCharSequence(in.readableBytes(), StandardCharsets.UTF_8).toString();

        // 4. 每次都 new 新对象，不能复用（并发安全）
        out.add(new EppMessage(type, deviceId, body));
    }
}
