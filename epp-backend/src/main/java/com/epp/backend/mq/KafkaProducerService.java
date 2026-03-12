package com.epp.backend.mq;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@Service
public class KafkaProducerService {
    private final KafkaTemplate<String,String> kafkaTemplate;

    public void sendMessage(String topic,String message)
    {
        kafkaTemplate.send(topic,message).whenComplete((sendResult,ex)->{
            if(ex!=null)
            {
                log.error("Kafka发送失败，topic:{}，message:{}",topic,message);
            }
            else{
                log.info("Kafka发送成功，topic:{}，message:{}",topic,message);
            }
        });
    }
}
