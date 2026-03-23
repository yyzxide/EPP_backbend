# EPP Backend 项目审查与本轮修复总结

## 1. 文档目的

这份文档用于系统总结本轮对 `epp-backend` 项目的审查结论与修复动作，重点回答三个问题：

1. 项目原先有哪些关键问题。
2. 本轮具体修改了哪些内容，为什么要这么改。
3. 这些修改体现了怎样的工程能力，以及后续还可以继续补哪些点。

这不是简单的“改动清单”，而是一次完整的工程复盘。你后续无论是自己回顾、准备面试，还是继续迭代项目，都可以把这份文档当作基线材料。

---

## 2. 项目原始状态概览

项目技术栈整体是比较完整的，属于“方向对了，但工程闭环还不够”的状态。

原项目包含这些核心组件：

- `Spring Boot 3`
- `MyBatis-Plus`
- `Redis`
- `Kafka`
- `Netty`
- `Micrometer + Prometheus`
- `JWT`

从设计意图上看，项目希望实现一套终端管理/策略下发/安全检查上报的后端系统，具备以下能力：

- 设备通过 HTTP 注册并获取 JWT
- 设备通过 Netty 长连接接入
- 后端处理心跳、安全检查、策略拉取
- 策略支持 Caffeine + Redis + MySQL 三级缓存
- 策略更新支持通过 Redis Pub/Sub 广播
- 暴露基础监控指标

如果只看“有无这些组件”，这个项目已经比很多初级项目更完整。但从工程质量看，原始版本主要问题在于：

- 安全边界不够严密
- 数据模型和数据库契约不一致
- 接口错误语义不清楚
- 线程池回压策略会反噬 Netty I/O 线程
- 自动化测试覆盖意识有，但可运行性和关键场景覆盖不够

也就是说，原项目不是“没思路”，而是“思路有了，但离可上线还有明显距离”。

---

## 3. 本轮修复的总体目标

本轮修改不是做新功能，而是围绕“可上线性”和“工程可信度”做加固，目标可以概括成 5 点：

1. 修复高危安全问题，堵住身份冒用和管理接口裸奔。
2. 统一代码与数据库的数据契约，避免上线即炸。
3. 让接口返回正确的错误语义，方便调用方和监控系统识别问题。
4. 修复并发场景下的错误回压策略，保护 Netty 事件线程。
5. 用自动化测试把关键修复场景覆盖住，避免回归。

---

## 4. 本轮修复的详细内容

### 4.1 修复 Netty 连接身份冒用问题

#### 原问题

设备通过 JWT 鉴权后，服务端确实会把 `deviceId` 绑定到 `Channel.attr()` 上。

但鉴权完成后的业务消息处理中，代码又重新使用了报文里的 `msg.getDeviceId()`：

- 心跳分发用的是报文字段
- 策略拉取限流键用的是报文字段
- 消息分发用的也是报文字段

这意味着只要攻击者拿到一个合法 token，就可以在后续消息里伪造任意 `deviceId`，造成以下风险：

- 冒充其他设备上报心跳
- 冒充其他设备拉取策略
- 污染安全检查记录
- 绕过按设备维度的限流

这是一个典型的“鉴权做了，但身份边界没有贯彻到底”的问题。

#### 修改方案

在 `DeviceChannelHandler` 中完成以下修复：

- 鉴权成功后继续把真实 `deviceId` 绑定到 channel 上
- 后续所有业务消息只认 channel 上的身份
- 如果报文里带了 `deviceId`，且与鉴权身份不一致，立即断开连接
- 如果 channel 上根本没有鉴权身份，也立即断开连接

#### 修改效果

现在身份链路变成：

`token -> validateAndGetId() -> channel.attr(deviceId) -> 后续业务统一使用该身份`

这样一来，消息体里的 `deviceId` 不再是可信身份来源，只能视为冗余字段或兼容字段。

#### 涉及文件

- [DeviceChannelHandler.java](/home/sid/Epp/epp-backend/src/main/java/com/epp/backend/netty/DeviceChannelHandler.java)

---

### 4.2 给管理接口增加鉴权保护

#### 原问题

原项目中两个管理能力完全暴露在外：

- `PUT /api/strategy/{strategyId}`
- `POST /api/message/dispatch`

