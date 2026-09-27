package com.lingmart.auth.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lingmart.auth.entity.User;
import com.lingmart.auth.mapper.UserMapper;
import com.lingmart.common.constant.AuthConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 管理员账号初始化器。
 *
 * <p>服务启动时若管理员账号不存在则自动创建（BCrypt 加密密码，避免在 SQL 中硬编码密文）。
 * 账号密码由配置 {@code lingmart.admin.phone / lingmart.admin.password} 控制，默认
 * 手机号 13800000000 / 密码 admin123。生产环境请务必修改。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminUserInitializer implements ApplicationRunner {

    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    @Value("${lingmart.admin.phone}")
    private String adminPhone;

    @Value("${lingmart.admin.password}")
    private String adminPassword;

    @Override
    public void run(ApplicationArguments args) {
        Long count = userMapper.selectCount(
                Wrappers.<User>lambdaQuery().eq(User::getPhone, adminPhone));
        if (count != null && count > 0) {
            log.info("管理员账号已存在，跳过初始化：phone={}", adminPhone);
            return;
        }
        User admin = new User();
        admin.setPhone(adminPhone);
        admin.setPassword(passwordEncoder.encode(adminPassword));
        admin.setNickname("管理员");
        admin.setRole(AuthConstants.ROLE_ADMIN);
        admin.setStatus(1);
        userMapper.insert(admin);
        log.info("已初始化管理员账号：phone={}", adminPhone);
    }
}
