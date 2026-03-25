# EPP Backend 项目完整复习手册

> 面试前看这一篇就够了。

---

## 一、项目一句话定位

**基于 360 实际 EPP 产品架构，独立设计实现的终端安全管理平台后端。**
模拟管理真实企业内网中数千台终端设备的注册、在线状态、安全策略下发、安全事件采集全链路。

---

## 二、技术栈

| 层次 | 技术 |
|------|------|
| Web框架 | Spring Boot 3.3 + Spring MVC |
| 长连接网关 | Netty 4.1（自定义二进制协议） |
| 数据库 | MySQL 8 + MyBatis-Plus |
| 缓存 | Caffeine（L1本地）+ Redis 7（L2分布式） |
| 消息队列 | Kafka（安全日志异步批处理） |
| 认证 | JWT（HMAC-SHA256） |
| AI | Spring AI + DeepSeek API（OpenAI兼容接口）|
| 监控 | Micrometer + Prometheus |
| 部署 | Docker Compose |

---

## 三、核心架构

### 数据流总图

```
C++终端客户端
  │
  ├─ HTTP POST /api/device/register → 拿JWT Token
  │
  └─ TCP长连接 (port 8090, Netty)
       │
       ├─ type=4 (AUTH)   → JWT验证 → 身份绑定到Channel
       ├─ type=1 (心跳)   → HeartbeatHandler → Redis刷TTL + MySQL
       ├─ type=2 (安全上报)→ SecCheckHandler → Kafka → 批量入库MySQL
       └─ type=3 (拉策略) → 限流 → 三级缓存 → 策略推回客户端

策略更新 (PUT /api/strategy/{id})
  → MySQL写入 → 删缓存 → Redis Pub/Sub广播
  → 每个实例推送在线设备 → 设备重新拉取
```

### 消息在 Netty 中的流转

```
TCP字节流
  → LengthFieldBasedFrameDecoder  (解决粘包/拆包)
  → EppProtocolDecoder            (自定义协议解析)
  → DeviceChannelHandler          (JWT鉴权 + 业务分发)
  → businessThreadPool.submit()   (IO线程与业务线程隔离)
  → MessageDispatcher             (按type路由到对应Handler)
```

---

## 四、模块详解（面试核心）

### 4.1 Netty 长连接网关

**自定义协议格式：**
```
4字节(length) | 1字节(type) | 32字节(deviceId) | N字节(body JSON)
```

**为什么用自定义二进制协议而不是 HTTP？**
- HTTP是请求-响应模型，服务端无法主动推送（策略下发做不到）
- 自定义二进制协议包更小，适合万级设备高频心跳
- TCP长连接复用，省去每次握手开销

**粘包拆包怎么解决？**
用 `LengthFieldBasedFrameDecoder`，在消息头加4字节长度字段，Netty根据这个长度切包，保证每次 decode 拿到的是完整帧。

**JWT鉴权怎么防止身份伪造？**
- 第一条消息必须是 type=4（AUTH包），body 是 JWT Token
- 验证通过后把 deviceId **绑定到 `channel.attr()`** 上，后续所有消息从 attr 取 deviceId，不从消息体取
- 即使消息体里写了别人的 deviceId，服务端也无视，用的是鉴权时绑定的
- `DeviceChannelHandler` 上有 `@ChannelHandler.Sharable`，因此所有连接共享同一个 handler 实例，attr 存在 Channel 上而不是 Handler 上

**线程池隔离是怎么做的？**
- Netty Worker 线程只负责 decode 和路由，不碰任何 IO（DB/Redis）
- 收到消息后立刻 `businessThreadPool.submit(()->dispatch(...))` 异步提交
- 业务线程池：核心 CPU×2，最大 CPU×4，队列 10000，拒绝策略 AbortPolicy
- 为什么用 AbortPolicy 不用 CallerRunsPolicy？CallerRunsPolicy 会让调用者（Netty Worker）去执行任务，Worker 被卡住就无法处理其他9999台设备的IO事件

