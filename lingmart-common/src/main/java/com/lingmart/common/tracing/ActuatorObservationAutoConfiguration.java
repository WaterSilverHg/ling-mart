package com.lingmart.common.tracing;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.lang.reflect.Method;

/**
 * 把 {@code /actuator/**} 的请求排除出观测系统，避免健康检查/指标抓取把链路追踪刷屏。
 *
 * <p><b>为什么需要它</b>：Prometheus 每 15 秒抓一次 9 个服务的
 * {@code /actuator/prometheus}，每个请求都会生成一条独立的 trace。一天下来光抓取就有
 * 3 万多条，Zipkin 的列表和依赖图会被这些「没有任何下游调用」的噪音占满，
 * 真实业务链路反而翻不出来——排查压测结果时极易误判成「追踪没生效」。
 * 健康检查（k8s 探针、{@code verify-health.sh}）同理。</p>
 *
 * <p><b>排除的是「观测」而不是「端点」</b>：被过滤的请求仍然正常响应，只是不再产生
 * span 与 {@code http.server.requests} 指标。Actuator 端点本身没有可观测价值，
 * 这正好也把指标里的噪音一起去掉了。若确实需要抓取 Actuator 的调用链，
 * 把 {@code lingmart.tracing.exclude-actuator} 设为 {@code false} 即可。</p>
 *
 * <p><b>为什么不直接 {@code instanceof}</b>：common 同时被 Servlet 服务（MVC）和
 * 网关（WebFlux）依赖，前者携带 {@code HttpServletRequest}、后者携带
 * {@code ServerHttpRequest}。在同一个类里直接引用任一类型，都会让另一侧在加载时
 * 抛 {@code NoClassDefFoundError}，因此这里用反射取 URI，两种栈都能覆盖。</p>
 *
 * <p>经 {@code META-INF/spring/...AutoConfiguration.imports} 注册，对所有引入 common 的服务生效。</p>
 */
@AutoConfiguration
@ConditionalOnClass(ObservationPredicate.class)
@ConditionalOnProperty(name = "lingmart.tracing.exclude-actuator", havingValue = "true", matchIfMissing = true)
public class ActuatorObservationAutoConfiguration {

    /** Actuator 端点前缀（Spring Boot 默认 /actuator，可用 management.endpoints.web.base-path 改） */
    private static final String ACTUATOR_PREFIX = "/actuator";

    /**
     * 注册观测断言：载体是 Actuator 请求时直接不建观测。
     *
     * <p>Micrometer 会在 {@code Observation#start()} 时调用它，返回 {@code false}
     * 即得到 NoopObservation——span 与指标都不会产生。</p>
     */
    @Bean
    public ObservationPredicate actuatorObservationPredicate() {
        return (name, context) -> !isActuatorRequest(context);
    }

    private static boolean isActuatorRequest(Observation.Context context) {
        if (context == null) {
            return false;
        }
        String path = resolvePath(carrierOf(context));
        return path != null && (path.equals(ACTUATOR_PREFIX) || path.startsWith(ACTUATOR_PREFIX + "/"));
    }

    /**
     * 取观测上下文里的请求载体。
     *
     * <p>{@code Observation.Context} 本身没有 {@code getCarrier()}，载体只在
     * {@code ServerRequestObservationContext} 等子类上；这里同样用反射，
     * 避免在 common 里静态依赖 Servlet 或 WebFlux 的实现类。</p>
     */
    private static Object carrierOf(Observation.Context context) {
        try {
            Method getCarrier = context.getClass().getMethod("getCarrier");
            return getCarrier.invoke(context);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    /**
     * 从请求载体上取路径，兼容两种 Web 栈。
     *
     * <ul>
     *   <li>Servlet：{@code HttpServletRequest#getRequestURI()}（已含 context path）</li>
     *   <li>WebFlux：{@code ServerHttpRequest#getURI().getPath()}</li>
     * </ul>
     *
     * @return 取不到时返回 {@code null}（视为普通请求，不误杀）
     */
    private static String resolvePath(Object carrier) {
        if (carrier == null) {
            return null;
        }
        try {
            Method getRequestUri = carrier.getClass().getMethod("getRequestURI");
            Object uri = getRequestUri.invoke(carrier);
            return uri == null ? null : uri.toString();
        } catch (ReflectiveOperationException ignored) {
            // 不是 Servlet 请求，继续按 WebFlux 尝试
        }
        try {
            Method getUri = carrier.getClass().getMethod("getURI");
            Object uri = getUri.invoke(carrier);
            if (uri == null) {
                return null;
            }
            Object path = uri.getClass().getMethod("getPath").invoke(uri);
            return path == null ? null : path.toString();
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
