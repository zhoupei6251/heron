-- 域前缀表名 · 雪花 id · 审计四件套 · 逻辑删除
--
-- 这一条迁移把 22 张表整体换骨架：表名按 <域>_<实体单数>，主键与外键从 uuid 换成
-- numeric(19,0) 的雪花 id，每张表补上 create_by / create_time / update_by / update_time，
-- 9 张业务实体表加上 del_flag。
--
-- 为什么全写成 ALTER 而不是重建表：库里已经有跑出来的数据（成片、发布记录、审计）。
-- 重建表会把它清空——那是不可逆的，而这次改的是「骨架」，不是「内容」。
--
-- 为什么写成一条迁移而不是三条：三条会分三次提交事务，中间那次失败就留下一个
-- 「改了一半」的库——表名改了、列类型没改、外键指向半路。一条迁移是一个事务，
-- 要么全成，要么全不成。
--
-- uuid → numeric 的映射：取 uuid 十六进制的前 13 位（52 bit）。
--   值域 < 4.5e15，而新的雪花 id 今天在 8.9e17 量级，两段值域不重叠，存量行不会与新行撞号。
--   映射是 uuid 的纯函数，所以同一个 uuid 在任何表、任何外键上都映射成同一个数，外键不用手工修补。

-- ── 一、主键与外键：uuid → numeric(19,0) ──────────────────────────────
-- uuid → numeric 不是二进制可强转的，列上有外键时直接改类型会被 Postgres 拒掉。
-- 所以先把依赖这些列的外键摘下来（定义先存进临时表），改完列再按原定义装回去。
DO $do$
DECLARE
  col record;
  fk record;