**僵尸连接怎么清理？**
`IdleStateHandler` 基于时间轮（HashedWheelTimer），60秒没收到任何数据触发 `READER_IDLE` 事件，服务端主动 `ctx.close()`，`close()` 自动触发 `channelInactive` → `channelManager.remove()`，完整闭环。

**快速重连竞态问题：**
```
时序问题：
  T1: 设备重连 → add(deviceId, newChannel) → map写入newChannel
  T2: 旧Channel的channelInactive触发 → remove(deviceId) → 把newChannel删了！

修复：channelManager.remove(deviceId, oldChannel)
  用 ConcurrentHashMap.remove(key, value)，原子CAS
  只有 map[deviceId] == oldChannel 才删，否则跳过
  newChannel安然无恙
```

---

### 4.2 三级缓存 + 防击穿

**缓存层次：**
```
L1: Caffeine（进程内存，访问不走网络，<1ms）
  → 未命中
L2: Redis（分布式，毫秒级，多实例共享）
  → 未命中
L3: MySQL（毫秒~百毫秒，来源）
```

**防缓存击穿（锁池）：**
```java
Object lock = lockMap.computeIfAbsent(strategyId, k -> new Object());
synchronized(lock) {
    // 二次检查
    if (caffeine.getIfPresent(key) != null) return;
    // 查DB，只有1个线程能进来
}
lockMap.remove(strategyId); // finally里清理，防止Map无限膨胀
```
- 100个并发打同一个冷key，只有1个线程穿到DB，有单测验证
- 为什么不用 `String.intern()`？intern 把字符串放常量池，GC回收不了，高并发下OOM风险
- 为什么不用 `synchronized(strategyId.intern())`？同上

**防缓存穿透（空值缓存）：**
key不存在时，在 Redis 和 Caffeine 里存 "EMPTY"，TTL 5分钟，下次请求直接返回404，不打DB。

**缓存更新策略（Cache Aside）：**
写策略时：先写MySQL → 删 Redis 缓存 → 删 Caffeine 缓存
不更新缓存，让下次读穿透进来，避免并发写导致旧值覆盖新值。

---

### 4.3 Kafka 异步处理（安全日志）

**为什么安全日志走Kafka，心跳不走？**
| | 心跳 | 安全日志 |
|--|------|---------|
| 时效性 | 极强（积压后处理无意义） | 弱（延迟几秒没影响）|
| 批处理收益 | 低（只更新一条Redis/MySQL） | 高（可以批量saveBatch）|
| 重试意义 | 低（设备可能已下线） | 高（日志不能丢）|

**三层防护：**
1. **Redis SETNX 幂等去重**：key = `topic:partition:offset`，TTL 24h，同一条消息只处理一次
2. **自动重试3次**：`FixedBackOff(1s间隔, 3次)`，处理失败自动重试
3. **死信队列**：3次重试失败 → `DeadLetterPublishingRecoverer` → 消息进 `epp.seccheck.dlq`，不丢失

**批量入库：**
每次 poll 最多50条（`max-poll-records=50`），过滤重复后 `saveBatch(batchRecords)` 一次性入库。

**失败时为什么要删幂等key？**
如果不删，下次重试时 SETNX 返回 false（以为处理过了），直接跳过，导致消息永久丢失。
删掉 key 后重试可以重新处理，实现 at-least-once 语义。

---

### 4.4 Redis Pub/Sub 集群广播

**问题背景：**
部署多个后端实例时，设备 A 连在实例1，设备 B 连在实例2。
策略更新请求打到实例1，实例1只能推送自己管理的设备 A，设备 B 收不到。
这就是"集群路由孤岛"问题。

**解决方案：**
```
策略更新 → Redis Pub/Sub 发布 "epp:strategy:update" channel
  ↓
所有实例都订阅了这个 channel（StrategyUpdateSubscriber）
  ↓
每个实例各自遍历自己的 channelManager，推送给本实例管理的设备
  ↓
所有在线设备都收到通知 ✓
```

