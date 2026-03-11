package com.epp.backend.service;

public interface StrategyService {

    /**
     * 获取策略配置 (走多级缓存架构: L1 Caffeine -> L2 Redis -> DB)
     * @param strategyId 策略ID
     * @return 策略内容 (JSON字符串)
     */
    String getStrategy(String strategyId);

}
