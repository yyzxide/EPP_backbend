package com.epp.backend.service;

public interface StrategyService {

    /**
     * 获取策略配置 (走多级缓存架构: L1 Caffeine -> L2 Redis -> DB)
     * @param strategyId 策略ID
     * @return 策略内容 (JSON字符串)
     */
    String getStrategy(String strategyId);

    /**
     * 更新策略配置 — 完整闭环：
     *   1. 写入 MySQL
     *   2. 主动清除 Redis + Caffeine 缓存（Cache Aside Pattern）
     *   3. 通过 Netty 向所有在线设备广播策略变更通知
     *
     * @param strategyId 策略ID
     * @param configJson 新的策略内容 (JSON字符串)
     */
    void updateStrategy(String strategyId, String configJson);
}
