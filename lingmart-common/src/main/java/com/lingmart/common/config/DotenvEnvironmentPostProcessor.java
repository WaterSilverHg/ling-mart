package com.lingmart.common.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地开发时自动加载项目根目录 {@code .env} 的属性（全部服务生效）。
 *
 * <p><b>为什么需要</b>：IDEA / {@code java -jar} 跑的是普通 Java 进程，不像 {@code docker compose}
 * 那样读项目根目录的 {@code .env}——于是 {@code ops-checklist} 教的「在 .env 覆盖 JWT_SECRET /
 * DEV_CODE_ENABLED」在本地<b>根本不生效</b>，实际用的还是 application.yml 的默认值（含仓库内置的
 * 开发密钥），这是个容易误判的安全陷阱。本处理器从工作目录（{@code user.dir}）<b>向上逐级查找</b>
 * {@code .env}，把其中变量作为属性源注入——不依赖工作目录、不用绝对路径。</p>
 *
 * <p><b>优先级</b>：真实系统环境变量 &gt; {@code .env} &gt; {@code application.yml} 默认值。
 * 也就是说 {@code .env} 里写的任何键都会覆盖 yml 的默认值（这正是「.env 覆盖」的语义），
 * 因此不要在 {@code .env} 里放只想影响单个服务的键——它对 9 个服务一视同仁。</p>
 *
 * <p><b>容器内不生效也无妨</b>：打包镜像里没有该文件（Dockerfile 只拷 jar），查找失败自动跳过，
 * 仍由 compose 注入的环境变量提供。想确认本地是否真的加载到了，可看 {@code /actuator/env} 里
 * 是否出现名为 {@code dotenv-file-<路径>} 的属性源。</p>
 *
 * <p>实现说明：本类在 Spring 容器启动<b>之前</b>执行，故意不打日志——此时 Logback 尚未由 Boot
 * 初始化，提前触发日志会干扰 {@code logback-spring.xml} 里的 {@code <springProperty>} 解析。</p>
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String ENV_FILE_NAME = ".env";
    private static final String SYSTEM_ENV_SOURCE = "systemEnvironment";
    private static final int MAX_UP_LEVELS = 8;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path dotenv = findDotenv();
        if (dotenv == null) {
            return;
        }
        Map<String, Object> props = parse(dotenv);
        if (props.isEmpty()) {
            return;
        }
        MutablePropertySources sources = environment.getPropertySources();
        MapPropertySource source = new MapPropertySource("dotenv-file-" + dotenv.toAbsolutePath(), props);
        try {
            // 插在系统环境变量之后：真实环境变量优先于 .env，.env 优先于 application.yml 默认值
            sources.addAfter(SYSTEM_ENV_SOURCE, source);
        } catch (IllegalArgumentException e) {
            // 找不到 systemEnvironment 源（极罕见）则放到末尾
            sources.addLast(source);
        }
    }

    /** 从工作目录向上查找 .env，最多向上 8 级。 */
    private Path findDotenv() {
        Path dir = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (int i = 0; i < MAX_UP_LEVELS; i++) {
            Path candidate = dir.resolve(ENV_FILE_NAME);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            Path parent = dir.getParent();
            if (parent == null) {
                break;
            }
            dir = parent;
        }
        return null;
    }

    /** 解析 KEY=VALUE（忽略整行 # 注释；值可能含 =，只按首个 = 切分）。
     * 注意：.env 按 properties 语义本不支持行尾注释，这里额外剥掉「空格 + #」之后的内容，
     * 避免 {@code AGENT_KB_ENABLED=true # 注释} 把注释当进布尔/数字值。 */
    private Map<String, Object> parse(Path file) {
        Map<String, Object> map = new HashMap<>();
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                int idx = t.indexOf('=');
                if (idx <= 0) {
                    continue;
                }
                String key = t.substring(0, idx).trim();
                String value = t.substring(idx + 1).trim();
                // 剥行尾注释（以空白+# 开头到行尾）；无空白前缀的 #（如密钥本身含 #）不处理
                int ci = indexOfInlineComment(value);
                if (ci >= 0) {
                    value = value.substring(0, ci).trim();
                }
                if (!key.isEmpty()) {
                    map.put(key, value);
                }
            }
        } catch (IOException ignore) {
            // 解析失败返回空 map（不影响启动）
        }
        return map;
    }

    /** 定位行尾注释起始下标：空白后紧跟 #（如 " #注释" 或 "\t#注释"），找不到返回 -1。 */
    private static int indexOfInlineComment(String value) {
        for (int i = 0; i < value.length() - 1; i++) {
            char c = value.charAt(i);
            if ((c == ' ' || c == '\t') && value.charAt(i + 1) == '#') {
                return i;
            }
        }
        return -1;
    }
}
