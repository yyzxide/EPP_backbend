package com.epp.backend.monitor;

import com.epp.backend.netty.ChannelManager;
import com.epp.backend.netty.NettyBusinessThreadPool;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * EPP 业务指标埋点
 *
 * 设计思路：
 *   Spring Boot Actuator + Micrometer 自动暴露 JVM、HTTP、数据库连接池等基础指标。
 *   本类只负责我们项目特有的【业务指标】，注册到同一个 MeterRegistry，
 *   Prometheus 拉取时会一并输出。
 *
 * 三类核心指标：
 *   1. Gauge（仪表盘）— 实时值，如"当前在线设备数"、"线程池队列积压"
 *      C++类比：相当于定时采样一个全局变量的当前值
 *
 *   2. Counter（计数器）— 单调递增，如"缓存命中次数"、"缓存穿透次数"
 *      C++类比：相当于一个 atomic<long> 只做 ++
 *
 *   3. Timer（计时器，本类暂未实现）— 记录耗时分布，如"策略查询 P99 延迟"
 *      后续可在 StrategyServiceImpl.getStrategy() 里用 Timer.record() 埋点
 *
 * Grafana 配置：
 *   数据源：Prometheus → http://localhost:9090
 *   面板示例 PromQL：
 *     在线设备数：epp_online_devices_total
 *     线程池积压：epp_business_threadpool_queue_size
 *     缓存命中率：rate(epp_cache_hit_total[1m]) / (rate(epp_cache_hit_total[1m]) + rate(epp_cache_miss_total[1m]))
 */
@Slf4j
@Component
public class EppMetrics {

    // ---- 缓存命中/穿透计数器 ----
    // Counter 是线程安全的，可以在任意线程 increment()
    private final Counter cacheHitCounter;
    private final Counter cacheMissCounter;

    // ---- 策略更新推送计数器 ----
    private final Counter strategyPushCounter;

    public EppMetrics(MeterRegistry registry,
                      ChannelManager channelManager,
                      NettyBusinessThreadPool businessThreadPool) {

        // 1. 在线设备数 Gauge
        //    Gauge 是"拉取式"的：每次 Prometheus 来抓取时，才调用 lambda 计算当前值。
        //    不像 Counter 需要手动 increment()，Gauge 是被动的，绑定到数据源。
        //    C++类比：相当于给 Prometheus 注册了一个回调函数指针，每次采样时回调取值。
        Gauge.builder("epp.online.devices", channelManager, ChannelManager::getOnlineCount)
                .description("当前通过 Netty 长连接在线的终端设备数量")
                .register(registry);

        // 2. 业务线程池队列积压 Gauge
        //    队列积压 > 0 说明线程池已饱和，业务在排队等待处理。
        //    积压持续增长 → 告警 → 扩容线程池或优化业务逻辑。
        ThreadPoolExecutor executor = businessThreadPool.getExecutor();
        Gauge.builder("epp.business.threadpool.queue.size", executor,
                      e -> e.getQueue().size())
                .description("Netty 业务线程池待处理队列积压任务数")
                .register(registry);

        Gauge.builder("epp.business.threadpool.active.threads", executor,
                      ThreadPoolExecutor::getActiveCount)
                .description("Netty 业务线程池当前活跃线程数")
                .register(registry);

        // 3. 缓存命中/穿透计数器
        //    命中率 = hit / (hit + miss)，在 Grafana 里用 PromQL 计算：
        //    rate(epp_cache_hit_total[1m]) / (rate(epp_cache_hit_total[1m]) + rate(epp_cache_miss_total[1m]))
        cacheHitCounter = Counter.builder("epp.cache.hit")
                .description("策略缓存命中次数（Caffeine L1 或 Redis L2）")
                .tag("cache", "strategy")
                .register(registry);

        cacheMissCounter = Counter.builder("epp.cache.miss")
                .description("策略缓存穿透次数（需要查询 MySQL L3）")
                .tag("cache", "strategy")
                .register(registry);

        // 4. 策略推送计数器
        strategyPushCounter = Counter.builder("epp.strategy.push")
                .description("策略变更通知推送次数（每次 updateStrategy 广播计 1 次）")
                .register(registry);

        log.info("EPP 业务监控指标已注册到 MeterRegistry，访问 /actuator/prometheus 查看");
    }

    // ---- 对外暴露的埋点方法，供 Service 层调用 ----

    /**
     * 缓存命中埋点（在 StrategyServiceImpl.getStrategy() 的 L1/L2 命中分支调用）
     */
    public void recordCacheHit() {
        cacheHitCounter.increment();
    }

    /**
     * 缓存穿透埋点（在 StrategyServiceImpl.getStrategy() 的 L3 MySQL 查询分支调用）
     */
    public void recordCacheMiss() {
        cacheMissCounter.increment();
    }

    /**
     * 策略推送埋点（在 StrategyServiceImpl.updateStrategy() 广播完成后调用）
     */
    public void recordStrategyPush() {
        strategyPushCounter.increment();
    }
}