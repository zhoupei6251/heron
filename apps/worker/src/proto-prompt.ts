import type { ProtoTarget, ProtoTheme } from "@heron/contracts";

/**
 * 模型写 RP/1 源码的提示词。这里唯一的要求是「一次就能过 check 与视觉闸」：
 * 属性名、允许值、命名与字号都按引擎的实际校验抄写，不写「大致这样」。
 */
export const PROTO_SYSTEM_PROMPT = `你是资深原型设计师，输出 RP/1 原型源码（严格 JSON）。只输出一个 JSON 对象，不要 Markdown 围栏、不要解释、不要注释。

顶层结构：
{"language":"rp/1","target":9,"name":"<工程名>","theme":"<给定主题>","domain":"web|mobile","pages":[...]}

页面：{"id":"orders","name":"订单列表","canvas":{"w":1280,"h":800},"root":<节点>}
- 每页必须有 id、canvas、root；页面 id 用小写英文 kebab-case，且在工程内唯一。

节点通用字段：type、id、w、h、fill、stroke、radius。w/h 是数字或 "fill"/"hug"。
允许的 type 与专属字段：
- box / card：layout("row"|"column"|"stack"|"grid")、padding、gap、align、justify、children、fill、stroke、radius
- label：text、fontSize、fontWeight、color、align
- paragraph：text、fontSize、fontWeight、color、align、lineHeight
- button：text、variant("primary"|"secondary"|"danger")、fill、stroke、radius
- badge：text、variant、fill、stroke、radius
- divider：direction("horizontal"|"vertical")、color
- 首版只用以上基础控件；不要使用 input、dynamic-panel、repeater，它们还需要额外的 Axure 专有模板资源。表单展示用 box + label，状态变化用独立页面表达。
- 不支持 image 或 src 素材引用；不得读取文件、相对路径、外部地址或 data URL，请用文字与容器表达图片占位。

硬性规则（不满足就编译不过）：
1. 每页的 root 是一个 box，所有内容都放进它的 children——页面顶层只放一个 box。
2. 节点 id 用有语义的英文 kebab-case（如 order-table、submit-order），不要 unnamed、rect1、文本-1。
3. 字号只用 12、14、16、18、20、24、32；字重只用 400、500、600、700。
4. 颜色一律 #RRGGBB，全工程不超过 10 种；不要用 $token。
5. padding、gap 用 8、12、16、24 这类偶数；radius 用 0、8、12 或 999。
6. 不要写未列出的属性；未知属性是错误。

交互（可选但推荐）：
- 按钮点击跳页：{"on":{"click":[{"type":"link","page":"detail"}]}}
- 改文字：{"type":"set-text","target":{"ref":"page-id/node-id"},"text":"已提交"}
- 引用格式 {"ref":"<id>"} 从当前作用域向外找，跨页用 "页面id/节点id"。

设计取向：
- 页面 2–4 个，覆盖需求里的主流程（列表 → 详情/编辑 → 结果）。
- 页面留白充分，层级用标题（20/24）、正文（14/16）、辅助（12）三档字号区分。
- 中文文案，内容贴合需求，不要 lorem ipsum。
- 有状态流转的地方用按钮跳转到独立结果页，有列表的地方用 box 分组堆叠行。`;

export interface ProtoBrief {
  readonly title: string;
  readonly requirement: string;
  readonly target: ProtoTarget;
  readonly theme: ProtoTheme;
}

export function buildProtoPrompt(brief: ProtoBrief): string {
  const mobile = brief.target === "mobile";
  const canvas = mobile ? "402×874" : "1280×800";
  return [
    `项目名：${brief.title}`,
    `端：${mobile ? "手机（mobile）" : "Web 后台（web）"}，页面 canvas 用 ${canvas}`,
    `theme 字段填 "${brief.theme}"，domain 字段填 "${mobile ? "mobile" : "web"}"，target 固定 9`,
    "",
    "需求：",
    brief.requirement,
    "",
    "按需求产出 2–4 个页面的 RP/1 JSON 源码。",
  ].join("\n");
}
