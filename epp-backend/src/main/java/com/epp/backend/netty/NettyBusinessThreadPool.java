package com.epp.backend.netty;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;

/**
 * Netty 业务线程池 — 将耗时业务逻辑从 Netty I/O 线程中解耦
 *
 * 【问题背景】
 * Netty 的 workerGroup（I/O 线程，数量 = CPU×2）负责：
 *   1. epoll 事件循环（监听所有连接的读写就绪）
 *   2. 调用 Pipeline 里的所有 Handler（包括 Decoder/Encoder/DeviceChannelHandler）
 *
 * 如果在 channelRead0 里直接执行：
 *   - kafkaTemplate.send()   （可能阻塞几十毫秒）
 *   - redisTemplate.get()    （可能阻塞几毫秒）
 *   - 数据库查询              （可能阻塞几十毫秒）
 *
 * 那么一个 workerGroup 线程就被这个请求"占住"了。
 * 如果同时有 500 个设备发来消息，16 个 Worker 线程全部阻塞，
 * 其他 99984 个在线设备的读写事件就全部得不到处理，整个网关瞬间瘫痪！
 *
 * 【解决方案】
 * 建立一个专用的"业务线程池"（Business Thread Pool）。
 * Netty Worker 线程只负责极快速的 Decode/Encode，
 * 一旦解析出 EppMessage，立刻把业务逻辑异步提交给这个线程池，
 * Worker 线程可以立即返回去处理其他连接的事件。
 *
 * C++类比: 相当于你在 epoll 线程里只做 recv() + 简单解析，
 *          然后把任务 push 到一个 task_queue，由另一组 worker 线程消费。
 */
@Component
@Slf4j
public class NettyBusinessThreadPool {

    /**
     * 业务线程池配置参数（可根据实际压测结果调整）：
     *
     * corePoolSize = CPU×2:   核心线程数，常驻内存，不会被回收
     * maximumPoolSize = CPU×4: 最大线程数，突发流量时扩容
     * keepAliveTime = 60s:    非核心线程空闲超过 60s 后销毁
     * queue = LinkedBlockingQueue(10000): 任务等待队列，最多积压 1 万个任务
     * threadFactory:          给线程起名字，方便排查 CPU 高占用时的线程 dump
     * rejectedHandler:        队列满了且线程数到上限时的拒绝策略：
     *                         CallerRunsPolicy = 让调用方（Netty Worker 线程）自己跑这个任务，
     *                         相当于降级处理，不直接丢弃。
     */
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            CPU_COUNT * 2,
            CPU_COUNT * 4,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(10000),
            new ThreadFactory() {
                private int counter = 0;
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r);
                    // 命名规范：方便 jstack/arthas 排查问题时快速识别
                    t.setName("epp-biz-" + (++counter));
                    t.setDaemon(true); // 守护线程：JVM 关闭时不阻塞退出
                    return t;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    /**
     * 将业务任务提交到线程池异步执行
     *
     * @param task 要异步执行的业务逻辑（Lambda 表达式传入）
     */
    public void submit(Runnable task) {
        executor.submit(() -> {
            try {
                task.run();
            } catch (Exception e) {
                // 业务线程里的异常必须在这里兜住，否则会吞掉异常，线程悄无声息地失败
                log.error("业务线程池执行任务异常", e);
            }
        });
    }

    /**
     * 获取线程池当前状态（可暴露给监控接口）
     */
    public String getStats() {
        return String.format(
                "业务线程池状态 | 活跃线程: %d / 核心: %d / 最大: %d | 队列积压: %d | 已完成任务: %d",
                executor.getActiveCount(),
                executor.getCorePoolSize(),
                executor.getMaximumPoolSize(),
                executor.getQueue().size(),
                executor.getCompletedTaskCount()
        );
    }
}