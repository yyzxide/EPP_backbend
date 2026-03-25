# EPP Backend 性能测试报告模板

## 1. 测试环境

- OS:
- CPU:
- Memory:
- JDK:
- MySQL:
- Redis:
- Kafka:
- App Config:
- Test Date:

## 2. 测试结果总览

| Test | Goal | Command | Concurrency/Connections | Duration | QPS | Avg | P95 | P99 | Error Rate | Notes |
|---|---|---|---|---|---|---|---|---|---|---|
| heartbeat-http |  |  |  |  |  |  |  |  |  |  |
| register-http |  |  |  |  |  |  |  |  |  |  |
| strategy-l1 |  |  |  |  |  |  |  |  |  |  |
| strategy-l2 |  |  |  |  |  |  |  |  |  |  |
| strategy-breakdown |  |  |  |  |  |  |  |  |  |  |
| netty-connections |  |  |  |  |  |  |  |  |  |  |
| netty-heartbeat |  |  |  |  |  |  |  |  |  |  |
| threadpool-saturation |  |  |  |  |  |  |  |  |  |  |

## 3. 详细记录

### 3.1 HTTP Heartbeat

- Command:
- Goal:
- Result:
- CPU:
- Memory:
- GC:
- Redis:
- MySQL:
- Conclusion:

### 3.2 HTTP Register

- Command:
- Goal:
- Result:
- CPU:
- Memory:
- GC:
- Redis:
- MySQL:
- Conclusion:

### 3.3 Strategy Query L1

- Command:
- Goal:
- Result:
- CPU:
- Memory:
- GC:
- Redis:
- MySQL:
- Conclusion:

### 3.4 Strategy Query L2

- Command:
- Goal:
- Result:
- CPU:
- Memory:
- GC:
- Redis:
- MySQL:
- Conclusion:

### 3.5 Cache Breakdown

- Command:
- Goal:
- Result:
- Log Observation:
- MySQL Observation:
- Redis Observation:
- Conclusion:

### 3.6 Netty Connections

- Tool:
- Goal:
- Connections:
- CPU:
- Memory:
- Socket Count:
- Conclusion:

### 3.7 Netty Heartbeat

- Tool:
- Goal:
- Message Rate:
- CPU:
- Memory:
- Redis:
- MySQL:
- Conclusion:

### 3.8 Thread Pool Saturation

- Tool:
- Goal:
- Trigger Method:
- Rejected Tasks:
- Netty Health:
- Conclusion:

## 4. 最终总结

- Strongest Result:
- Weakest Link:
- Main Bottleneck:
- What Was Verified:
- What Still Needs Work:

## 5. 面试表达版本

```text
本项目我主要验证了 HTTP 心跳、策略查询、缓存击穿、Netty 长连接和线程池饱和几个核心场景。
其中策略查询在缓存命中下吞吐较高，缓存为空时数据库只发生极少次数查询，说明防缓存击穿逻辑生效。
另外我还验证了线程池饱和时不会把业务执行反压到 Netty I/O 线程，这对长连接网关的稳定性很关键。
```