BEGIN
  CREATE TEMP TABLE _heron_fk_backup ON COMMIT DROP AS
  SELECT
    con.conname AS name,
    con.conrelid::regclass::text AS tbl,
    pg_get_constraintdef(con.oid) AS def
  FROM pg_constraint con
  WHERE con.contype = 'f'
    AND (
      con.conrelid::regclass::text IN (SELECT tablename FROM pg_tables WHERE schemaname = 'public')
      OR con.confrelid::regclass::text IN (SELECT tablename FROM pg_tables WHERE schemaname = 'public')
    );

  FOR fk IN SELECT name, tbl FROM _heron_fk_backup LOOP
    EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', fk.tbl, fk.name);
  END LOOP;

  FOR col IN
    SELECT table_name, column_name
    FROM information_schema.columns
    WHERE table_schema = 'public' AND data_type = 'uuid'
  LOOP
    EXECUTE format(
      'ALTER TABLE %I ALTER COLUMN %I TYPE numeric(19,0) USING ((''x'' || substr(replace(%I::text,''-'',''''),1,13))::bit(52)::bigint::numeric)',
      col.table_name, col.column_name, col.column_name
    );
  END LOOP;

  -- 哨兵租户：老库的 DEFAULT_TENANT_ID 是那个固定 uuid，映射之后落到 4。
  -- 代码里写的是 1（新的保留哨兵），趁外键还摘着，把 id 与所有 tenant_id 引用一起归一。
  UPDATE tenants SET id = 1 WHERE id = 4;
  UPDATE users SET tenant_id = 1 WHERE tenant_id = 4;
  UPDATE sessions SET tenant_id = 1 WHERE tenant_id = 4;
  UPDATE audit_logs SET tenant_id = 1 WHERE tenant_id = 4;
  UPDATE platform_accounts SET tenant_id = 1 WHERE tenant_id = 4;
  UPDATE settings SET tenant_id = 1 WHERE tenant_id = 4;
  UPDATE feed_sources SET tenant_id = 1 WHERE tenant_id = 4;
  UPDATE candidates SET tenant_id = 1 WHERE tenant_id = 4;
  UPDATE tasks SET tenant_id = 1 WHERE tenant_id = 4;
  UPDATE confirmations SET tenant_id = 1 WHERE tenant_id = 4;
  UPDATE publishes SET tenant_id = 1 WHERE tenant_id = 4;
  UPDATE aigc_posture SET tenant_id = 1 WHERE tenant_id = 4;
  UPDATE usage_records SET tenant_id = 1 WHERE tenant_id = 4;
  UPDATE proto_projects SET tenant_id = 1 WHERE tenant_id = 4;

  FOR fk IN SELECT name, tbl, def FROM _heron_fk_backup LOOP
    EXECUTE format('ALTER TABLE %s ADD CONSTRAINT %I %s', fk.tbl, fk.name, fk.def);
  END LOOP;
  DROP TABLE _heron_fk_backup;
END $do$;
--> statement-breakpoint

-- ── 二、两条序列主键换成雪花 ──────────────────────────────────────────
-- task_events 与 usage_records 的主键原来是 identity 序列。现在 id 一律是应用层生成的雪花，
-- 序列留着就会出现两套 id：库里自增一个、应用层再生成一个，两套混在一张表里。
ALTER TABLE task_events ALTER COLUMN id DROP IDENTITY;
--> statement-breakpoint
ALTER TABLE task_events ALTER COLUMN id DROP DEFAULT;
--> statement-breakpoint
ALTER TABLE task_events ALTER COLUMN id TYPE numeric(19,0) USING id::numeric;
--> statement-breakpoint
DROP SEQUENCE IF EXISTS task_events_id_seq;
--> statement-breakpoint
ALTER TABLE usage_records ALTER COLUMN id DROP IDENTITY;
--> statement-breakpoint
ALTER TABLE usage_records ALTER COLUMN id DROP DEFAULT;
--> statement-breakpoint
ALTER TABLE usage_records ALTER COLUMN id TYPE numeric(19,0) USING id::numeric;
--> statement-breakpoint
DROP SEQUENCE IF EXISTS usage_records_id_seq;
--> statement-breakpoint

-- ── 三、表名：<域>_<实体单数> ─────────────────────────────────────────
ALTER TABLE tenants RENAME TO sys_tenant;
--> statement-breakpoint
ALTER TABLE users RENAME TO sys_user;
--> statement-breakpoint
ALTER TABLE sessions RENAME TO sys_session;
--> statement-breakpoint
ALTER TABLE audit_logs RENAME TO sys_audit_log;
--> statement-breakpoint
ALTER TABLE platform_accounts RENAME TO plat_account;
--> statement-breakpoint
ALTER TABLE platform_operators RENAME TO plat_operator;
--> statement-breakpoint
ALTER TABLE platform_audit_logs RENAME TO plat_audit_log;
--> statement-breakpoint
ALTER TABLE platform_settings RENAME TO plat_setting;
--> statement-breakpoint
ALTER TABLE settings RENAME TO news_setting;
--> statement-breakpoint
ALTER TABLE feed_sources RENAME TO news_feed_source;
--> statement-breakpoint
ALTER TABLE candidates RENAME TO news_candidate;
--> statement-breakpoint
ALTER TABLE tasks RENAME TO news_task;
--> statement-breakpoint
ALTER TABLE task_events RENAME TO news_task_event;
--> statement-breakpoint
ALTER TABLE gate_results RENAME TO news_gate_result;
--> statement-breakpoint
ALTER TABLE renders RENAME TO news_render;
--> statement-breakpoint
ALTER TABLE scripts RENAME TO news_script;
--> statement-breakpoint
ALTER TABLE confirmations RENAME TO news_confirmation;
--> statement-breakpoint
ALTER TABLE publishes RENAME TO news_publish;
--> statement-breakpoint
ALTER TABLE aigc_posture RENAME TO news_posture;
--> statement-breakpoint
ALTER TABLE usage_records RENAME TO usage_record;
--> statement-breakpoint
ALTER TABLE proto_projects RENAME TO proto_project;
--> statement-breakpoint
ALTER TABLE proto_revisions RENAME TO proto_revision;
--> statement-breakpoint

-- ── 四、索引与约束跟着表名改 ─────────────────────────────────────────
-- 索引名不改的话，库里还挂着旧表名的对象名，排查时「这个索引是哪张表的」要靠猜。
-- 约束与它们的索引一起改（Postgres 里两者同名），外键的名字里也带着父表名，一并换掉。
DO $do$
DECLARE
  m record;
  idx record;
  con record;
  new_name text;
BEGIN
  FOR m IN
    SELECT * FROM (VALUES
      ('tenants', 'sys_tenant'), ('users', 'sys_user'), ('sessions', 'sys_session'),
      ('audit_logs', 'sys_audit_log'), ('platform_accounts', 'plat_account'),
      ('platform_operators', 'plat_operator'), ('platform_audit_logs', 'plat_audit_log'),
      ('platform_settings', 'plat_setting'), ('settings', 'news_setting'),
      ('feed_sources', 'news_feed_source'), ('candidates', 'news_candidate'),
      ('tasks', 'news_task'), ('task_events', 'news_task_event'),
      ('gate_results', 'news_gate_result'), ('renders', 'news_render'),
      ('scripts', 'news_script'), ('confirmations', 'news_confirmation'),
      ('publishes', 'news_publish'), ('aigc_posture', 'news_posture'),
      ('usage_records', 'usage_record'), ('proto_projects', 'proto_project'),
      ('proto_revisions', 'proto_revision')
    ) AS t(old_name, new_name)
  LOOP
    -- 约束（主键、唯一、检查、外键）：改名会带着它们的索引一起改
    FOR con IN
      SELECT conname FROM pg_constraint WHERE conrelid = to_regclass(m.new_name)::oid
    LOOP
      EXECUTE format(
        'ALTER TABLE %I RENAME CONSTRAINT %I TO %I',
        m.new_name, con.conname, replace(con.conname, m.old_name, m.new_name)
      );
    END LOOP;

    -- 不是约束的索引（drizzle 的 index() / uniqueIndex() 建的）：单独改
    FOR idx IN
      SELECT c.relname FROM pg_class c
      JOIN pg_index i ON i.indexrelid = c.oid
      WHERE i.indrelid = to_regclass(m.new_name)::oid
        AND c.relkind = 'i'
        AND NOT EXISTS (SELECT 1 FROM pg_constraint pc WHERE pc.conindid = c.oid)
    LOOP
      EXECUTE format(
        'ALTER INDEX %I RENAME TO %I', idx.relname, replace(idx.relname, m.old_name, m.new_name)
      );
    END LOOP;
  END LOOP;

  -- 外键的名字里带着父表名（renders_task_id_tasks_id_fkey）。父表改名时它不在同一张表上，
  -- 上面那一轮碰不到它，所以再全局扫一遍，把剩下的旧表名换掉。
  FOR con IN
    SELECT conname, conrelid::regclass::text AS tbl FROM pg_constraint
    WHERE connamespace = 'public'::regnamespace
  LOOP
    new_name := con.conname;
    FOR m IN
      SELECT * FROM (VALUES
        ('tenants', 'sys_tenant'), ('users', 'sys_user'), ('sessions', 'sys_session'),
        ('audit_logs', 'sys_audit_log'), ('platform_accounts', 'plat_account'),
        ('platform_operators', 'plat_operator'), ('platform_audit_logs', 'plat_audit_log'),
        ('platform_settings', 'plat_setting'), ('settings', 'news_setting'),
        ('feed_sources', 'news_feed_source'), ('candidates', 'news_candidate'),
        ('tasks', 'news_task'), ('task_events', 'news_task_event'),
        ('gate_results', 'news_gate_result'), ('renders', 'news_render'),
        ('scripts', 'news_script'), ('confirmations', 'news_confirmation'),
        ('publishes', 'news_publish'), ('aigc_posture', 'news_posture'),
        ('usage_records', 'usage_record'), ('proto_projects', 'proto_project'),
        ('proto_revisions', 'proto_revision')
      ) AS t(old_name, new_name)
    LOOP
      new_name := replace(new_name, m.old_name, m.new_name);
    END LOOP;
    IF new_name <> con.conname THEN
      EXECUTE format(
        'ALTER TABLE %s RENAME CONSTRAINT %I TO %I', con.tbl, con.conname, new_name
      );
    END IF;
  END LOOP;
END $do$;
--> statement-breakpoint

-- ── 五、审计四件套 ───────────────────────────────────────────────────
-- created_at / updated_at 改名成 create_time / update_time（参考项目的命名），
-- 缺的两列补上，四张表外加 create_by / update_by 两个操作人列。
-- 时间列的默认值与 packages/db/src/time.ts 的 nowIso() 同形：UTC 的 ISO-8601，带毫秒。
DO $do$
DECLARE
  r record;
  tbl text;
  ts_default text := 'to_char(now() at time zone ''utc'', ''YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'')';
BEGIN
  FOR r IN
    SELECT table_name FROM information_schema.columns
    WHERE table_schema = 'public' AND column_name = 'created_at'
  LOOP
    EXECUTE format('ALTER TABLE %I RENAME COLUMN "created_at" TO "create_time"', r.table_name);
    EXECUTE format('ALTER TABLE %I ALTER COLUMN "create_time" SET DEFAULT %s', r.table_name, ts_default);
  END LOOP;

  FOR r IN
    SELECT table_name FROM information_schema.columns
    WHERE table_schema = 'public' AND column_name = 'updated_at'
  LOOP
    EXECUTE format('ALTER TABLE %I RENAME COLUMN "updated_at" TO "update_time"', r.table_name);
    EXECUTE format('ALTER TABLE %I ALTER COLUMN "update_time" SET DEFAULT %s', r.table_name, ts_default);
  END LOOP;

  FOR r IN SELECT tablename AS tbl FROM pg_tables WHERE schemaname = 'public' LOOP
    tbl := r.tbl;
    IF NOT EXISTS (
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = 'public' AND table_name = tbl AND column_name = 'create_time'
    ) THEN
      EXECUTE format('ALTER TABLE %I ADD COLUMN "create_time" text DEFAULT %s NOT NULL', tbl, ts_default);
    END IF;
    IF NOT EXISTS (
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = 'public' AND table_name = tbl AND column_name = 'update_time'
    ) THEN
      EXECUTE format('ALTER TABLE %I ADD COLUMN "update_time" text DEFAULT %s NOT NULL', tbl, ts_default);
      -- 刚补出来的 update_time 默认是「现在」，但这些表以前从来没有 updated_at 列，
      -- 它们最后一次被改的时间无从得知；用 create_time 比用 now() 诚实。
      EXECUTE format('UPDATE %I SET "update_time" = "create_time"', tbl);
    END IF;
    IF NOT EXISTS (
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = 'public' AND table_name = tbl AND column_name = 'create_by'
    ) THEN
      EXECUTE format('ALTER TABLE %I ADD COLUMN "create_by" numeric(19,0)', tbl);
    END IF;
    IF NOT EXISTS (
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = 'public' AND table_name = tbl AND column_name = 'update_by'
    ) THEN
      EXECUTE format('ALTER TABLE %I ADD COLUMN "update_by" numeric(19,0)', tbl);
    END IF;
  END LOOP;
END $do$;
--> statement-breakpoint

-- 两列被四件套取代了：老的「谁建的/谁改的」各只一列，四件套里是 create_by / update_by。
-- 不删就会留下两套含义重叠的列，而其中一套还没有人写——库里与代码说的就不是一回事了。
ALTER TABLE proto_project DROP COLUMN IF EXISTS created_by;
--> statement-breakpoint
ALTER TABLE plat_setting DROP COLUMN IF EXISTS updated_by;
--> statement-breakpoint

-- ── 六、逻辑删除标记 ─────────────────────────────────────────────────
-- 只有业务实体表有 del_flag（'0' 存在 / '1' 已删，形状照参考项目）。证据与流水表（审计、闸门结果、人工确认、发布记录、用量、
-- 事件）一律不加：人工确认与闸门结果是「人审 A、系统发 B」的对照物，删了就没法对账。
ALTER TABLE sys_tenant ADD COLUMN "del_flag" char(1) NOT NULL DEFAULT '0';
--> statement-breakpoint
ALTER TABLE sys_user ADD COLUMN "del_flag" char(1) NOT NULL DEFAULT '0';
--> statement-breakpoint
ALTER TABLE plat_operator ADD COLUMN "del_flag" char(1) NOT NULL DEFAULT '0';
--> statement-breakpoint
ALTER TABLE news_feed_source ADD COLUMN "del_flag" char(1) NOT NULL DEFAULT '0';
--> statement-breakpoint
ALTER TABLE news_candidate ADD COLUMN "del_flag" char(1) NOT NULL DEFAULT '0';
--> statement-breakpoint
ALTER TABLE news_task ADD COLUMN "del_flag" char(1) NOT NULL DEFAULT '0';
--> statement-breakpoint
ALTER TABLE news_script ADD COLUMN "del_flag" char(1) NOT NULL DEFAULT '0';
--> statement-breakpoint
ALTER TABLE proto_project ADD COLUMN "del_flag" char(1) NOT NULL DEFAULT '0';
--> statement-breakpoint
ALTER TABLE proto_revision ADD COLUMN "del_flag" char(1) NOT NULL DEFAULT '0';
--> statement-breakpoint

-- 软删之后，被软删的行仍然占着唯一键——不处理的话，「删掉一个叫 A 的数据源，再建一个同 URL 的」
-- 会撞唯一索引，而报错完全指不到软删上。唯一索引改成 partial：只对活着的行唯一。
DROP INDEX IF EXISTS u_sys_user_tenant_email;
--> statement-breakpoint
CREATE UNIQUE INDEX "u_sys_user_tenant_email" ON "sys_user" ("tenant_id","email") WHERE "del_flag" = '0';
--> statement-breakpoint
DROP INDEX IF EXISTS u_plat_operator_username;
--> statement-breakpoint
CREATE UNIQUE INDEX "u_plat_operator_username" ON "plat_operator" ("username") WHERE "del_flag" = '0';
--> statement-breakpoint
DROP INDEX IF EXISTS u_news_feed_source_tenant_url;
--> statement-breakpoint
CREATE UNIQUE INDEX "u_news_feed_source_tenant_url" ON "news_feed_source" ("tenant_id","url") WHERE "del_flag" = '0';
--> statement-breakpoint
DROP INDEX IF EXISTS u_news_candidate_tenant_norm;
--> statement-breakpoint
CREATE UNIQUE INDEX "u_news_candidate_tenant_norm" ON "news_candidate" ("tenant_id","norm_key") WHERE "del_flag" = '0';
--> statement-breakpoint
DROP INDEX IF EXISTS u_news_script_task;
--> statement-breakpoint
CREATE UNIQUE INDEX "u_news_script_task" ON "news_script" ("task_id") WHERE "del_flag" = '0';
--> statement-breakpoint
DROP INDEX IF EXISTS u_proto_revision_project_version;
--> statement-breakpoint
CREATE UNIQUE INDEX "u_proto_revision_project_version" ON "proto_revision" ("project_id","version") WHERE "del_flag" = '0';
--> statement-breakpoint
ALTER TABLE sys_tenant DROP CONSTRAINT IF EXISTS sys_tenant_slug_unique;
--> statement-breakpoint
CREATE UNIQUE INDEX "u_sys_tenant_slug" ON "sys_tenant" ("slug") WHERE "del_flag" = '0';
--> statement-breakpoint

-- ── 七、update_time 由触发器维护 ─────────────────────────────────────
-- 对齐参考项目的 ON UPDATE CURRENT_TIMESTAMP。应用层只管 update_by（触发器不知道是谁改的）。
CREATE OR REPLACE FUNCTION heron_set_update_time() RETURNS trigger AS $$
BEGIN
  NEW.update_time := to_char(now() at time zone 'utc', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"');
  RETURN NEW;
END $$ LANGUAGE plpgsql;
--> statement-breakpoint
DO $do$
DECLARE
  r record;
BEGIN
  FOR r IN SELECT tablename AS tbl FROM pg_tables WHERE schemaname = 'public' LOOP
    EXECUTE format(
      'CREATE TRIGGER %I BEFORE UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION heron_set_update_time()',
      'tg_' || r.tbl || '_update_time', r.tbl
    );
  END LOOP;
END $do$;
