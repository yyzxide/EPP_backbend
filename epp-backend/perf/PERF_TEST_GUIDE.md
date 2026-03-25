# EPP Backend 压测执行指南

## 1. 目的

这份文档用于指导你后续对 `epp-backend` 做一轮完整、可复用、可用于面试表达的性能测试。

目标不是单纯跑一个 QPS 数字，而是得到以下几类有价值的数据：

- HTTP 接口吞吐与延迟
- 缓存命中与缓存击穿效果
- Netty 长连接在线规模与消息吞吐
- 线程池饱和时的退化行为
- MySQL、Redis、Kafka 是否成为瓶颈

最后你要拿到的不只是“我测过”，而是“我有一套完整的测试过程和结果解释”。

---

## 2. 建议目录

本目录下已经给你准备了这些文件：

- `perf/PERF_TEST_GUIDE.md`
- `perf/PERF_REPORT_TEMPLATE.md`
- `perf/wrk/register.lua`
- `perf/wrk/heartbeat.lua`
- `perf/wrk/strategy_update.lua`

你后续执行测试时，建议把最终结果再整理到：

- `perf/PERF_REPORT.md`

---

## 3. 测试前准备

### 3.1 固定测试环境

每次测试都要先记录环境，避免后续数据无法比较。

至少记录这些信息：

- 操作系统版本
- CPU 核数
- 内存大小
- JDK 版本
- MySQL 版本
- Redis 版本
- Kafka 版本
- 应用启动参数

建议保存如下信息：

```bash
uname -a
nproc
free -h
java -version
mysql --version
redis-server --version
kafka-topics.sh --version
```

### 3.2 应用配置要求

执行测试前，至少确认：

- MySQL、Redis、Kafka 都已启动
- `schema.sql` 已正确初始化数据库
- 应用可正常启动
- 已配置环境变量：

```bash
export EPP_JWT_SECRET='change-me-to-a-real-secret-key-with-at-least-32-bytes'
export EPP_ADMIN_TOKEN='change-me-admin-token'
```

### 3.3 测试前初始化数据

建议先准备一条可用策略，例如 `S1001`。

可以通过接口创建：

```bash
curl -X PUT "http://127.0.0.1:8080/api/strategy/S1001" \
  -H "Content-Type: application/json" \
  -H "X-Admin-Token: ${EPP_ADMIN_TOKEN}" \
  -d '{"configJson":"{\"rules\":\"BLOCK\",\"version\":1}"}'
```

然后验证读取：

```bash
curl "http://127.0.0.1:8080/api/strategy/S1001"
```

### 3.4 安装压测工具

建议优先使用 `wrk`。

Ubuntu/Debian 可尝试：

```bash
sudo apt-get update
sudo apt-get install -y wrk
```

如果没法直接安装，也可以用 `hey` 作为补充。

---

## 4. 测试时必须同时观察的指标

压测时不要只盯着 `wrk` 输出。至少同时开 4 个终端观察。

### 4.1 应用进程

先找到 Java 进程：

```bash
jps -l
```

记下 PID，然后执行：

```bash
top -p <pid>
```

### 4.2 GC 情况

```bash
jstat -gcutil <pid> 1s
```

重点看：

- Young GC 是否频繁
- Full GC 是否出现
- Old 区是否持续上涨

### 4.3 系统层面

```bash
vmstat 1
```

如果装了 `sysstat`，再开：

```bash
iostat -x 1
```

### 4.4 网络连接数

```bash
ss -s
```

如果做 Netty 长连接测试，再看：

```bash
ss -ant | grep 8090 | wc -l
```

### 4.5 Redis

```bash
redis-cli info stats
redis-cli info memory
```

### 4.6 MySQL

进入 MySQL 后执行：

```sql
show processlist;
show global status like 'Threads%';
show global status like 'Questions';
```

---

## 5. 测试执行顺序

建议严格按这个顺序执行：

1. HTTP 心跳接口
2. HTTP 策略查询 L1 命中
3. HTTP 策略查询 L2 命中
4. 缓存击穿测试
5. HTTP 注册接口
6. Netty 在线连接数测试
7. Netty 心跳吞吐测试
8. 线程池饱和测试

原因：

- 先拿到最稳定、最容易产出的数据
- 再逐步增加复杂度
- 避免一开始就卡在 Netty 压测客户端上

---

## 6. HTTP 心跳接口测试

### 6.1 接口

`POST /api/device/heartbeat`

### 6.2 目的

- 测试 Redis + MySQL 更新链路的吞吐
- 这是最接近真实业务的 HTTP 接口

### 6.3 使用脚本

脚本文件：

- `perf/wrk/heartbeat.lua`

### 6.4 命令

先跑低压：

```bash
wrk -t2 -c50 -d30s -s perf/wrk/heartbeat.lua http://127.0.0.1:8080
```

再逐步提高：

