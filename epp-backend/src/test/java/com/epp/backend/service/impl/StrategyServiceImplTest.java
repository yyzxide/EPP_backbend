package com.epp.backend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.epp.backend.entity.StrategyConfig;
import com.epp.backend.mapper.StrategyMapper;
import com.epp.backend.monitor.EppMetrics;
import com.epp.backend.pubsub.StrategyUpdatePublisher;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 策略服务单元测试
 * 核心目标: 验证高并发下 "防缓存击穿" (Double-Check Locking) 逻辑的有效性
 * 
 * 知识点 (Mockito): 
 *   通过 Mock (模拟) 掉底层依赖 (DB, Redis)，让我们只专注于测试 Service 层的并发逻辑。
 *   C++ 类比: 相当于用 gmock 写了一个 FakeDB 和 FakeRedis，通过 EXPECT_CALL 统计函数调用次数。
 */
@ExtendWith(MockitoExtension.class)
class StrategyServiceImplTest {

    // @Mock 会创建一个虚假的对象 (Fake Object)，所有方法默认返回 null 或 0
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    
    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private StrategyMapper strategyMapper;

    @Mock
    private EppMetrics eppMetrics;

    @Mock
    private StrategyUpdatePublisher strategyUpdatePublisher;

    // @Spy 会创建一个真实的对象，但我们可以用 Mockito 去监视它的行为
    // 我们需要一个真实的 Caffeine 缓存来配合锁池的测试
    @Spy
    private Cache<String, String> strategyCache = Caffeine.newBuilder().build();

    // @InjectMocks 会把上面用 @Mock 和 @Spy 创建的假对象，自动注入到这个被测对象里
    @InjectMocks
    private StrategyServiceImpl strategyService;

    @BeforeEach
    void setUp() {
        // 配置 Redis 的 Mock 行为：当调用 opsForValue() 时，返回我们准备好的 mock 操作类
        // 注意：我们必须在 lenient 模式下，因为某些测试用例可能不会触发这些调用
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        
        // 模拟 Redis 刚开始没有数据
        lenient().when(valueOperations.get(anyString())).thenReturn(null);
    }

    /**
     * 【硬核实战】高并发防击穿测试
     * 
     * 场景：Redis 和 Caffeine 中都没有 "S1001" 策略。
     * 行动：100 个线程同时发起拉取请求。
     * 预期结果：虽然有 100 个并发，但在锁池的保护下，数据库查询方法 `selectOne` 只能被执行 1 次！
     */
    @Test
    void testGetStrategy_HighConcurrency_CacheBreakdownDefense() throws InterruptedException {
        String strategyId = "S1001";
        String fakeJson = "{\"strategyId\":\"S1001\",\"rules\":\"BLOCK\"}";

        // 1. 准备 Mock 数据：当查数据库时，返回一个假实体，并且人为制造 50 毫秒的查询延迟
        StrategyConfig fakeDbResult = new StrategyConfig();
        fakeDbResult.setStrategyId(strategyId);
        fakeDbResult.setConfigJson(fakeJson);

        when(strategyMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            // 模拟数据库查询耗时 50ms。如果不加锁，这 50ms 内足以让 100 个线程全部穿透到这里
            Thread.sleep(50);
            return fakeDbResult;
        });

        // 2. 准备多线程并发环境
        int threadCount = 100;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        // 使用 CountDownLatch 就像田径场上的发令枪，让 100 个线程在同一起跑线等待，然后同时开跑
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        // 3. 安排 100 个线程进场
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    startLatch.await(); // 所有线程在这里阻塞，等待发令枪响
                    
                    // 核心动作：调用你写的带锁池的业务方法
                    String result = strategyService.getStrategy(strategyId);
                    assertEquals(fakeJson, result); // 验证每个人都拿到了正确的数据
                    
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown(); // 跑完一个，报告一次
                }
            });
        }

        // 4. 发令枪响！100 个线程同时杀向 getStrategy 方法
        startLatch.countDown();

        // 等待所有线程跑完（最多等 5 秒防止死锁）
        doneLatch.await(5, TimeUnit.SECONDS);

        // 5. 【终极断言】验证防线是否生效！
        // 告诉 Mockito：去查一下 strategyMapper.selectOne() 这个方法，是不是严格只被调用了 1 次？
        // 如果你的锁没写好，这里会报错，显示实际被调用了多次。
        verify(strategyMapper, times(1)).selectOne(any(LambdaQueryWrapper.class));
        
        System.out.println("✅ 防击穿测试通过：100个并发下，数据库仅被穿透 1 次！");
    }
}
