package com.lingmart.product.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务线程池（供 {@code @Async} 使用）。
 *
 * <p>为什么必须显式配置：不配 {@link AsyncConfigurer} 时，Spring Boot 交给默认的
 * {@code applicationTaskExecutor}——任务队列<b>无界</b>、且与其它异步任务共用同一个池。
 * 而每次支付/退款都会发一个 {@code SaleUpdateEvent}（AFTER_COMMIT + @Async 刷新 ES），
 * 高峰期会把 ES 刷新任务无限堆积，既无隔离也无拒绝策略。这里给 ES 刷新一条专用通道：
 * 有界队列 + {@link ThreadPoolExecutor.CallerRunsPolicy}（队列满时由提交线程自己执行，
 * 相当于对上游做背压，绝不丢任务、也绝不无限堆积）。</p>
 */
@Slf4j
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    /** 常驻线程数：ES 刷新是短任务（单批文档写），不需要大并发 */
    private static final int CORE_POOL_SIZE = 2;
    /** 峰值线程数 */
    private static final int MAX_POOL_SIZE = 8;
    /** 有界队列容量：达到上限后触发 CallerRunsPolicy 背压 */
    private static final int QUEUE_CAPACITY = 512;
    /** 空闲线程回收时间（秒） */
    private static final int KEEP_ALIVE_SECONDS = 60;

    @Bean("taskExecutor")
    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setKeepAliveSeconds(KEEP_ALIVE_SECONDS);
        executor.setThreadNamePrefix("lingmart-es-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 优雅停机：等待在途 ES 刷新写完，避免停机时留下未落地的文档（由对账任务兜底，但能少则少）
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }

    /**
     * 异步任务抛出的异常默认只被吞掉（连日志都没有）：统一打 ERROR，便于定位「为什么补偿表在涨」。
     * ES 写失败本身已有 es_write_retry 补偿表兜底，这里只负责让它可见。
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (Throwable ex, Method method, Object... params) ->
                log.error("异步任务执行失败：method={}, err={}", method.getName(), ex.getMessage(), ex);
    }
}
