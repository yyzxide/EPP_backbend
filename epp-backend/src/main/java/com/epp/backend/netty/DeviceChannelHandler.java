package com.epp.backend.netty;

import com.epp.backend.config.JwtUtils;
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
    private final JwtUtils jwtUtils;

    /**
     * AttributeKey — Netty 给每个 Channel 提供的"附加属性"机制
     * C++类比: 相当于给 socket fd 绑定一个 void* userData
     * 这里我们把 deviceId 绑上去，这样 channelInactive 时就能取回来
     */
    private static final AttributeKey<String> DEVICE_ID_KEY = AttributeKey.valueOf("deviceId");
    private static final AttributeKey<Boolean> AUTH_KEY = AttributeKey.valueOf("isAuthed");

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, EppMessage msg) throws Exception {
        // 1. 【安全关卡】检查当前连接是否已经过 JWT 鉴权
        Boolean isAuthed = ctx.channel().attr(AUTH_KEY).get();

        if (isAuthed == null || !isAuthed) {
            // 未通过鉴权的连接，强制要求第一条消息必须是 AUTH 类型 (Type=4)
            if (msg.getType() == 4) {
                try {
                    // 验证 Token (body)，非法或过期会抛出异常
                    String deviceId = jwtUtils.validateAndGetId(msg.getBody());

                    // 验证通过，标记为已授权，并绑定设备身份
                    ctx.channel().attr(AUTH_KEY).set(true);
                    ctx.channel().attr(DEVICE_ID_KEY).set(deviceId);

                    // 注册到全局管理器
                    channelManager.add(deviceId, ctx.channel());
                    log.info("设备 [{}] 鉴权成功，允许接入", deviceId);
                    return; // 鉴权包不走后续业务分发
                } catch (Exception e) {
                    log.warn("设备鉴权失败，非法 Token，强制断开: {}, addr: {}", msg.getBody(), ctx.channel().remoteAddress());
                    ctx.close(); 
                    return;
                }
            } else {
                // 未鉴权却发了业务包，直接判定为非法攻击
                log.warn("非法尝试：未鉴权即发送业务数据, type: {}, addr: {}", msg.getType(), ctx.channel().remoteAddress());
                ctx.close();
                return;
            }
        }

        // 2. 已通过鉴权的连接，正常处理业务逻辑
        String deviceId = msg.getDeviceId();
        log.info("收到终端消息, deviceId: {}, type: {}", deviceId, msg.getType());

        // 3. 将协议中的 byte 类型转换为业务中的 String 类型
        String typeStr = switch (msg.getType()) {
            case 1 -> "HEARTBEAT";
            case 2 -> "SEC_CHECK";
            case 3 -> "STRATEGY_PULL";
            default -> "UNKNOWN";
        };

        // 4. 【线程池隔离】将耗时的业务分发逻辑异步提交给业务线程池
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
