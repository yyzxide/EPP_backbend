package com.epp.backend.netty;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import org.springframework.stereotype.Component;

@Component
public class NettyServerInitializer extends ChannelInitializer<SocketChannel> {
    
    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();
        
        // 1. 处理 TCP 粘包/拆包的解码器
        // maxFrameLength: 1MB (1024*1024)
        // lengthFieldOffset: 0 (长度字段在最开头)
        // lengthFieldLength: 4 (长度字段占 4 字节，int)
        // lengthAdjustment: 0 (长度字段的值不包含长度字段本身)
        // initialBytesToStrip: 4 (跳过前 4 字节的长度字段，后面只传真实数据)
        pipeline.addLast(new LengthFieldBasedFrameDecoder(1024 * 1024, 0, 4, 0, 4));

        // 2. 自定义解码器：把字节反序列化为 Java 对象（待写）
        // pipeline.addLast(new EppProtocolDecoder());

        // 3. 自定义编码器：把 Java 对象序列化为字节（待写）
        // pipeline.addLast(new EppProtocolEncoder());

        // 4. 业务处理器（待写）
        // pipeline.addLast(new DeviceChannelHandler());
    }
}