```bash
wrk -t4 -c100 -d60s -s perf/wrk/heartbeat.lua http://127.0.0.1:8080
wrk -t4 -c200 -d60s -s perf/wrk/heartbeat.lua http://127.0.0.1:8080
wrk -t8 -c500 -d60s -s perf/wrk/heartbeat.lua http://127.0.0.1:8080
```

### 6.5 记录项

- Requests/sec
- Avg latency
- Stdev latency
- Max latency
- CPU
- Redis 请求量
- MySQL 压力
- 错误率

### 6.6 你最后要总结什么

例如：

```text
HTTP heartbeat 在 200 并发下可稳定运行，QPS 为 xxxx，P95 为 xx ms，错误率为 0。
```

---

## 7. HTTP 注册接口测试

### 7.1 接口

`POST /api/device/register`

### 7.2 目的

- 测试写 MySQL + 生成 JWT 的吞吐
- 这个接口可以体现“写路径”能力

### 7.3 使用脚本

脚本文件：

- `perf/wrk/register.lua`

### 7.4 命令

```bash
wrk -t2 -c50 -d30s -s perf/wrk/register.lua http://127.0.0.1:8080
wrk -t4 -c100 -d60s -s perf/wrk/register.lua http://127.0.0.1:8080
wrk -t4 -c200 -d60s -s perf/wrk/register.lua http://127.0.0.1:8080
```

### 7.5 注意事项

- 这个脚本会不断生成新的 `deviceId`
- 测试前确认数据库磁盘空间和表数据量增长情况

### 7.6 总结目标

例如：

```text
注册接口是写路径，性能比纯查询接口低，但在 xx 并发下仍可稳定维持 xx QPS。
```

---

## 8. HTTP 策略查询测试

### 8.1 接口

`GET /api/strategy/{strategyId}`

### 8.2 目的

- 验证三级缓存效果
- 比较 Caffeine、Redis、MySQL 三种路径的性能差异

你要分 3 个场景测。

### 8.3 场景 A：L1 Caffeine 命中

#### 准备

先查几次，让应用内存中有缓存：

```bash
curl http://127.0.0.1:8080/api/strategy/S1001
curl http://127.0.0.1:8080/api/strategy/S1001
curl http://127.0.0.1:8080/api/strategy/S1001
```

#### 命令

```bash
wrk -t4 -c100 -d60s http://127.0.0.1:8080/api/strategy/S1001
wrk -t4 -c200 -d60s http://127.0.0.1:8080/api/strategy/S1001
wrk -t8 -c500 -d60s http://127.0.0.1:8080/api/strategy/S1001
```

#### 你要观察

- 极限 QPS
- P95/P99 是否很低
- MySQL 与 Redis 是否几乎没有压力

### 8.4 场景 B：L2 Redis 命中

#### 准备

推荐做法：

1. 先访问一次策略，让 Redis 有值
2. 重启应用，清空 Caffeine
3. 不清 Redis
4. 再开始压测

重启后直接执行：

```bash
wrk -t4 -c100 -d60s http://127.0.0.1:8080/api/strategy/S1001
wrk -t4 -c200 -d60s http://127.0.0.1:8080/api/strategy/S1001
```

#### 你要比较

- 与 L1 命中相比，QPS 差多少
- 延迟增加多少

### 8.5 场景 C：L3 MySQL 命中

#### 准备

先删除 Redis key：

```bash
redis-cli del strategy:S1001
```

然后重启应用，确保 Caffeine 也为空。

#### 命令

不要一上来高并发，先用低并发：

```bash
wrk -t2 -c10 -d30s http://127.0.0.1:8080/api/strategy/S1001
wrk -t2 -c20 -d30s http://127.0.0.1:8080/api/strategy/S1001
```

#### 你要看什么

- 单次查询延迟
- MySQL 压力
- 回填 Redis/Caffeine 后性能是否恢复

---

## 9. 缓存击穿测试

### 9.1 目的

证明“多个并发同时请求同一个冷 key 时，数据库不会被同时打爆”。

这个测试是你项目里非常值得讲的亮点。

### 9.2 测试思路

1. 数据库中确保存在 `S1001`
2. 删除 Redis key
3. 重启应用，确保 Caffeine 为空
4. 瞬时高并发打同一个策略 key

### 9.3 命令

如果安装了 `hey`，建议这样：

```bash
hey -n 1000 -c 100 http://127.0.0.1:8080/api/strategy/S1001
```

如果只有 `wrk`，也可以：

```bash
wrk -t4 -c100 -d10s http://127.0.0.1:8080/api/strategy/S1001
```

### 9.4 重点不是只看 QPS

你要重点观察：

- 后台日志里 `L3 MySQL 命中` 出现几次
- MySQL 查询量增长是否很小
- Redis 和 Caffeine 是否被快速回填

### 9.5 最终结论怎么写

理想表达：