这两个接口本质上都是“后台管理/控制平面”接口：

- 一个能改策略
- 一个能往消息分发链路里塞业务消息

如果没有鉴权，任何人都可以：

- 推送恶意策略
- 伪造内部消息
- 干扰设备控制链路

这在真实后端场景里是不可接受的。

#### 修改方案

新增了一个轻量级管理口令拦截器 `AdminTokenInterceptor`：

- 使用请求头 `X-Admin-Token`
- 从配置 `epp.admin.token` 中读取服务端管理口令
- 对 `POST /api/message/dispatch` 和 `PUT /api/strategy/*` 做拦截
- `GET /api/strategy/*` 不拦截，保持设备拉取策略能力

同时新增 `WebMvcConfig` 注册拦截器。

#### 修改效果

管理接口不再裸奔，接口分成两类：

- 设备读接口：允许访问
- 管理写接口：必须携带管理口令

#### 当前设计说明

这里没有直接引入 Spring Security 全家桶，而是使用一个足够轻量的拦截器方案，原因是：

- 当前项目原本没有完整用户/角色体系
- 本轮目标是快速修复高危暴露面
- 轻量拦截器足够完成“先把门关上”

如果后续继续工程化，推荐再升级为：

- Spring Security
- 管理员用户体系
- RBAC 权限模型
- 审计日志

#### 涉及文件

- [AdminTokenInterceptor.java](/home/sid/Epp/epp-backend/src/main/java/com/epp/backend/security/AdminTokenInterceptor.java)
- [WebMvcConfig.java](/home/sid/Epp/epp-backend/src/main/java/com/epp/backend/config/WebMvcConfig.java)
- [application.yml](/home/sid/Epp/epp-backend/src/main/resources/application.yml)

---

### 4.3 修复敏感配置硬编码问题

#### 原问题

原配置中存在两个明显风险：

- JWT secret 写死在配置文件里
- MySQL 默认账号密码是 `root/root`

其中最敏感的是 JWT secret。只要 secret 泄露，任何人都可以伪造合法 token。

#### 修改方案

将 JWT secret 改为优先从环境变量读取：

- `EPP_JWT_SECRET`

同时新增管理接口口令配置：

- `EPP_ADMIN_TOKEN`

#### 修改效果

现在项目具备了更合理的部署方式：

- 开发环境可以走默认值或本地环境变量
- 生产环境必须注入独立 secret 和管理口令

#### 说明

这次没有顺手改数据库默认账号密码，是因为这更接近部署配置治理，而不是代码逻辑问题。后续建议在部署文档里明确：

- 禁止生产环境使用默认数据库账号
- 禁止生产环境使用默认 JWT secret
- 禁止 `EPP_ADMIN_TOKEN` 为空

#### 涉及文件

- [application.yml](/home/sid/Epp/epp-backend/src/main/resources/application.yml)

---

### 4.4 修复策略表结构与代码模型不一致问题

#### 原问题

原项目策略业务代码是按 `strategyId` 查询和更新的，但数据库 `schema.sql` 里的 `strategy_config` 表没有 `strategy_id` 字段。

此外：

- Java 实体 `StrategyConfig.version` 是 `String`
- SQL 里 `version` 是 `BIGINT`

这种问题的严重性很高，因为它不是“代码风格问题”，而是“项目一跑到真实数据库就会报错”的契约错误。

#### 修改方案

统一代码和 DDL：

- 在 `strategy_config` 中新增 `strategy_id VARCHAR(64) NOT NULL UNIQUE`
- 将 `version` 保持为 `BIGINT`
- Java 实体中的 `version` 改成 `Long`
- 补充 `update_time`
- 增加 `strategy_id` 索引

#### 修改效果

现在数据契约已经一致：

- 代码查询字段存在于数据库中
- Java 类型与 SQL 类型一致
- 策略版本号能以时间戳形式正常维护

#### 这类问题的工程意义

这类修复特别能体现工程成熟度，因为它说明你在乎的是：

- 代码能否编译
- 更重要的是，代码、实体、DDL、运行时行为是否一致

很多项目在 demo 阶段看起来功能通了，但一接数据库就炸，问题通常就出在这里。

#### 涉及文件

