package com.epp.backend.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NettyServer implements CommandLineRunner {

    // 端口先写死 8090
    private final int port = 8090;
    
    // 注入我们之后要写的 Channel 初始化器（先声明着，虽然还没写）
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
            //   - group(bossGroup, workerGroup)
            //   - channel(NioServerSocketChannel.class)
            //   - childHandler(nettyServerInitializer)
            bootstrap.group(bossGroup,workerGroup);
            bootstrap.channel(NioServerSocketChannel.class);
            bootstrap.childHandler(nettyServerInitializer);

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