```text
在缓存为空的情况下，100 并发请求同一个 strategyId，数据库只发生极少次数查询，说明锁池 + 双重检查的防缓存击穿逻辑生效。
```

如果你观察到只查 1 次，那就是非常漂亮的结论。

---

## 10. Netty 在线连接数测试

### 10.1 目的

- 测试服务能维持多少长连接
- 观察连接数增长时 CPU、内存、FD、网络状态

### 10.2 推荐做法

这个测试不建议用通用压测工具，建议你自己写一个简单 TCP 客户端，或者后续再单独补一个测试程序。

客户端至少要支持：

- 建立 TCP 连接到 `8090`
- 发送鉴权包 `type=4`
- 保持连接不断开

### 10.3 逐步提升连接数

建议按这个节奏做：

- 100
- 500
- 1000
- 2000
- 5000

每提升一档，至少稳定观察 2 到 5 分钟。

### 10.4 观察项

- 应用 CPU
- 应用内存
- `ss -s`
- `ss -ant | grep 8090 | wc -l`
- 是否出现异常断连
- 日志是否异常增多

### 10.5 最终要回答什么

例如：

```text
单机在当前环境下可稳定维持 xxxx 个 Netty 长连接，CPU 和内存在可接受范围内。
```

---

## 11. Netty 心跳吞吐测试

### 11.1 目的

- 测试 Netty 消息入口 + 业务线程池 + Redis/MySQL 的综合能力

### 11.2 测试方式

在已经建立好一批连接后，让每个连接定期发送 `type=1` 心跳包。

建议分三档：

- 1000 连接，每秒总共 1000 条心跳
- 1000 连接，每秒总共 2000 条心跳
- 2000 连接，每秒总共 2000 条心跳

### 11.3 关注点

- 是否有连接被异常断开
- 业务线程池是否堆积
- Redis 和 MySQL 是否成为瓶颈
- CPU 是否快速打满

### 11.4 结论写法

例如：

```text
在 xxxx 连接、每秒 xxxx 条心跳消息下，服务可稳定运行，未出现明显异常断连或线程池失控。
```

---

## 12. 策略拉取与限流测试

### 12.1 目的

- 验证策略拉取的真实吞吐
- 验证单设备限流是否生效

### 12.2 测试方法

分两组客户端：

- 正常组：按合理频率发送 `type=3`
- 恶意组：高频发送 `type=3`

### 12.3 你要观察

- 正常组是否仍能拿到返回结果
- 恶意组是否被限流
- 后台是否出现限流日志
- Redis / MySQL 是否被异常放大

### 12.4 面试里怎么讲

```text
项目对策略拉取做了单设备维度的令牌桶限流，在异常高频请求下可阻断恶意流量，保护后端资源。
```

---

## 13. 线程池饱和测试

### 13.1 目的

验证线程池满载时，系统是否还能保护 Netty I/O 线程。

### 13.2 测试方法

为了更容易观察，建议你本地临时把线程池参数调小，例如：

- 核心线程数改小
- 最大线程数改小
- 队列长度改小

然后大量发送耗时业务请求，例如：

- 高频策略拉取
- 高频安全检查上报

### 13.3 重点观察

- 是否出现任务拒绝日志
- 是否出现 I/O 线程卡死
- Netty 连接是否还活着
- 服务是否还能响应其他轻量请求

### 13.4 最终要形成的结论

```text
在线程池饱和时，系统会拒绝部分业务任务并记录日志，但不会将业务执行反压到 Netty I/O 线程，从而保护网关整体存活性。
```

---

## 14. 每次测试都必须记录的内容

你可以直接复制下面的模板到自己的测试报告里。

```text
测试名称：
测试目标：
测试环境：
测试命令：
并发/连接数：
持续时间：
QPS：
平均延迟：
P95：
P99：
错误率：
CPU：
内存：
GC：
Redis 状态：
MySQL 状态：
备注：
```

---

## 15. 最低交付标准

如果你不想一次测太多，至少先完成下面 4 项：

1. `POST /api/device/heartbeat`
2. `GET /api/strategy/S1001` 的 L1 命中
3. `GET /api/strategy/S1001` 的 Redis 命中
4. 缓存击穿测试

只要这 4 项做完，你就已经能拿出一版很像样的性能说明。

---

## 16. 面试中建议怎么表达

你最终不要只报数字，而要用“场景 + 结果 + 原因”来讲。

例如：

```text
我重点测了心跳接口、策略查询和缓存击穿场景。
策略查询在 Caffeine 命中下 QPS 很高，Redis 命中场景延迟略高，但仍稳定。
缓存为空时，我做了 100 并发同 key 压测，数据库只发生极少次数查询，说明防缓存击穿逻辑生效。
另外我还验证了线程池饱和时不会拖慢 Netty I/O 线程，这部分是我专门调整过回压策略的结果。
```

这样的表达比单说“我测了 QPS”强很多。
