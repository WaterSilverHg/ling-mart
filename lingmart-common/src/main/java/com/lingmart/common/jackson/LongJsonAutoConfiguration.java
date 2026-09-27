package com.lingmart.common.jackson;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;

/**
 * 全局 JSON 序列化：将装箱 {@link Long} 以字符串形式输出。
 *
 * <p>背景：主键切换为雪花后为 64 位 ≈ 19 位十进制，超出 JavaScript 精确整数上限
 * （Number.MAX_SAFE_INTEGER = 2^53 ≈ 9.0e15），前端若按 number 解析会静默丢精度、
 * 回传即查不到记录。因此所有对外 JSON 中的装箱 Long（主键/外键 id 等）一律输出为字符串；
 * 基本类型 {@code long}（如 {@code PageResult.total/page/size} 分页数字）保持数值，不受影响。</p>
 *
 * <p>经 {@code META-INF/spring/...AutoConfiguration.imports} 注册，对所有引入 common 的服务生效；
 * 仅影响写出（JSON 输出），Jackson 反序列化时字符串→Long 仍自动兼容。</p>
 */
@AutoConfiguration
public class LongJsonAutoConfiguration {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        SimpleModule module = new SimpleModule();
        module.addSerializer(Long.class, ToStringSerializer.instance);
        return builder -> builder.modulesToInstall(module);
    }
}
