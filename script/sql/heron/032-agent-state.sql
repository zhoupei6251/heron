-- AgentState 落库：让研究上下文存 MySQL 而不是用户目录。
-- 之前落在 ~/.agentscope/state/<agentName>/...，既会跨轮污染（同名即复用），又会在部署机上无限累积。
-- 表结构由 io.agentscope.extensions.mysql.state.MysqlAgentStateStore 决定，本文件与其 DDL 一致；
-- 应用侧用 initializeSchema=false，不在运行时建表。
--
-- 键的构成（实测）：session_id = "<agentId>:<userId>"，state_key = 会话 id，item_index 为分段序号。
-- 因此「按研究隔离」要靠 state_key，也就是 builder 的 defaultSessionId。
CREATE TABLE IF NOT EXISTS heron_agent_state (
  session_id varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  state_key  varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  item_index int NOT NULL DEFAULT 0,
  state_data longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  version    bigint NOT NULL DEFAULT 0,
  created_at datetime DEFAULT CURRENT_TIMESTAMP,
  updated_at datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (session_id, state_key, item_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 回滚：DROP TABLE heron_agent_state;
