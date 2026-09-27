package com.lingmart.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 密码加密配置。
 *
 * <p>提供 {@link BCryptPasswordEncoder} 单例 Bean，用户密码统一使用 BCrypt 加密存储。</p>
 */
@Configuration
public class PasswordConfig {

    /**
     * 密码编码器（BCrypt 自动加盐，不可逆）。
     *
     * @return BCrypt 密码编码器
     */
    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
