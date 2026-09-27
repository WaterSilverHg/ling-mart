package com.lingmart.auth;

import com.lingmart.common.exception.GlobalExceptionHandler;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * 轻营 · 认证中心启动类。
 *
 * <p>负责用户注册 / 登录、JWT 签发、手机号验证码。启动参数：{@code --server.port=8000}。</p>
 */
@SpringBootApplication(scanBasePackages = "com.lingmart.auth")
@MapperScan("com.lingmart.auth.mapper")
@Import(GlobalExceptionHandler.class)
public class AuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }
}
