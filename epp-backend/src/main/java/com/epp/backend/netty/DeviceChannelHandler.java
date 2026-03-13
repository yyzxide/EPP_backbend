package com.epp.backend.netty;

import com.epp.backend.handler.MessageDispatcher;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 终端业务处理器
 * C++类比: 相当于 Socket Server 的消息分发回调函数
 *
 * 核心职责:
 *   1. 收到消息 → 注册 Channel 到 ChannelManager → 【异步】分发给策略处理器
 *   2. 连接断开 → 从 ChannelManager 移除
 *
 * 设计要点:
 *   channelInactive 回调里拿不到 EppMessage，所以无法直接获取 deviceId。
 *   解决方案: 利用 Netty 的 Channel.attr() 把 deviceId 绑定到 Channel 上，
 *   断开时从 attr 取回。
 *   C++类比: 类似于给 socket fd 挂一个自定义的 userData 指针。
 *
 * 【线程池隔离】
 *   Netty Worker 线程（epoll 事件线程）只负责极快速的 Decode/Encode 和 Channel 注册。
 *   一旦解析出 EppMessage，立刻把 messageDispatcher.dispatch() 异步提交给业务线程池。
 *   Worker 线程可以立即返回去处理其他 99999 台设备的 I/O 事件，不被业务阻塞。
 *   C++类比: epoll 线程只做 recv() + 简单解析，任务 push 到 task_queue，由另一组 worker 线程消费。
 */
@Slf4j
@Component
@ChannelHandler.Sharable
@RequiredArgsConstructor
public class DeviceChannelHandler extends SimpleChannelInboundHandler<EppMessage> {

    private final MessageDispatcher messageDispatcher;
    private final ChannelManager channelManager;
    private final NettyBusinessThreadPool businessThreadPool;

    /**
     * AttributeKey — Netty 给每个 Channel 提供的"附加属性"机制
     * C++类比: 相当于给 socket fd 绑定一个 void* userData
     * 这里我们把 deviceId 绑上去，这样 channelInactive 时就能取回来
     */
    private static final AttributeKey<String> DEVICE_ID_KEY = AttributeKey.valueOf("deviceId");

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, EppMessage msg) throws Exception {
        String deviceId = msg.getDeviceId();
        log.info("收到终端消息, deviceId: {}, type: {}", deviceId, msg.getType());

        // 1. 将 deviceId 绑定到 Channel 的 attr 上（幂等操作，重复设置没关系）
        //    ⚡ 这步必须在 Netty Worker 线程里同步执行（操作的是当前线程的 Channel，无并发问题）
        ctx.channel().attr(DEVICE_ID_KEY).set(deviceId);

        // 2. 注册到 ChannelManager（内部用 ConcurrentHashMap，线程安全）
        //    ⚡ 这步也必须同步执行，保证后续的业务逻辑能通过 channelManager.pushCommand() 找到 Channel
        channelManager.add(deviceId, ctx.channel());

        // 3. 将协议中的 byte 类型转换为业务中的 String 类型
        String typeStr = switch (msg.getType()) {
            case 1 -> "HEARTBEAT";
            case 2 -> "SEC_CHECK";
            case 3 -> "STRATEGY_PULL";
            default -> "UNKNOWN";
        };

        // 4. 【线程池隔离】将耗时的业务分发逻辑异步提交给业务线程池
        //    Netty Worker 线程到这里就立刻返回了，去处理其他连接的 I/O 事件
        //    业务线程池里的线程负责执行 Kafka 发送、Redis 查询、数据库操作等耗时操作
        final String finalTypeStr = typeStr;
        businessThreadPool.submit(() ->
                messageDispatcher.dispatch(finalTypeStr, deviceId, msg.getBody())
        );
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        log.info("终端连接成功: {}", ctx.channel().remoteAddress());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // 从 Channel 的 attr 中取回之前绑定的 deviceId
        String deviceId = ctx.channel().attr(DEVICE_ID_KEY).get();
        if (deviceId != null) {
            channelManager.remove(deviceId);
            log.info("终端断开连接, deviceId: {}, addr: {}", deviceId, ctx.channel().remoteAddress());
        } else {
            log.info("终端断开连接(未注册设备): {}", ctx.channel().remoteAddress());
        }
    }

    /**
     * 【僵尸连接清理】捕获 IdleStateHandler 触发的空闲事件
     *
     * C++类比: 相当于你给每个 socket fd 挂了一个定时器，
     *          超时后主动 close(fd)，而不是傻等 epoll 通知 EPOLLRDHUP。
     * 底层原理: IdleStateHandler 基于时间轮(HashedWheelTimer)实现，
     *           O(1) 插入/删除，不管有多少连接都不会 CPU 爆炸。
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent idleEvent) {
            if (idleEvent.state() == IdleState.READER_IDLE) {
                // 60 秒没收到客户端任何数据，判定为僵尸连接，服务端主动断开
                String deviceId = ctx.channel().attr(DEVICE_ID_KEY).get();
                log.warn("设备 [{}] 超过 60 秒未发送心跳，判定为僵尸连接，服务端主动断开, addr: {}",
                        deviceId, ctx.channel().remoteAddress());
                // close() 会自动触发 channelInactive → channelManager.remove()，完美闭环
                ctx.close();
            }
        } else {
            // 其他类型的事件继续往后传，不要吞掉
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("Netty 链路异常, addr: {}", ctx.channel().remoteAddress(), cause);
        ctx.close(); // close 会触发 channelInactive，所以 remove 逻辑在上面统一处理
    }
}
