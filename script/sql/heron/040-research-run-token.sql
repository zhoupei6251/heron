-- 研究任务的执行令牌。
-- 为什么需要：attempt_no 只区分「第几次」，不区分「是谁」。第一次执行超时被第二次领走后，
-- 第一次若还没真停，它会继续给第二次的租约续命，并在结束时按 research_id 覆盖第二次的结果。
-- 令牌在领取时生成，心跳、成功写回、失败写回、取消收尾四处都带上它：
-- 旧执行者失去令牌后只能拿到 0 行更新，原地退出。
-- 回滚：ALTER TABLE heron_research_task DROP COLUMN run_token;
ALTER TABLE heron_research_task
  ADD COLUMN run_token varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL
    COMMENT '本次执行的令牌，领取时生成；写回必须匹配 attempt_no + run_token' AFTER attempt_no;
