package com.epp.backend.mq;

import com.epp.backend.entity.SecCheckRecord;
import com.epp.backend.service.SecCheckService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 安全检查日志 Kafka 消费者
 *
 * 与 HeartbeatConsumer 相同的三层防护:
 * 1. Redis SETNX 幂等去重
 * 2. 重试3次 (KafkaConsumerConfig)
 * 3. 死信队列 epp.seccheck.dlq 兜底
 *
 * 额外优化: 批量入库 (MyBatis-Plus saveBatch)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SecCheckConsumer {

    private final SecCheckService secCheckService;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    private static final String IDEMPOTENT_KEY_PREFIX = "epp:mq:dedup:seccheck:";

    @KafkaListener(
            topics = "${epp.kafka.topic.seccheck}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "batchFactory"
    )
    public void consume(List<ConsumerRecord<String, String>> records) {
        log.info("批量收到安全检查消息, 数量: {}", records.size());

        List<SecCheckRecord> batchRecords = new ArrayList<>();
        List<String> processedKeys = new ArrayList<>();

        for (ConsumerRecord<String, String> record : records) {
            String idempotentKey = IDEMPOTENT_KEY_PREFIX + record.topic()
                    + ":" + record.partition() + ":" + record.offset();
            try {
                // 幂等去重
                Boolean absent = redisTemplate.opsForValue()
                        .setIfAbsent(idempotentKey, "1", 24, TimeUnit.HOURS);
                if (Boolean.FALSE.equals(absent)) {
                    log.debug("安全检查消息已处理过，跳过, key: {}", idempotentKey);
                    continue;
                }
                processedKeys.add(idempotentKey);

                SecCheckRecord checkRecord = objectMapper.readValue(record.value(), SecCheckRecord.class);
                if (checkRecord.getCheckTime() == null) {
                    checkRecord.setCheckTime(LocalDateTime.now());
                }
                batchRecords.add(checkRecord);

            } catch (Exception e) {
                // 回滚已标记的幂等key
                processedKeys.forEach(redisTemplate::delete);
                log.error("解析安全检查消息失败, offset: {}, message: {}, error: {}",
                        record.offset(), record.value(), e.getMessage());
                throw new RuntimeException("安全检查消息处理失败, 触发重试", e);
            }
        }

        // 批量入库
        if (!batchRecords.isEmpty()) {
            try {
                secCheckService.saveBatch(batchRecords);
                log.info("安全检查记录批量入库成功, 数量: {}", batchRecords.size());
            } catch (Exception e) {
                // 入库失败，回滚幂等key
                processedKeys.forEach(redisTemplate::delete);
                log.error("安全检查记录批量入库失败, 数量: {}", batchRecords.size(), e);
                throw new RuntimeException("批量入库失败, 触发重试", e);
            }
        }
    }
}