- [StrategyConfig.java](/home/sid/Epp/epp-backend/src/main/java/com/epp/backend/entity/StrategyConfig.java)
- [schema.sql](/home/sid/Epp/epp-backend/src/main/resources/schema.sql)

---

### 4.5 强化策略更新接口的输入校验与版本维护

#### 原问题

原始实现对 `strategyId` 和 `configJson` 的输入校验不够严谨：

- 控制层没有真正阻止空配置
- 服务层也缺少明确的业务异常语义
- 更新策略时没有统一维护新版本号

#### 修改方案

在 `StrategyController` 和 `StrategyServiceImpl` 中补充：

- `strategyId` 不能为空
- `configJson` 不能为空
- 更新策略时，自动生成新的 `version = System.currentTimeMillis()`
- 插入和更新都会带上最新版本号

#### 修改效果

这样做有两个直接收益：

1. 避免空策略、脏策略写入数据库。
2. 让策略版本字段真正具备业务含义，而不是只是“表里有这个字段”。

#### 涉及文件

- [StrategyController.java](/home/sid/Epp/epp-backend/src/main/java/com/epp/backend/controller/StrategyController.java)
- [StrategyServiceImpl.java](/home/sid/Epp/epp-backend/src/main/java/com/epp/backend/service/impl/StrategyServiceImpl.java)

---

### 4.6 重构异常模型，返回正确的 HTTP 语义

#### 原问题

原项目的全局异常处理器会把几乎所有异常都返回成：

- `CommonResult.failed(...)`
- 逻辑上等价于 `500`

这会带来几个问题：

- 参数错误和服务器错误分不清
- 资源不存在和系统异常分不清
- 鉴权失败和业务失败分不清
- 前端、客户端、监控系统很难做精确判断

#### 修改方案

新增三类业务异常：

- `BadRequestException`
- `ResourceNotFoundException`
- `UnauthorizedException`

并改造 `GlobalExceptionHandler`：

- `BadRequestException -> 400`
- `UnauthorizedException -> 401`
- `ResourceNotFoundException -> 404`
- 其他运行时异常 -> 500`
- 未知异常 -> 500`

同时保留 `CommonResult` 的统一响应结构，只是状态码语义变得正确了。

#### 修改效果

现在接口错误已经具备更合理的对外语义：

- 调用方发错参数，不会再收到模糊的 500
- token 不合法会收到 401
- 设备/策略不存在会收到 404
- 真正的服务内部故障才是 500

#### 涉及文件

- [CommonResult.java](/home/sid/Epp/epp-backend/src/main/java/com/epp/backend/common/CommonResult.java)
- [GlobalExceptionHandler.java](/home/sid/Epp/epp-backend/src/main/java/com/epp/backend/exception/GlobalExceptionHandler.java)
- [BadRequestException.java](/home/sid/Epp/epp-backend/src/main/java/com/epp/backend/exception/BadRequestException.java)
- [ResourceNotFoundException.java](/home/sid/Epp/epp-backend/src/main/java/com/epp/backend/exception/ResourceNotFoundException.java)
- [UnauthorizedException.java](/home/sid/Epp/epp-backend/src/main/java/com/epp/backend/exception/UnauthorizedException.java)

同时以下业务类也已经切到新的异常语义：

- [JwtUtils.java](/home/sid/Epp/epp-backend/src/main/java/com/epp/backend/config/JwtUtils.java)
- [DeviceServiceImpl.java](/home/sid/Epp/epp-backend/src/main/java/com/epp/backend/service/impl/DeviceServiceImpl.java)
- [StrategyServiceImpl.java](/home/sid/Epp/epp-backend/src/main/java/com/epp/backend/service/impl/StrategyServiceImpl.java)

---

### 4.7 修复业务线程池的回压策略问题

#### 原问题

原线程池使用的是 `CallerRunsPolicy`。

这个策略在很多普通业务线程池里不是问题，但在当前项目里会带来一个非常严重的副作用：

- 当线程池满了
- 调用方线程会自己执行任务

而当前调用方线程恰恰是 Netty I/O 线程。

这意味着一旦业务线程池被打满，原本应该只负责收发包、快速分发的 Netty worker 线程，反而会被迫执行数据库/Redis/业务逻辑，导致：

