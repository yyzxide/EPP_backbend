package com.epp.backend.controller;

import com.epp.backend.service.AiAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * AI 安全分析接口
 *
 * POST /api/ai/analyze-logs?deviceId=xxx&limit=20
 * 拉取设备最近的安全检查记录，调用 LLM 分析威胁等级，SSE 流式返回。
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiAnalysisService aiAnalysisService;

    /**
     * 流式分析设备安全日志
     *
     * @param deviceId 设备ID
     * @param limit    拉取最近N条记录，默认20
     * @return SSE 流式响应 (text/event-stream)
     */
    @PostMapping(value = "/analyze-logs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> analyzeLogs(
            @RequestParam String deviceId,
            @RequestParam(defaultValue = "20") int limit) {
        log.info("收到AI分析请求, deviceId: {}, limit: {}", deviceId, limit);
        return aiAnalysisService.analyzeDeviceLogs(deviceId, limit);
    }
}
