package com.lingmart.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 工具注册表：声明每个工具的**类别**，并据此决定"服务端能不能自己执行"。
 *
 * <p>类别是执行模型的唯一依据——不是靠工具名硬编码判断，也不是靠提示词约束：</p>
 * <ul>
 *   <li>{@link Category#READ_ONLY_SELF}：只能查"我自己的"数据，<b>参数里不存在主体</b>。服务端直接执行，免用户确认。</li>
 *   <li>{@link Category#READ_ONLY_PUBLIC}：查询对象本身是公开的（如某商品的公开详情）。服务端直接执行，免确认。</li>
 *   <li>{@link Category#WRITE}：任何产生服务端状态变更的动作。<b>永远不执行</b>，只下发意图等用户确认。</li>
 * </ul>
 *
 * <p>三条边界画在这里最稳的原因：判据是"<b>参数里有没有主体</b>"和"<b>会不会改状态</b>"，
 * 这两条都能在代码里机械校验，不依赖人的判断力。</p>
 *
 * <p><b>启动期校验</b>：只读工具的参数里一旦出现主体字段名（userId/buyerId/…），
 * 模型就有可能被诱导去查别人的数据，而参数级校验很难兜住。所以这里在 Bean 构造时
 * 用反射扫一遍只读工具的 {@code @Tool} 方法参数名，发现违规立即打 <b>ERROR</b>。
 * （比单测更强：单测只在 CI 跑一次，这个校验每次启动都跑，不可能随代码漂移。）</p>
 */
@Slf4j
@Service
public class ToolRegistry {

    /** 工具类别 */
    public enum Category {
        /** 只能查"我自己的"，参数里不存在主体 → 服务端免确认执行 */
        READ_ONLY_SELF,
        /** 查询公开对象 → 服务端免确认执行 */
        READ_ONLY_PUBLIC,
        /** 产生状态变更 → 永不服务端执行，走用户确认 */
        WRITE
    }

    /**
     * 永远不允许出现在只读工具参数里的主体字段名（小写比较）。
     *
     * <p>这条清单是「只读免确认」的安全前提：只要工具签名里没有"查谁的"这个旋钮，
     * 模型就没有被诱导越权的入口——比在参数上做校验可靠得多。</p>
     */
    private static final Set<String> FORBIDDEN_SUBJECT_PARAMS = Set.of(
            "userid", "buyerid", "sellerid", "ownerid", "memberid", "customerid", "userrole");

    /** 仅 READ_ONLY_SELF 额外禁止的字段：查公开店铺可以传 shopId，查"我的店铺"不可以。 */
    private static final Set<String> FORBIDDEN_SELF_ONLY_PARAMS = Set.of("shopid");

    /** 工具名 → 类别 */
    private final Map<String, Category> categories = new LinkedHashMap<>();

    /** 工具名 → 展示用中文标签（前端"正在查询…"文案） */
    private final Map<String, String> labels = new LinkedHashMap<>();

    /**
     * 只读工具类清单，供启动期参数校验使用。
     *
     * <p>由 {@link #register} 在注册时<b>自动收集</b>，而不是在校验处硬编码一份——
     * 硬编码的清单会随新增工具而漂移：新工具照常注册、照常免确认执行，却<b>悄悄跳过了参数校验</b>，
     * 而这是一条不会报错、不会被发现的失效路径。</p>
     */
    private final List<Class<?>> readOnlyToolClasses = new ArrayList<>();

    public ToolRegistry() {
        register(PageActionTools.class, Category.WRITE, "页面操作");
        register(OrderQueryTools.class, Category.READ_ONLY_SELF, "查询我的订单");
        register(ShopQueryTools.class, Category.READ_ONLY_SELF, "查询我的开店状态");
        register(ProductQueryTools.class, Category.READ_ONLY_SELF, "查询我的商品");
        register(SearchQueryTools.class, Category.READ_ONLY_PUBLIC, "搜索店铺");
        validate();
    }

    /** 该工具是否由服务端直接执行（只读类）。 */
    public boolean isServerExecutable(String toolName) {
        Category category = categories.get(toolName);
        return category == Category.READ_ONLY_SELF || category == Category.READ_ONLY_PUBLIC;
    }

    /** 该工具是否存在且为写操作（需要走用户确认）。未知工具名按"写"处理（fail-safe：宁可多要一次确认）。 */
    public boolean isWrite(String toolName) {
        return categories.get(toolName) != Category.READ_ONLY_SELF
                && categories.get(toolName) != Category.READ_ONLY_PUBLIC;
    }

    /** 工具的中文展示标签（用于"正在查询…"）。 */
    public String labelOf(String toolName) {
        return labels.getOrDefault(toolName, "执行操作");
    }

    /** 已注册的工具名（按注册顺序）。 */
    public List<String> toolNames() {
        return List.copyOf(categories.keySet());
    }

    /** 注册某工具类里所有 {@code @Tool} 方法的类别与标签。 */
    private void register(Class<?> toolClass, Category category, String label) {
        if (category != Category.WRITE) {
            readOnlyToolClasses.add(toolClass);
        }
        for (Method method : toolClass.getDeclaredMethods()) {
            Tool tool = method.getAnnotation(Tool.class);
            if (tool != null) {
                categories.put(tool.name(), category);
                labels.put(tool.name(), label);
            }
        }
    }

    /**
     * 扫描只读工具的 {@code @Tool} 方法参数名，校验不含主体字段。
     *
     * <p>参数名取自 Java 反射——父 POM 已开启 {@code -parameters}，故参数名与 Spring AI 生成的
     * JSON Schema 属性名一致，校验的是模型真正看得到的那个名字。</p>
     */
    private void validate() {
        List<String> violations = new ArrayList<>();
        for (Class<?> toolClass : readOnlyToolClasses) {
            for (Method method : toolClass.getDeclaredMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool == null) {
                    continue;
                }
                Category category = categories.get(tool.name());
                boolean selfOnly = category == Category.READ_ONLY_SELF;
                for (Parameter parameter : method.getParameters()) {
                    if (parameter.getAnnotation(ToolParam.class) == null) {
                        continue;
                    }
                    String name = parameter.getName().toLowerCase(Locale.ROOT);
                    if (FORBIDDEN_SUBJECT_PARAMS.contains(name)
                            || (selfOnly && FORBIDDEN_SELF_ONLY_PARAMS.contains(name))) {
                        violations.add(tool.name() + "(" + parameter.getName() + ")");
                    }
                }
            }
        }
        if (!violations.isEmpty()) {
            // 只告警不阻断启动：这是设计期不变量，由开发者修；生产起不来比"多一个越权入口"更难接受
            log.error("【只读工具参数违规】以下只读工具参数含主体字段，模型可能被诱导越权查询，"
                    + "请改为由服务端从身份注入主体：{}", violations);
        }
        log.info("工具注册表就绪：{} 个工具（{} 个服务端可执行）", categories.size(),
                categories.values().stream().filter(c -> c != Category.WRITE).count());
    }
}