- I/O 事件处理变慢
- 连接响应抖动
- 长连接系统吞吐骤降
- 极端情况下形成雪崩

换句话说，原本设计“线程池隔离”是为了保护 Netty，结果 `CallerRunsPolicy` 会在高压场景下把隔离打穿。

#### 修改方案

做了两个修改：

1. 拒绝策略改为 `AbortPolicy`
2. 在 `submit()` 中捕获 `RejectedExecutionException` 并记录日志

#### 修改效果

现在的行为变成：

- 线程池满时，任务被拒绝并记录
- 不会把业务执行反灌到 Netty I/O 线程
- 更符合“保护网关存活性优先”的原则

#### 设计权衡

这不是说丢任务一定比执行任务好，而是：

- 对长连接网关来说，先保住 I/O 线程活着，系统才不会整体雪崩
- 如果必须做可靠投递，应把任务交给更适合的异步队列或消息系统，而不是让 Netty 线程兜底

#### 涉及文件

- [NettyBusinessThreadPool.java](/home/sid/Epp/epp-backend/src/main/java/com/epp/backend/netty/NettyBusinessThreadPool.java)

---

### 4.8 给协议解码器增加最小长度防御

#### 原问题

原始 `EppProtocolDecoder` 直接读取：

- 1 字节 `type`
- 32 字节 `deviceId`
- 剩余字节作为 `body`

虽然在正式链路前面有 `LengthFieldBasedFrameDecoder`，但当前 decoder 自身没有最小长度校验，意味着：

- 它对上游 decoder 的正确性过度依赖
- 如果未来测试、复用、链路调整时绕开了上游 decoder，就可能触发异常

#### 修改方案

在 decoder 开始处增加：

- `readableBytes() < 33` 则直接抛 `DecoderException`

#### 修改效果

这样 decoder 自己也具备了基本的防御能力，不再完全依赖调用方保证输入合法。

#### 工程意义

这类改动很小，但很有代表性。它体现的是“组件自身要有边界感”，而不是“我默认别人都不会传错”。

#### 涉及文件

- [EppProtocolDecoder.java](/home/sid/Epp/epp-backend/src/main/java/com/epp/backend/netty/EppProtocolDecoder.java)

---

### 4.9 修复 Mockito 在当前 JDK 21 环境下的测试问题

#### 原问题

执行 `mvn test` 时，原项目测试失败，原因不是业务断言失败，而是 Mockito 在当前 JDK 21/WSL 环境下无法正常初始化 inline mock maker。

这类问题的影响是：

- 测试不是“不够多”，而是“根本跑不起来”
- 本地验证和 CI 都会受影响

#### 修改方案

新增测试资源文件：

- `src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker`

内容为：

- `mock-maker-subclass`

#### 修改效果

测试环境恢复可运行，`mvn test` 能正常通过。

#### 涉及文件

- [org.mockito.plugins.MockMaker](/home/sid/Epp/epp-backend/src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker)

---

### 4.10 补齐关键自动化测试

#### 原问题

虽然项目原本已经有测试意识，但关键问题没有形成回归测试保护。最典型的是：

- Netty 身份冒用问题没有测试
- 管理接口鉴权没有测试

如果没有测试，这些问题后续很容易被再次改坏。

#### 新增测试 1：Netty 身份绑定测试

新增 `DeviceChannelHandlerTest`，覆盖两个关键场景：

1. 鉴权后如果发来的业务消息里 `deviceId` 与真实身份不一致，连接应该被断开。
2. 正常业务消息应该使用鉴权后的身份进行分发，而不是依赖报文字段。

这个测试直接验证了本轮最重要的安全修复。

#### 新增测试 2：管理接口拦截器测试

新增 `AdminTokenInterceptorTest`，覆盖三个场景：

1. `GET /api/strategy/{id}` 无 token 允许访问
2. `PUT /api/strategy/{id}` 无 token 返回 `401`
3. `POST /api/message/dispatch` 携带正确 token 可以访问

#### 修改效果

现在关键修复不再只是“代码看起来对”，而是已经有自动化测试做回归保护。

#### 涉及文件

- [DeviceChannelHandlerTest.java](/home/sid/Epp/epp-backend/src/test/java/com/epp/backend/netty/DeviceChannelHandlerTest.java)
- [AdminTokenInterceptorTest.java](/home/sid/Epp/epp-backend/src/test/java/com/epp/backend/security/AdminTokenInterceptorTest.java)

