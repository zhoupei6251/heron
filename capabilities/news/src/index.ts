import type { CapabilityManifest } from "@heron/contracts";

export const NEWS_CAPABILITY = "news";

/** manifest 里声明的提示词 key；worker 据此判断这个 capability 要不要真写稿。 */
export const SCRIPT_PROMPT_KEY = "news.script.v1";

export const SCRIPT_SYSTEM_PROMPT = `你是短视频新闻编辑。只输出 JSON，不要 markdown 代码块，不要解释。
输出形状：{"title":"整条片的标题","scenes":[{"body":"这一镜的口播","onscreen":"可选的上屏短句"}]}
规则：
- title 不超过 30 字；
- scenes 2 到 8 镜，按叙事顺序；
- body 不能为空，每镜一到两句，整条片合计不超过 200 字；
- onscreen 可省略；写了就不能和 title 是同一句话（版式会并排打两遍）；
- 只能使用输入里给出的信息，没有的数字、人名、引语不要编。`;

export interface ScriptInput {
  title: string;
  summary: string;
}

export function buildScriptPrompt(input: ScriptInput): string {
  return [
    `标题：${input.title}`,
    `摘要：${input.summary.trim() || "（无）"}`,
    "请按约定输出 JSON 稿子。",
  ].join("\n");
}

export const newsManifest: CapabilityManifest = {
  name: NEWS_CAPABILITY,
  pipeline: [
    "candidate",
    "claimed",
    "scripting",
    "factcheck",
    "rendering",
    "review",
    "approved",
    "publishing",
    "published",
  ],
  gates: ["dedupe", "contract", "visual", "compliance", "human"],
  layouts: [],
  promptKeys: [SCRIPT_PROMPT_KEY],
};
