/**
 * 唯一的模型出口：OpenAI Chat Completions 协议。供应商（七牛 / 方舟 / DeepSeek / 本地 Ollama…
 * 只要它兼容这套接口）在「工作空间设置」页配，环境变量兜底，两者都不改代码。
 *
 * 密钥只在调用这一刻出现：不进日志、不进错误文案、不进审计台账。
 */

export interface ModelConfig {
  /** 不带结尾斜杠，请求拼 `${baseUrl}/chat/completions`。 */
  baseUrl: string;
  /** 本地模型（Ollama / LM Studio）通常不需要；云端地址没它就拒绝调用。 */
  apiKey: string;
  model: string;
}

export interface ChatMessage {
  role: "system" | "user" | "assistant";
  content: string;
}

export interface ChatReply {
  content: string;
  /** 供应商没回 usage 时是 null：没有数就不记账，别拿 0 冒充。 */
  tokensIn: number | null;
  tokensOut: number | null;
  model: string;
}

export class EmptyModelReplyError extends Error {
  constructor(readonly reply: ChatReply) {
    super("模型返回了空内容");
    this.name = "EmptyModelReplyError";
  }
}

/** 七牛云 AI 推理的 OpenAI 兼容入口。换厂商就覆盖 HERON_MODEL_BASE_URL。 */
const DEFAULT_BASE_URL = "https://api.qnaigc.com/v1";
/** 本机模型不需要密钥。容器里连宿主上的 Ollama 走 host.docker.internal。 */
const KEYLESS_HOSTS = new Set(["localhost", "127.0.0.1", "::1", "[::1]", "host.docker.internal"]);
/** 租约 TTL 是 60s：单次调用拖过它，另一个 worker 就可能接管同一条任务去重写。 */
const TIMEOUT_MS = 50_000;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

/** 租户在页面上配的三项；null / 空串表示「这一项用兜底」。 */
export interface ModelSource {
  baseUrl?: string | null;
  apiKey?: string | null;
  model?: string | null;
}

/**
 * 合并顺序：页面配置 > 环境变量 > 默认地址。密钥与模型名没有安全默认值，
 * 所以缺模型名直接报错；密钥按地址区分——本地模型不需要。
 */
export function resolveModelConfig(
  source: ModelSource = {},
  env: NodeJS.ProcessEnv = process.env,
): ModelConfig {
  const baseUrl = (
    source.baseUrl?.trim() ||
    env.HERON_MODEL_BASE_URL?.trim() ||
    DEFAULT_BASE_URL
  ).replace(/\/+$/, "");
  const apiKey = source.apiKey?.trim() || env.HERON_MODEL_API_KEY?.trim() || "";
  const model = source.model?.trim() || env.HERON_MODEL_NAME?.trim() || "";

  let url: URL;
  try {
    url = new URL(`${baseUrl}/chat/completions`);
  } catch {
    throw new Error(`模型地址不是合法 URL：${baseUrl}`);
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") {
    throw new Error(`模型地址只接受 http/https：${baseUrl}`);
  }
  if (!model) {
    throw new Error(
      "未配置模型名：在「工作空间设置 → 写稿模型」里选一个，或设置 HERON_MODEL_NAME（七牛在「AI 大模型广场」里复制完整 id）",
    );
  }
  if (!apiKey && !KEYLESS_HOSTS.has(url.hostname)) {
    throw new Error(
      `模型地址 ${url.origin} 需要 API key：在「工作空间设置 → 写稿模型」里填，或设置 HERON_MODEL_API_KEY；本地模型地址才可以留空`,
    );
  }
  return { baseUrl, apiKey, model };
}

/** 一次对话。非 2xx、空内容、返回不是 JSON 都当场报错并带响应片段，不静默返回空稿。 */
export async function chat(config: ModelConfig, messages: ChatMessage[]): Promise<ChatReply> {
  const response = await fetch(`${config.baseUrl}/chat/completions`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      ...(config.apiKey ? { authorization: `Bearer ${config.apiKey}` } : {}),
    },
    body: JSON.stringify({ model: config.model, messages, temperature: 0.3 }),
    signal: AbortSignal.timeout(TIMEOUT_MS),
  });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`模型接口 ${response.status}：${text.slice(0, 300)}`);
  }

  let body: unknown;
  try {
    body = JSON.parse(text);
  } catch {
    throw new Error(`模型接口返回的不是 JSON：${text.slice(0, 200)}`);
  }
  const choices = isRecord(body) && Array.isArray(body.choices) ? body.choices : [];
  const first: unknown = choices[0];
  const message = isRecord(first) && isRecord(first.message) ? first.message : null;
  const content = message && typeof message.content === "string" ? message.content : "";
  const usage = isRecord(body) && isRecord(body.usage) ? body.usage : null;
  const reply: ChatReply = {
    content,
    tokensIn: usage && typeof usage.prompt_tokens === "number" ? usage.prompt_tokens : null,
    tokensOut:
      usage && typeof usage.completion_tokens === "number" ? usage.completion_tokens : null,
    model: config.model,
  };
  if (!content.trim()) throw new EmptyModelReplyError(reply);
  return reply;
}