---

## 5. 本轮修改后的验证结果

本轮修改完成后，执行了完整测试：

```bash
mvn test -q
```

结果：

- 所有测试通过
- 原先 Mockito 初始化失败的问题已解决
- 新增的安全测试和拦截器测试均通过

这说明本轮改动不是停留在静态分析层面，而是已经经过实际验证。

---

## 6. 本轮改动涉及的文件清单

### 修改的已有文件

- [src/main/java/com/epp/backend/common/CommonResult.java](/home/sid/Epp/epp-backend/src/main/java/com/epp/backend/common/CommonResult.java)
- [src/main/java/com/epp/backend/config/JwtUtils.java](/home/sid/Epp/epp-backend/src/main/java/com/epp/backend/config/JwtUtils.java)
- [src/main/java/com/epp/backend/controller/StrategyController.java](/home/sid/Epp/epp-backend/src/main/java/com/epp/backend/controller/StrategyController.java)
- [src/main/java/com/epp/backend/entity/StrategyConfig.java](/home/sid/Epp/epp-backend/src/main/java/com/epp/backend/entity/StrategyConfig.java)
- [src/main/java/com/epp/backend/exception/GlobalExceptionHandler.java](/home/sid/Epp/epp-backend/src/main/java/com/epp/backend/exception/GlobalExceptionHandler.java)
- [src/main/java/com/epp/backend/netty/DeviceChannelHandler.java](/home/sid/Epp/epp-backend/src/main/java/com/epp/backend/netty/DeviceChannelHandler.java)
- [src/main/java/com/epp/backend/netty/EppProtocolDecoder.java](/home/sid/Epp/epp-backend/src/main/java/com/epp/backend/netty/EppProtocolDecoder.java)
- [src/main/java/com/epp/backend/netty/NettyBusinessThreadPool.java](/home/sid/Epp/epp-backend/src/main/java/com/epp/backend/netty/NettyBusinessThreadPool.java)
- [src/main/java/com/epp/backend/service/impl/DeviceServiceImpl.java](/home/sid/Epp/epp-backend/src/main/java/com/epp/backend/service/impl/DeviceServiceImpl.java)
- [src/main/java/com/epp/backend/service/impl/StrategyServiceImpl.java](/home/sid/Epp/epp-backend/src/main/java/com/epp/backend/service/impl/StrategyServiceImpl.java)
- [src/main/resources/application.yml](/home/sid/Epp/epp-backend/src/main/resources/application.yml)
- [src/main/resources/schema.sql](/home/sid/Epp/epp-backend/src/main/resources/schema.sql)

### 新增的文件

- [src/main/java/com/epp/backend/config/WebMvcConfig.java](/home/sid/Epp/epp-backend/src/main/java/com/epp/backend/config/WebMvcConfig.java)
- [src/main/java/com/epp/backend/exception/BadRequestException.java](/home/sid/Epp/epp-backend/src/main/java/com/epp/backend/exception/BadRequestException.java)
- [src/main/java/com/epp/backend/exception/ResourceNotFoundException.java](/home/sid/Epp/epp-backend/src/main/java/com/epp/backend/exception/ResourceNotFoundException.java)
- [src/main/java/com/epp/backend/exception/UnauthorizedException.java](/home/sid/Epp/epp-backend/src/main/java/com/epp/backend/exception/UnauthorizedException.java)
- [src/main/java/com/epp/backend/security/AdminTokenInterceptor.java](/home/sid/Epp/epp-backend/src/main/java/com/epp/backend/security/AdminTokenInterceptor.java)
- [src/test/java/com/epp/backend/netty/DeviceChannelHandlerTest.java](/home/sid/Epp/epp-backend/src/test/java/com/epp/backend/netty/DeviceChannelHandlerTest.java)
- [src/test/java/com/epp/backend/security/AdminTokenInterceptorTest.java](/home/sid/Epp/epp-backend/src/test/java/com/epp/backend/security/AdminTokenInterceptorTest.java)
- [src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker](/home/sid/Epp/epp-backend/src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker)

---

## 7. 从工程能力角度，这轮修改说明了什么

