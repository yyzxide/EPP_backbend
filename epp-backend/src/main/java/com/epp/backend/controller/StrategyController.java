package com.epp.backend.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.epp.backend.common.CommonResult;
import com.epp.backend.service.StrategyService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/strategy")
@RequiredArgsConstructor
public class StrategyController {
    private final StrategyService strategyService;

    @GetMapping("/{strategyId}")
    public CommonResult<String> getStrategy(@PathVariable String strategyId) {
        String strategyJson = strategyService.getStrategy(strategyId);
        return CommonResult.success(strategyJson);
    }
}
