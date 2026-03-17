package com.epp.backend.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NettyServer implements CommandLineRunner {

    // 从配置文件读取端口，默认 8090，支持通过环境变量 NETTY_PORT 覆盖
    @Value("${epp.netty.port:8090}")
    private int port;
    
    // 注入 Channel 初始化器
    private final NettyServerInitializer nettyServerInitializer;

    @Override
    public void run(String... args) throws Exception {
        // 1. 创建两个线程组：bossGroup 负责接收连接，workerGroup 负责处理读写
        EventLoopGroup bossGroup = new NioEventLoopGroup(1); // 1个线程足够了
        EventLoopGroup workerGroup = new NioEventLoopGroup(); // 默认是 CPU核数 * 2

        try {
            // 2. 创建 ServerBootstrap（服务端启动引导类）
            ServerBootstrap bootstrap = new ServerBootstrap();
            
            // 3. 配置 bootstrap：
            bootstrap.group(bossGroup, workerGroup)
                     .channel(NioServerSocketChannel.class)
                     // 【防连接风暴】服务端 TCP 全连接队列大小
                     // 默认值通常只有 128，几千个 EPP 客户端同时重连时队列溢出会被内核直接丢包
                     // C++类比: listen(fd, 4096) 的第二个参数 backlog
                     .option(ChannelOption.SO_BACKLOG, 4096)
                     // 【低延迟】禁用 Nagle 算法，小包（心跳/策略指令）立即发送，不攒包
                     // Nagle 算法会把多个小包合并成一个大包再发，适合大文件传输但会增加延迟
                     .childOption(ChannelOption.TCP_NODELAY, true)
                     // 【保活兜底】开启 TCP 底层的 KeepAlive 探测
                     // 应用层已有 IdleStateHandler（60s），TCP KeepAlive 作为最后一道防线，
                     // 防止网络中间设备（NAT/防火墙）单方面断开连接而两端毫不知情
                     .childOption(ChannelOption.SO_KEEPALIVE, true)
                     .childHandler(nettyServerInitializer);

            // 4. 绑定端口并同步等待成功
            ChannelFuture future = bootstrap.bind(port).sync();
            log.info("🚀 Netty TCP 服务端启动成功，监听端口: {}", port);

            // 5. 等待服务端监听端口关闭（阻塞主线程，保持运行）
            future.channel().closeFuture().sync();

        } catch (Exception e) {
            log.error("Netty 服务端启动失败", e);
        } finally {
            // 6. 优雅关闭线程组
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