**为什么不用 Kafka 做广播？**
Kafka 是点对点消费（每条消息只被消费组内一个实例消费），不适合广播。
Redis Pub/Sub 是真正的发布订阅，所有订阅者都能收到。

**Pub/Sub 的局限性？**
消息不持久化，如果某实例订阅线程挂掉会丢消息。
但策略推送是"最终一致性"场景：设备下次心跳时会重新拉策略，可以容忍偶发丢失。

---

### 4.5 设备在线状态

**在线判断逻辑：**
```
1. Redis有key epp:device:online:{deviceId} → 在线（TTL 90s，心跳60s刷新）
2. Redis无key但5分钟内有心跳记录 → 补写Redis，视为在线
3. 以上都不满足 → 离线
```

**为什么Redis写在 `TransactionSynchronization.afterCommit()` 里？**
MySQL 和 Redis 不在同一个事务里。如果先写Redis再提交MySQL，MySQL回滚了，Redis里已经有脏数据（设备显示在线但实际没注册成功）。
注册事务提交后回调，保证MySQL成功后才写Redis。

---

### 4.6 AI 安全分析

**接口：**
```
POST /api/ai/analyze-logs?deviceId=xxx&limit=20
```

**流程：**
1. 从MySQL查设备最近20条安全检查记录
2. 格式化成文本，拼接 System Prompt（安全分析师角色）
3. 调用 DeepSeek API（OpenAI兼容接口），stream模式
4. SSE逐字推回前端

**DeepSeek 为什么能用 Spring AI 的 OpenAI starter？**
DeepSeek 的 API 完全兼容 OpenAI 格式，只需要把 `base-url` 配置成 DeepSeek 的地址即可。
Spring AI 用 `ChatClient` 抽象了底层 API，切换模型只需改配置。

---

## 五、数据库设计

### device_info（设备表）
```sql
device_id   VARCHAR(64)  UNIQUE   -- 硬件唯一ID，C++端生成
os_type     VARCHAR(32)           -- Windows/Linux/MacOS
ip_address  VARCHAR(45)           -- 支持IPv6
strategy_version BIGINT           -- 当前生效策略版本（时间戳）
status      TINYINT               -- 0离线 1在线
last_heartbeat_time DATETIME      -- 最后心跳时间
索引：idx_device_id, idx_status
```

### strategy_config（策略表）
```sql
strategy_id VARCHAR(64)  UNIQUE   -- 策略标识
version     BIGINT                -- 时间戳版本号
config_json JSON                  -- 策略内容（黑白名单/扫描项等）
索引：idx_strategy_id, idx_version
```

### sec_check_record（安全检查记录表）
```sql
device_id   VARCHAR(64)           -- 设备ID
check_type  VARCHAR(64)           -- VIRUS_SCAN/USB_BLOCK/PROCESS_CHECK
result      VARCHAR(32)           -- CLEAN/THREAT/BLOCKED
detail      VARCHAR(1024)         -- 详情（威胁名/路径等）
check_time  DATETIME
索引：idx_device_id, idx_check_time  -- AI分析按device_id+check_time查询
```

---

## 六、监控指标

| 指标名 | 类型 | 含义 |
|--------|------|------|
| `epp.online.devices` | Gauge | 当前在线设备数 |
| `epp.cache.hit` | Counter | L1/L2缓存命中次数 |
| `epp.cache.miss` | Counter | 穿透到MySQL的次数 |
| `epp.strategy.push` | Counter | 策略推送次数 |
| `epp.business.threadpool.queue.size` | Gauge | 业务线程池队列积压 |
| `epp.business.threadpool.active.threads` | Gauge | 业务线程池活跃线程数 |

访问 `GET /actuator/prometheus` 拉取，接 Grafana 画图。

---

## 七、测试覆盖

