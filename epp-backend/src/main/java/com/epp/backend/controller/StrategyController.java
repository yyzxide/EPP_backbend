package com.epp.backend.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.epp.backend.common.CommonResult;
import com.epp.backend.service.StrategyService;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import com.epp.backend.exception.BadRequestException;
import org.springframework.util.StringUtils;

@RestController
@RequestMapping("/api/strategy")
@RequiredArgsConstructor
public class StrategyController {
    private final StrategyService strategyService;

    /**
     * 查询策略配置（三级缓存：Caffeine → Redis → MySQL）
     * GET /api/strategy/{strategyId}
     */
    @GetMapping("/{strategyId}")
    public CommonResult<String> getStrategy(@PathVariable String strategyId) {
        String strategyJson = strategyService.getStrategy(strategyId);
        return CommonResult.success(strategyJson);
    }

    /**
     * 更新策略配置 — 完整闭环：写DB + 清缓存 + 广播推送
     * PUT /api/strategy/{strategyId}
     * Body: { "configJson": "..." }
     *
     * 管理员调用此接口 → Service 层完成三步闭环 → 所有在线设备收到 Netty 推送 → 设备主动拉取新配置
     */
    @PutMapping("/{strategyId}")
    public CommonResult<String> updateStrategy(
            @PathVariable String strategyId,
            @RequestBody UpdateStrategyRequest request) {
        if (request == null || !StringUtils.hasText(request.getConfigJson())) {
            throw new BadRequestException("configJson 不能为空");
        }
        strategyService.updateStrategy(strategyId, request.getConfigJson());
        return CommonResult.success("策略已更新并推送给所有在线设备");
    }

    /**
     * 更新策略请求体 DTO
     */
    @Data
    static class UpdateStrategyRequest {
        private String configJson;
    }
}
