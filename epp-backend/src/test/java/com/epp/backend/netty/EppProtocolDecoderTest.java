package com.epp.backend.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Netty 二进制协议解码器测试
 *
 * 核心目标:
 *   1. 验证正常数据包能否被正确解析为 EppMessage。
 *   2. 验证脏数据、半包、恶意伪造字段等极端情况下，系统是否会发生越界崩溃 (IndexOutOfBounds)。
 *
 * 知识点 (Netty EmbeddedChannel):
 *   EmbeddedChannel 是 Netty 专门提供的"无网络开销"测试通道。
 *   我们可以把 ByteBuf 像塞水管一样塞进去 (writeInbound)，然后去另一头接水 (readInbound)，
 *   不需要启动真实的 Socket 端口。
 */
class EppProtocolDecoderTest {

    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        // 创建一个包裹了我们自定义解码器的测试通道
        channel = new EmbeddedChannel(new EppProtocolDecoder());
    }

    @Test
    void testDecode_NormalPacket() {
        // 1. 构造一个完美的测试包
        byte type = 3; // 策略交互
        String deviceId = "test-device-12345678901234567890"; // 刚好 32 字节
        String body = "{\"strategyId\":\"S1001\"}";

        // Netty 的 Unpooled 相当于 malloc，分配一块直接/堆内存
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(type);
        buf.writeBytes(deviceId.getBytes(StandardCharsets.UTF_8));
        buf.writeBytes(body.getBytes(StandardCharsets.UTF_8));

        // 2. 将数据包写入通道 (模拟从网卡收到了数据)
        // writeInbound 如果返回 true，说明有数据被成功解码并传递到了下一个 Handler
        assertTrue(channel.writeInbound(buf));

        // 3. 从通道的另一端取出解码后的对象
        EppMessage decodedMsg = channel.readInbound();

        // 4. 断言 (Assert)：验证解出来的东西和我们塞进去的是否一致
        assertNotNull(decodedMsg);
        assertEquals(type, decodedMsg.getType());
        assertEquals(deviceId, decodedMsg.getDeviceId());
        assertEquals(body, decodedMsg.getBody());
        
        System.out.println("✅ 正常解码测试通过！");
    }

    /**
     * 【对抗测试】恶意构造一个超短的包（只有 5 个字节）
     * 
     * 现象：由于我们在 NettyServerInitializer 前面加了 LengthFieldBasedFrameDecoder，
     *      正常情况下，这个短包在上一关就被拦截/缓存了。
     *      但由于目前这个测试只挂载了 EppProtocolDecoder（跳过了 LengthField 关卡），
     *      它直接去读取 1+32 字节，必然会发生缓冲区越界（IndexOutOfBoundsException）。
     *      
     * 意义：这个测试反而证明了，如果前面没有 LengthFieldBasedFrameDecoder 当保镖，
     *      我们目前的 Decoder 是非常脆弱的。这就证明了你引入 LengthField 是绝对正确的架构决策！
     */
    @Test
    void testDecode_BufferUnderflow_ProvesLengthFieldIsNeeded() {
        // 构造一个只有几个字节的残缺包
        ByteBuf maliciousBuf = Unpooled.buffer();
        maliciousBuf.writeByte(1); // type
        maliciousBuf.writeBytes(new byte[]{0x01, 0x02, 0x03}); // 残缺的 deviceId

        // 当我们强行把这个包塞给它解析时，期望它抛出 DecoderException (内部包裹了 IndexOutOfBoundsException)
        assertThrows(DecoderException.class, () -> {
            channel.writeInbound(maliciousBuf);
        });
        
        System.out.println("✅ 边界崩溃测试通过：证明了如果不加前置 LengthField 拦截，系统必崩！");
    }
}
