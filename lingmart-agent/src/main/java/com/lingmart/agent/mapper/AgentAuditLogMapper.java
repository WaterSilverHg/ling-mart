package com.lingmart.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmart.agent.entity.AgentAuditLog;

/**
 * Agent 操作审计日志 Mapper。
 *
 * <p>无自定义 SQL：审计日志只用 {@code insert}（写）与 {@code delete}（按时间清理），
 * 查询走 MyBatis-Plus 的 {@code QueryWrapper}（见 AgentAuditService）。</p>
 */
public interface AgentAuditLogMapper extends BaseMapper<AgentAuditLog> {
}
