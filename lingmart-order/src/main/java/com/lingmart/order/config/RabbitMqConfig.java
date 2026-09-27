package com.lingmart.order.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * RabbitMQ 配置：订单超时自动取消（TTL + 死信队列）。
 *
 * <p>下单成功后将 orderSn 发到 {@code order.delay.queue}（延迟 TTL），TTL 到期未消费即
 * 被投递到死信队列 {@code order.timeout.queue}，由消费者校验订单仍待支付则自动取消。</p>
 *
 * <p>消费失败处理（v0.6）：容器工厂配置退避重试，重试耗尽由 {@link RepublishMessageRecoverer}
 * 把消息重发到死信交换器 → {@code order.timeout.dlq}。**不依赖队列级死信参数**——
 * 队列参数创建后不可修改，若旧队列已存在会触发 PRECONDITION_FAILED，故超时队列保持无参声明，
 * 由恢复器在消费侧重发，旧队列无需删除即可热升级。</p>
 */
@Configuration
public class RabbitMqConfig {

    /** 延迟交换器（生产者入口） */
    public static final String DELAY_EXCHANGE = "order.delay.exchange";
    public static final String DELAY_QUEUE = "order.delay.queue";
    public static final String DELAY_ROUTING_KEY = "order.delay";

    /** 死信交换器与超时消费队列 */
    public static final String TIMEOUT_EXCHANGE = "order.timeout.exchange";
    public static final String TIMEOUT_QUEUE = "order.timeout.queue";
    public static final String TIMEOUT_ROUTING_KEY = "order.timeout.cancel";
    /** 超时消费重试耗尽后的死信队列（人工介入/兜底扫描信号） */
    public static final String TIMEOUT_DLQ = "order.timeout.dlq";
    public static final String TIMEOUT_DLQ_ROUTING_KEY = "order.timeout.dlq";

    /** 支付成功事件（Outbox → MQ → ware 异步扣减）；与 lingmart-ware 同名声明（幂等） */
    public static final String PAID_EXCHANGE = "order.paid.exchange";
    public static final String PAID_QUEUE = "order.paid.ware.queue";
    public static final String PAID_ROUTING_KEY = "order.paid.ware";

    /** 订单支付超时时间（毫秒），来自 application.yml */
    @Value("${lingmart.order.timeout-ms:120000}")
    private long timeoutMs;

    /** 消费失败最大尝试次数（含首次） */
    @Value("${lingmart.order.retry-max-attempts:3}")
    private int retryMaxAttempts;

    /** 首次重试间隔（毫秒） */
    @Value("${lingmart.order.retry-initial-interval:1000}")
    private long retryInitialInterval;

    /** 重试间隔上限（毫秒） */
    @Value("${lingmart.order.retry-max-interval:10000}")
    private long retryMaxInterval;

    @Bean
    public DirectExchange delayExchange() {
        return new DirectExchange(DELAY_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange timeoutExchange() {
        return new DirectExchange(TIMEOUT_EXCHANGE, true, false);
    }

    @Bean
    public Queue delayQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-message-ttl", timeoutMs);                          // 延迟时长
        args.put("x-dead-letter-exchange", TIMEOUT_EXCHANGE);          // 过期后投递到死信交换器
        args.put("x-dead-letter-routing-key", TIMEOUT_ROUTING_KEY);    // 死信路由键
        return new Queue(DELAY_QUEUE, true, false, false, args);
    }

    @Bean
    public Queue timeoutQueue() {
        // 保持无参声明（队列参数不可修改，旧队列存在时带参声明会 PRECONDITION_FAILED）；
        // 消费重试耗尽的死信投递由 RepublishMessageRecoverer 在消费侧重发，不依赖队列参数
        return new Queue(TIMEOUT_QUEUE, true);
    }

    @Bean
    public Queue timeoutDlq() {
        return new Queue(TIMEOUT_DLQ, true);
    }

    @Bean
    public Binding delayBinding() {
        return BindingBuilder.bind(delayQueue()).to(delayExchange()).with(DELAY_ROUTING_KEY);
    }

    @Bean
    public Binding timeoutBinding() {
        return BindingBuilder.bind(timeoutQueue()).to(timeoutExchange()).with(TIMEOUT_ROUTING_KEY);
    }

    @Bean
    public Binding timeoutDlqBinding() {
        return BindingBuilder.bind(timeoutDlq()).to(timeoutExchange()).with(TIMEOUT_DLQ_ROUTING_KEY);
    }

    @Bean
    public DirectExchange paidExchange() {
        return new DirectExchange(PAID_EXCHANGE, true, false);
    }

    @Bean
    public Queue paidQueue() {
        return new Queue(PAID_QUEUE, true);
    }

    @Bean
    public Binding paidBinding() {
        return BindingBuilder.bind(paidQueue()).to(paidExchange()).with(PAID_ROUTING_KEY);
    }

    /**
     * JSON 消息转换器（orderSn 等以 JSON 传输，便于排查）。
     */
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        return template;
    }

    /**
     * 监听容器工厂：消费失败按退避重试，耗尽后由 {@link RepublishMessageRecoverer} 重发到死信交换器。
     *
     * @param configurer        Spring Boot 监听容器工厂配置器（应用 spring.rabbitmq.listener.* 与消息转换器）
     * @param connectionFactory 连接工厂
     * @param rabbitTemplate    用于重发死信
     * @return 配置完成的监听容器工厂
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            RabbitTemplate rabbitTemplate) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);

        RetryOperationsInterceptor retry = RetryInterceptorBuilder.stateless()
                .maxAttempts(retryMaxAttempts)
                .backOffOptions(retryInitialInterval, 2.0, retryMaxInterval)
                .recoverer(new RepublishMessageRecoverer(rabbitTemplate, TIMEOUT_EXCHANGE, TIMEOUT_DLQ_ROUTING_KEY))
                .build();
        factory.setAdviceChain(retry);
        return factory;
    }
}
