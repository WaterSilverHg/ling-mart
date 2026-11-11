package com.lingmart.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 前置路由闸门（v0.30）：在付出任何模型调用之前，对用户输入做一次确定性判定。
 *
 * <p>动机是把「要不要受理、要不要把先前对话当依据」这个判断从模型手里拿回来。
 * 让模型自己决定"这条请求我该不该理"，等于把判断权交给了最容易被说服的那一环；
 * 而这两件事（是否受理、是否继承上下文）本来就不需要语义理解就能判。</p>
 *
 * <p><b>两种结论，严重度不同——这是本类最关键的设计</b>：</p>
 * <ul>
 *   <li>{@link Verdict#BLOCK} <b>元请求</b>：索取系统提示词、越狱模式。这类请求在本应用里
 *       没有任何正常读法，直接回固定文案、不调用模型，省下一次调用也去掉了模型的自由裁量。</li>
 *   <li>{@link Verdict#RESET} <b>覆盖类</b>：「忽略之前的指令」「忘掉前面的对话」。
 *       这类话<b>同时是注入的标准开场白，也是完全正常的纠正</b>——
 *       「忽略之前的指令，我改问订单」谁都会说。所以不拦，改为触发上下文重置：
 *       去掉的是"让模型把先前立场当既定事实"这个风险，而不是拒绝服务。</li>
 * </ul>
 *
 * <p><b>它不是安全边界，也明确承认会漏</b>：规则只匹配高置信度模式（宁可漏、不可误伤），
 * 真正的硬防线仍是能力最小化（写操作必须用户确认、只读工具参数里没有主体字段）。
 * 正则的定位是省一次调用 + 拿掉模型自由裁量，不是拦住精心构造的攻击。</p>
 *
 * <p>纯函数、无状态，可直接单测（见 {@code PreRouteGuardTest}）。</p>
 */
@Slf4j
@Component
public class PreRouteGuard {

    /** 判定结论。 */
    public enum Verdict {
        /** 正常放行 */
        PASS,
        /** 不受理：直接回引导文案，不调用模型、不落会话历史 */
        BLOCK,
        /** 受理，但推进上下文地平线：此前对话不再作为依据 */
        RESET
    }

    /**
     * 判定结果。
     *
     * @param verdict 结论
     * @param ruleId  命中的规则号（未命中为空串）。落审计的是规则号而不是用户原文——
     *                排障时能分清"这条规则误伤了"与"确实有人在试探"，而规则号本身不敏感
     */
    public record Decision(Verdict verdict, String ruleId) {

        private static final Decision PASS = new Decision(Verdict.PASS, "");

        static Decision pass() {
            return PASS;
        }

        public boolean blocked() {
            return verdict == Verdict.BLOCK;
        }

        public boolean reset() {
            return verdict == Verdict.RESET;
        }
    }

    /** 一条规则：命中即返回对应结论。 */
    private record Rule(String id, Verdict verdict, Pattern pattern) {
    }

    /** 英文匹配统一忽略大小写；中文无大小写概念，不受影响。 */
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    /**
     * 规则表（顺序即优先级：BLOCK 规则必须排在 RESET 之前）。
     *
     * <p>写这些正则的总原则：**宁可漏，不可误伤**。误伤的代价是用户正常提问被拒或被莫名重置，
     * 比放过一次试探严重得多。故每一条都要求出现"明确的元话语标记"，而不是泛泛的动词。</p>
     */
    private static final List<Rule> RULES = List.of(
            // ---- 元请求：索取系统提示词（本应用里没有正常读法）----
            new Rule("meta.system-prompt", Verdict.BLOCK, Pattern.compile(
                    "(系统|平台)\\s*(提示词|提示语|指令集)"
                            + "|(你的|你收到的)\\s*(系统)?\\s*(提示词|指令|规则|设定|人设)\\s*(是什么|内容|全文|原文|列出来)"
                            + "|system\\s*prompt"
                            + "|(repeat|print|show|reveal|tell\\s+me)\\s+(me\\s+)?your\\s+(system\\s+)?"
                            + "(prompt|instruction|rule|configuration|setting)s?",
                    FLAGS)),

            // ---- 元请求：越狱模式（同样是"要求解锁一个不存在的模式"）----
            new Rule("meta.jailbreak-mode", Verdict.BLOCK, Pattern.compile(
                    "开发者模式|开发者选项|越狱模式|无限制模式|不受限制模式|上帝模式"
                            + "|jailbreak|developer\\s+mode|\\bDAN\\b",
                    FLAGS)),

            // ---- 覆盖类：忽略/忘掉既有指令（正常纠正，也作注入开场白 → 只重置不拦）----
            new Rule("override.ignore-instructions", Verdict.RESET, Pattern.compile(
                    "(忽略|无视|忘记|忘掉|丢弃|抛弃|不用管|不要理会|别管)\\s*.{0,8}?"
                            + "(以上|之前|上面|前面|先前|此前|所有|全部|这些|你)(的)?\\s*.{0,6}?"
                            + "(指令|规则|设定|限制|约束|要求|身份|人设|提示|对话|上下文)",
                    FLAGS)),

            new Rule("override.ignore-english", Verdict.RESET, Pattern.compile(
                    "ignore\\s+(all\\s+|the\\s+|any\\s+)*(previous|prior|above|earlier|preceding|foregoing)\\s+"
                            + "(instruction|prompt|rule|direction|message)s?",
                    FLAGS)),

            // ---- 覆盖类：显式要求清空上下文（这本来就该重置）----
            // 中间的 `的?` 不能省："忘记所有的记忆"这种插一个"的"的说法很常见，少了它就会漏
            new Rule("override.reset-context", Verdict.RESET, Pattern.compile(
                    "(重置|清空|清除|忘掉|忘记|删除)\\s*(一下)?\\s*(之前的|以上|所有|全部|我们)?\\s*的?\\s*"
                            + "(对话|上下文|记忆|聊天记录|会话)",
                    FLAGS)),

            // ---- 覆盖类：要求解除约束 / 换身份 ----
            // "没有限制"不单独成规则：它是"有没有限制"的子串，会误伤正常提问
            new Rule("override.drop-constraints", Verdict.RESET, Pattern.compile(
                    "(摆脱|解除|去掉|取消|抛开)\\s*.{0,6}(限制|约束|规则|设定)"
                            + "|(不受|不再受|无需再受|不必再受)\\s*.{0,4}(限制|约束)"
                            + "|(从现在起|现在开始|接下来|以后)\\s*你\\s*(不再是|扮演|假装)",
                    FLAGS))
    );

    /**
     * 判定一条用户输入。
     *
     * @param message 用户本轮原文（**只看用户自己写的话**，不看页面上下文/文档/工具结果——
     *                那些是不可信内容里混进来的字面匹配，判定它们会把正常内容误判成攻击）
     * @return 判定结果；空输入一律放行
     */
    public Decision judge(String message) {
        if (!StringUtils.hasText(message)) {
            return Decision.pass();
        }
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(message).find()) {
                // 只记规则号，不记用户原文（原文可能含敏感信息；需要时从审计表的定长摘录里看）
                log.info("前置闸门命中规则 rule={} verdict={}", rule.id(), rule.verdict());
                return new Decision(rule.verdict(), rule.id());
            }
        }
        return Decision.pass();
    }
}