如果从大厂后端面试官视角看，这轮修改最有价值的不是“多写了几个类”，而是体现了以下能力：

### 7.1 有安全边界意识

不是只会“加 JWT”，而是能意识到：

- 鉴权和身份绑定不是一回事
- 管理接口必须与设备接口分离
- 密钥和口令不能硬编码

这说明你开始具备真正的后端安全意识，而不是只会调用框架 API。

### 7.2 有数据契约意识

能发现并修复：

- DDL 和 Entity 不一致
- 查询字段在表里根本不存在
- Java 类型和 SQL 类型不一致

这比单纯写业务逻辑更重要，因为真实线上问题很多都发生在“代码和数据模型各说各话”。

### 7.3 有并发与系统稳定性意识

能意识到：

- 线程池隔离的目标是什么
- 为什么 `CallerRunsPolicy` 在 Netty 场景下是有害的
- 为什么保护 I/O 线程优先级更高

这说明你不是只会“会用线程池”，而是开始理解线程模型和系统压力传播路径。

### 7.4 有错误语义和对外契约意识

很多初级项目只会“统一返回 JSON”，但不会区分：

- 400
- 401
- 404
- 500

而对成熟后端来说，对外错误语义本身就是接口契约的一部分。

### 7.5 有测试闭环意识

这轮不是只改主代码，还把以下内容补齐了：

- 环境兼容导致的测试无法运行
- 安全关键场景的回归测试
- 管理接口的行为测试

这说明你不是停留在“我觉得改对了”，而是进入“我会用测试保护改动”的阶段。

---

## 8. 当前项目还没有彻底解决的后续项

虽然本轮已经把最关键的问题收口了，但如果要继续把这个项目往“作品级”推进，后面还有几件很值得做的事。

### 8.1 将轻量管理口令升级为完整权限体系

当前方案是务实的修复方案，但不是终局方案。后续建议补：

- Spring Security
- 管理员账号体系
- 角色权限模型
- 接口级权限注解
- 审计日志

### 8.2 给线程池拒绝增加指标与降级策略

目前线程池满了会记录日志，但还可以继续增强：

- 增加 `rejected task` 指标
- 对不同消息类型做优先级区分
- 对低优先级任务做丢弃/延迟处理

### 8.3 补接口测试和集成测试

当前新增的是单元级/MockMvc 级测试。后续还可以补：

- 策略更新接口集成测试
- JWT 鉴权失败接口测试
- Redis/Kafka 相关链路的集成测试

### 8.4 引入数据库迁移工具

当前项目仍然靠 `schema.sql` 管理建表，后续建议引入：

- Flyway
- 或 Liquibase

这样才能支持真实的版本化数据库迁移。

### 8.5 增强可观测性

项目已经有指标基础，但还可以继续补：

- 线程池拒绝次数
- JWT 鉴权失败次数
- Netty 异常断连次数
- 策略拉取耗时分布
- Redis / DB 命中率拆分

---

## 9. 如果你要在面试中讲这轮修改，建议怎么说

你可以把这轮修改概括成一句话：

“我没有继续堆功能，而是把项目从 demo 状态往生产可用方向推进，重点补了安全边界、数据契约、一致的异常语义、线程池回压策略和自动化测试闭环。”

进一步可以拆成 5 点讲：

1. 发现并修复了 Netty 鉴权后的身份冒用问题。
2. 给管理接口增加鉴权，区分设备平面和控制平面。
3. 修复了策略模型和数据库表结构不一致的问题。
4. 优化了线程池回压策略，避免在高压下拖垮 Netty I/O 线程。
5. 用测试把这些关键修复场景保护住，并修好了测试环境本身。

这样的表达会比单纯说“我用过 Redis/Kafka/Netty”更有说服力。

---

## 10. 最终结论

本轮修改完成后，这个项目相比原始版本的提升主要体现在：

- 安全边界更清晰
- 数据契约更一致
- 接口错误语义更规范
- 并发场景更稳健
- 自动化测试更可信

从“项目能跑”到“项目更像一个真正的后端系统”，核心差别就在这些地方。

如果继续往下打磨，把权限体系、数据库迁移、集成测试、指标体系再补齐，这个项目会更适合作为你转后端方向时的代表作品。