| 测试类 | 测试点 | 核心验证 |
|--------|--------|---------|
| `StrategyServiceImplTest` | 高并发防击穿 | 100线程并发，DB只穿透1次 |
| `EppProtocolDecoderTest` | 协议解析 | 正常解包 + 不加LengthField必崩 |
| `DeviceChannelHandlerTest` | 身份绑定安全 | 身份不匹配强制断连 |
| `AdminTokenInterceptorTest` | 管理接口鉴权 | 无Token返回401 |
| `ChannelManagerConcurrencyTest` | 快速重连竞态 | 旧Channel inactive不删新Channel |

---

## 八、面试高频问题

**Q: 项目中Kafka和Netty是什么关系？**
> 两条完全独立的路径。Netty处理来自C++客户端的TCP长连接，心跳直接处理不经Kafka。安全日志经Netty接收后发到Kafka，由消费者批量入库。选择的核心依据是时效性：心跳过期无意义，安全日志延迟几秒可接受且批处理收益大。

**Q: 缓存击穿怎么处理的？**
> 用锁池（ConcurrentHashMap维护每个strategyId对应的锁对象），加synchronized双重检查。100个并发只有1个能进到查DB的代码块，其他等待拿到锁后发现Caffeine已有数据直接返回。有单测验证：100线程并发，DB的selectOne只被调用1次。

**Q: 集群部署时策略怎么推给所有设备？**
> Redis Pub/Sub。每个实例订阅同一个channel，策略更新时publish消息，所有实例都收到后各自向本实例管理的设备推送Netty消息，解决路由孤岛问题。

**Q: Kafka消费如何保证幂等性？**
> Redis SETNX，key是topic+partition+offset（Kafka内唯一），TTL 24h。同一条消息重投时SETNX返回false，直接跳过。处理失败时删除key，允许重试时重新处理，实现at-least-once语义。

**Q: 为什么不用CallerRunsPolicy？**
> CallerRunsPolicy让调用者线程去执行任务，这里的调用者是Netty Worker线程。Worker被业务任务卡住，就无法处理epoll事件，万台设备的IO全部堵死。用AbortPolicy抛弃任务并记日志，Worker线程不受影响。

**Q: 安全漏洞修了哪些？**
> 1. 身份伪造：将deviceId绑到channel.attr()，消息体里的deviceId不可信，用attr里的。2. 管理接口暴露：AdminTokenInterceptor保护PUT /strategy和POST /message/dispatch。3. 敏感配置：JWT Secret和Admin Token通过环境变量注入，不硬编码。

---

## 九、可以主动提的加分点

1. **"我在实际工作中接触了EPP的完整链路（终端注册→策略下发→安全检查→日志上报），后端是基于这个真实架构设计的，不是教程项目。"**

2. **"我有C++和Linux系统编程背景，所以在设计Netty这层时对TCP粘包、文件描述符泄漏、epoll线程模型有更深的理解。"**（ChannelManager的CAS remove、fd泄漏防护、不阻塞IO线程）

3. **"项目集成了Spring AI接入DeepSeek，做安全日志的LLM威胁分析，SSE流式返回，这个让我的后端项目同时覆盖了传统高并发和AI应用两个方向。"**

---

## 十、自己要知道的弱点

1. **没有真实压测数据**：如果被问"能支持多少设备？"，回答"基于Netty的NIO模型，理论上单实例可以支持万级长连接，具体瓶颈在业务线程池和DB写入，有wrk性能测试脚本但没有收集到生产级别的压测报告。"

2. **AI模块需要真实API Key才能跑**：如果被问"能不能跑给我看？"，说"AI分析需要配置DeepSeek API Key，其他模块docker-compose up就能跑。"

3. **没有分布式事务**：Kafka发送和本地消息的一致性用的是at-least-once + 幂等去重，不是exactly-once。如果追问exactly-once怎么做，说"需要Kafka事务+幂等生产者，目前业务场景安全日志允许偶发重复入库，幂等去重已经够用。"

---

*最后更新：2026-03-25*
