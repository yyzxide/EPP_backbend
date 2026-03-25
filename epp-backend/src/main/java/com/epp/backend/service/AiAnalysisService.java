package com.epp.backend.service;

import com.epp.backend.entity.SecCheckRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

/**
 * AI 安全日志分析服务
 *
 * 拉取设备最近的安全检查记录，喂给 LLM 分析威胁等级，SSE 流式返回。
 */
@Slf4j
@Service
public class AiAnalysisService {

    private final ChatClient chatClient;
    private final SecCheckService secCheckService;

    // ChatClient 只 build 一次，复用同一个实例
    public AiAnalysisService(ChatClient.Builder chatClientBuilder, SecCheckService secCheckService) {
        this.chatClient = chatClientBuilder.build();
        this.secCheckService = secCheckService;
    }

    private static final String SYSTEM_PROMPT = """
            你是一名资深安全运营分析师，专注于终端安全(EPP)领域。
            你的任务是分析终端设备的安全检查日志，给出：
            1. 威胁等级评估（Critical / High / Medium / Low / Info）
            2. 关键发现摘要
            3. 每条异常记录的风险分析
            4. 处置建议

            请用专业但简洁的语言输出，使用中文回答。
            """;

    public Flux<String> analyzeDeviceLogs(String deviceId, int limit) {
        List<SecCheckRecord> records = secCheckService.getRecentByDeviceId(deviceId, limit);

        if (records.isEmpty()) {
            return Flux.just("该设备暂无安全检查记录。");
        }

        String logsText = records.stream()
                .map(r -> String.format("[%s] 类型:%s 结果:%s 详情:%s",
                        r.getCheckTime(), r.getCheckType(), r.getResult(), r.getDetail()))
                .collect(Collectors.joining("\n"));

        String userMessage = String.format("""
                以下是设备 %s 最近 %d 条安全检查日志，请进行威胁分析：

                %s
                """, deviceId, records.size(), logsText);

        log.info("AI分析请求, deviceId:{}, 记录数:{}", deviceId, records.size());

        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userMessage)
                .stream()
                .content();
    }
}
