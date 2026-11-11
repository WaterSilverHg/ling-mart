-- =============================================================
-- 轻营 · 网页浮窗 Agent 数据库初始化脚本
-- 数据库：lingmart_agent（Agent 独立库）
-- 说明：只放「操作审计日志」。会话记忆/限流/挂起态仍在 Redis（可丢失的运行时状态），
--       审计日志必须落库——它要用于事后追溯「AI 建议了什么、用户确认了什么、结果如何」，
--       而本项目的 Redis 是匿名卷 + 默认 RDB 快照（`docker compose down && up` 后卷不复用、
--       快照需 100 改/300s 才触发），不适合承载审计。
-- =============================================================

CREATE DATABASE IF NOT EXISTS `lingmart_agent`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE `lingmart_agent`;

-- -------------------------------------------------------------
-- 操作审计日志（append-only）
--   写入策略：同步写、失败只告警不阻断对话（审计是附属能力，不能因审计写失败而拖垮主链路）
--   保留策略：默认 30 天，由 AgentAuditService 的定时任务硬删除（非逻辑删除——
--             审计数据不该"软删除后仍可被查到"，也不该被 deleted 字段改变查询语义）
--   敏感信息最小化：不存用户消息正文，只存定长摘录（message_excerpt），长度可配 0 关闭
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `agent_audit_log`;
CREATE TABLE `agent_audit_log`
(
    `id`              BIGINT        NOT NULL COMMENT '主键（雪花，应用侧分配）',
    `user_id`         BIGINT                 DEFAULT NULL COMMENT '用户ID（Agent 自行验签 JWT 后解析，不取网关透传的 X-User-Id）',
    `session_id`      VARCHAR(64)            DEFAULT NULL COMMENT '会话ID',
    `event_type`      VARCHAR(32)   NOT NULL COMMENT '事件类型：tool_proposed/tool_run/user_confirmed/user_cancelled/receipt_rejected/turn_completed/preroute_blocked/context_reset（后两个为 v0.30 四层约束策略：前置闸门拦下 / 上下文地平线推进）',
    `tool_call_id`    VARCHAR(64)            DEFAULT NULL COMMENT '模型侧工具调用ID',
    `tool_name`       VARCHAR(64)            DEFAULT NULL COMMENT '工具名：navigate_to/click_element/prefill_shop_application（只读工具的事件见 tool_run）',
    `action`          VARCHAR(32)            DEFAULT NULL COMMENT '前端动作：navigate/click/prefill',
    `arguments`       VARCHAR(1024)          DEFAULT NULL COMMENT '工具参数 JSON（route/elementId/elementText 等）',
    `purpose`         VARCHAR(512)           DEFAULT NULL COMMENT '操作目的说明（确认卡片文案）',
    `user_action`     VARCHAR(16)            DEFAULT NULL COMMENT '用户动作：confirm/cancel',
    `result`          VARCHAR(32)            DEFAULT NULL COMMENT '结果：proposed/success/failure/rejected/text/tool/empty/ok/cache_hit/quota_exceeded/failed/blocked/reset',
    `error_msg`       VARCHAR(512)           DEFAULT NULL COMMENT '失败原因或错误详情',
    `message_excerpt` VARCHAR(160)           DEFAULT NULL COMMENT '用户消息摘录（定长截断以最小化敏感信息；配置为 0 则不写）',
    `ts`              DATETIME(3)   NOT NULL COMMENT '事件时间（毫秒精度，同轮内可排序）',
    PRIMARY KEY (`id`),
    KEY `idx_user_ts` (`user_id`, `ts`),
    KEY `idx_session_ts` (`session_id`, `ts`),
    KEY `idx_ts` (`ts`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='Agent 操作审计日志';
