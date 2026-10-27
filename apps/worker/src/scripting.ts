import type { Task } from "@heron/contracts";
import { SCRIPT_PROMPT_KEY, SCRIPT_SYSTEM_PROMPT, buildScriptPrompt } from "@heron/capability-news";
import {
  getCandidate,
  getModelSettings,
  recordUsage,
  upsertScript,
  type TenantDb,
} from "@heron/db/pg";

import {
  chat,
  resolveModelConfig,
  type ChatMessage,
  type ChatReply,
  type ModelConfig,
} from "./model";

export type ChatFn = (config: ModelConfig, messages: ChatMessage[]) => Promise<ChatReply>;

/** 测试替掉真模型时只换 chat / config；解析、落库与记账仍走真实路径。 */
export interface ScriptDeps {
  chat?: ChatFn;
  modelConfig?: ModelConfig;
}

/** capability 在 manifest 里声明了这条提示词，才意味着它要真写稿（p0-fake 因此保持空转）。 */
export function declaresScript(promptKeys: readonly string[]): boolean {
  return promptKeys.includes(SCRIPT_PROMPT_KEY);
}

/**
 * 模型常见两种包装：```json 围栏，或前后带一句解释。
 * 只取最外层 {...}，解析不了就报错——写稿阶段不替模型猜，契约闸只判已落库的 JSON。
 */
export function parseScriptJson(text: string): unknown {
  const fenced = /```(?:json)?\s*([\s\S]*?)```/i.exec(text);
  const body = (fenced?.[1] ?? text).trim();
  const start = body.indexOf("{");
  const end = body.lastIndexOf("}");
  if (start === -1 || end <= start) {
    throw new Error(`模型没有输出 JSON 稿子：${body.slice(0, 200)}`);
  }
  const json = body.slice(start, end + 1);
  try {
    return JSON.parse(json);
  } catch {
    throw new Error(`模型输出的 JSON 解析失败：${json.slice(0, 200)}`);
  }
}

/**
 * 写稿阶段：候选 → 提示词 → 模型 → JSON 落库 + token 记账。
 * 返回模型名，只用于台账文案；失败一律抛出，由调用方把原因写进 error_reason。
 */
export async function generateScript(
  tdb: TenantDb,
  task: Task,
  deps: ScriptDeps = {},
): Promise<string> {
  if (!task.candidateId) throw new Error(`任务 ${task.id} 没有关联候选，无法写稿`);
  const candidate = await getCandidate(tdb, task.candidateId);
  if (!candidate) {
    throw new Error(`任务 ${task.id} 的候选不存在或不在当前租户，无法写稿`);
  }

  // 页面配置按租户存，在任务自己的租户上下文里读；没配的项回落到环境变量
  const config = deps.modelConfig ?? resolveModelConfig(await getModelSettings(tdb));
  const call = deps.chat ?? chat;
  let reply: ChatReply;
  try {
    reply = await call(config, [
      { role: "system", content: SCRIPT_SYSTEM_PROMPT },
      {
        role: "user",
        content: buildScriptPrompt({ title: candidate.title, summary: candidate.summary }),
      },
    ]);
  } catch (cause) {
    throw new Error(`写稿模型调用失败：${cause instanceof Error ? cause.message : String(cause)}`, {
      cause,
    });
  }

  const content = parseScriptJson(reply.content);
  await upsertScript(tdb, { taskId: task.id, model: reply.model, content });
  // 供应商没回 usage 就不记：拿 0 充数会让账单看起来「免费」，那是最坏的一种静默
  if (reply.tokensIn !== null) {
    await recordUsage(tdb, {
      kind: "llm_tokens_in",
      quantity: String(reply.tokensIn),
      unit: "tokens",
      taskId: task.id,
      detail: { model: reply.model },
    });
  }
  if (reply.tokensOut !== null) {
    await recordUsage(tdb, {
      kind: "llm_tokens_out",
      quantity: String(reply.tokensOut),
      unit: "tokens",
      taskId: task.id,
      detail: { model: reply.model },
    });
  }
  return reply.model;
}
