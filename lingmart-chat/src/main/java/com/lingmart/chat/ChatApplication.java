package com.lingmart.chat;

import com.lingmart.common.exception.GlobalExceptionHandler;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;

/**
 * 轻营 · 聊天服务启动类。
 *
 * <p>买家与店铺老板的 WebSocket(STOMP) 实时会话 + 消息持久化（MySQL），
 * 历史记录经 REST 分页拉取。经 Feign 调用 auth 获取用户公开信息、调用 shop 校验店铺。</p>
 */
@SpringBootApplication(scanBasePackages = "com.lingmart.chat")
@MapperScan("com.lingmart.chat.mapper")
@EnableFeignClients(basePackages = "com.lingmart.chat.feign")
@Import(GlobalExceptionHandler.class)
public class ChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatApplication.class, args);
    }
}
