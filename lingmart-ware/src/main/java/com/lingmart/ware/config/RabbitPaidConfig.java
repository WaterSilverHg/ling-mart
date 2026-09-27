package com.lingmart.ware.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;

/**
 * RabbitMQ 配置：订阅「订单支付成功」事件，异步扣减库存。
 *
 * <p>交换器/队列与 lingmart-order 侧同名声明（幂等，可双侧重复声明）。消费失败按退避重试，
 * 耗尽后由 {@link RepublishMessageRecoverer} 重发到本交换器 → {@code order.paid.ware.dlq} 死信，
 * 供人工排查（与库存流水人工介入共同作为红线）。</p>
 */
@Configuration
public class RabbitPaidConfig {

    /** 支付成功事件（与 order 同名的交换器/队列/路由键） */
    public static final String PAID_EXCHANGE = "order.paid.exchange";
    public static final String PAID_QUEUE = "order.paid.ware.queue";
    public static final String PAID_ROUTING_KEY = "order.paid.ware";
    /** 消费重试耗尽后的死信队列（人工介入信号） */
    public static final String PAID_DLQ = "order.paid.ware.dlq";
    public static final String PAID_DLQ_ROUTING_KEY = "order.paid.ware.dlq";

    @Value("${lingmart.ware.retry-max-attempts:3}")
    private int retryMaxAttempts;

    @Value("${lingmart.ware.retry-initial-interval:1000}")
    private long retryInitialInterval;

    @Value("${lingmart.ware.retry-max-interval:10000}")
    private long retryMaxInterval;

    @Bean
    public DirectExchange paidExchange() {
        return new DirectExchange(PAID_EXCHANGE, true, false);
    }

    @Bean
    public Queue paidQueue() {
        return new Queue(PAID_QUEUE, true);
    }

    @Bean
    public Queue paidDlq() {
        return new Queue(PAID_DLQ, true);
    }

    @Bean
    public Binding paidBinding() {
        return BindingBuilder.bind(paidQueue()).to(paidExchange()).with(PAID_ROUTING_KEY);
    }

    @Bean
    public Binding paidDlqBinding() {
        return BindingBuilder.bind(paidDlq()).to(paidExchange()).with(PAID_DLQ_ROUTING_KEY);
    }

    /** JSON 消息转换器（OrderPaidMessage 传输） */
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 监听容器工厂：消费失败按退避重试，耗尽后重发到死信队列。
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
                .recoverer(new RepublishMessageRecoverer(rabbitTemplate, PAID_EXCHANGE, PAID_DLQ_ROUTING_KEY))
                .build();
        factory.setAdviceChain(retry);
        return factory;
    }
}
