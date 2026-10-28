import { createServer, type IncomingMessage, type ServerResponse } from "node:http";

import { describe, expect, it } from "vitest";

import { chat, resolveModelConfig } from "./model";

interface StubServer {
  baseUrl: string;
  close: () => Promise<void>;
}

async function listen(
  handler: (req: IncomingMessage, res: ServerResponse, body: string) => void,
): Promise<StubServer> {
  const server = createServer((req, res) => {
    let body = "";
    req.on("data", (chunk: Buffer) => {
      body += chunk.toString("utf8");
    });
    req.on("end", () => {
      handler(req, res, body);
    });
  });
  await new Promise<void>((resolve) => {
    server.listen(0, "127.0.0.1", resolve);
  });
  const address = server.address();
  if (address === null || typeof address === "string") throw new Error("测试服务器没有拿到端口");
  return {
    baseUrl: `http://127.0.0.1:${address.port}/v1`,
    close: () =>
      new Promise<void>((resolve, reject) => {
        server.close((error) => {
          if (error) reject(error);
          else resolve();
        });
      }),
  };
}

describe("模型配置", () => {
  it("缺模型名直接拒绝：各家模型 id 不同，没有安全的默认值", () => {
    expect(() => resolveModelConfig({}, {})).toThrow(/模型名/);
  });

  it("云端地址没有密钥就拒绝，别把「忘了配」变成「用别人的额度」", () => {
    expect(() => resolveModelConfig({}, { HERON_MODEL_NAME: "deepseek-flash" })).toThrow(/API key/);
  });

  it("本地模型（Ollama / LM Studio）可以不带密钥", () => {
    expect(
      resolveModelConfig(
        {},
        {
          HERON_MODEL_BASE_URL: "http://127.0.0.1:11434/v1",
          HERON_MODEL_NAME: "qwen3",
        },
      ),
    ).toEqual({ baseUrl: "http://127.0.0.1:11434/v1", apiKey: "", model: "qwen3" });
  });

  it("环境变量仍然生效：没在页面上配时按它走，结尾斜杠被抹平", () => {
    expect(
      resolveModelConfig(
        {},
        {
          HERON_MODEL_BASE_URL: "https://api.example.com/openai/v1/",
          HERON_MODEL_API_KEY: "k",
          HERON_MODEL_NAME: "m",
        },
      ),
    ).toEqual({ baseUrl: "https://api.example.com/openai/v1", apiKey: "k", model: "m" });
  });

  it("页面配置优先于环境变量；页面密钥留空时用环境变量那把", () => {
    expect(
      resolveModelConfig(
        { baseUrl: "https://api.qnaigc.com/v1", model: "qiniu/conatus-free", apiKey: null },
        {
          HERON_MODEL_BASE_URL: "https://api.example.com/v1",
          HERON_MODEL_API_KEY: "env-key",
          HERON_MODEL_NAME: "env-model",
        },
      ),
    ).toEqual({
      baseUrl: "https://api.qnaigc.com/v1",
      apiKey: "env-key",
      model: "qiniu/conatus-free",
    });
  });
});

describe("chat（OpenAI 兼容）", () => {
  it("解析 choices 与 usage，并带上 Bearer", async () => {
    let seenPath = "";
    let seenAuth: string | undefined;
    const server = await listen((req, res, body) => {
      seenPath = req.url ?? "";
      seenAuth = req.headers.authorization;
      const request = JSON.parse(body) as { model?: string };
      expect(request.model).toBe("test-model");
      res.setHeader("content-type", "application/json");
      res.end(
        JSON.stringify({
          choices: [{ message: { content: "稿子" } }],
          usage: { prompt_tokens: 11, completion_tokens: 7 },
        }),
      );
    });
    try {
      const reply = await chat({ baseUrl: server.baseUrl, apiKey: "secret", model: "test-model" }, [
        { role: "user", content: "hi" },
      ]);
      expect(reply).toEqual({
        content: "稿子",
        tokensIn: 11,
        tokensOut: 7,
        model: "test-model",
      });
      expect(seenPath).toBe("/v1/chat/completions");
      expect(seenAuth).toBe("Bearer secret");
    } finally {
      await server.close();
    }
  });

  it("没回 usage 时记 null，不拿 0 冒充免费", async () => {
    const server = await listen((_req, res) => {
      res.end(JSON.stringify({ choices: [{ message: { content: "稿子" } }] }));
    });
    try {
      const reply = await chat({ baseUrl: server.baseUrl, apiKey: "", model: "m" }, [
        { role: "user", content: "hi" },
      ]);
      expect(reply).toMatchObject({ tokensIn: null, tokensOut: null });
    } finally {
      await server.close();
    }
  });

  it("非 2xx 带状态码与响应片段，失败不静默", async () => {
    const server = await listen((_req, res) => {
      res.statusCode = 429;
      res.end("rate limited");
    });
    try {
      await expect(
        chat({ baseUrl: server.baseUrl, apiKey: "", model: "m" }, [
          { role: "user", content: "hi" },
        ]),
      ).rejects.toThrow(/429.*rate limited/);
    } finally {
      await server.close();
    }
  });

  it.each(["", "  ", null])("空内容 %j 仍报错，但保留已消耗用量", async (content) => {
    const server = await listen((_req, res) => {
      res.end(
        JSON.stringify({
          choices: [{ message: { content } }],
          usage: { prompt_tokens: 11, completion_tokens: 7 },
        }),
      );
    });
    try {
      const response = chat({ baseUrl: server.baseUrl, apiKey: "", model: "m" }, [
        { role: "user", content: "hi" },
      ]);
      await expect(response).rejects.toThrow(/空内容/);
      await expect(response).rejects.toMatchObject({
        reply: { content: content ?? "", model: "m", tokensIn: 11, tokensOut: 7 },
      });
    } finally {
      await server.close();
    }
  });
});
