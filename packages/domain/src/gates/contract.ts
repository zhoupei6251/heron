import type { GateVerdict } from "@heron/contracts";
import { NewsScriptSchema } from "@heron/contracts";

/** 与 oracle 的 check_scene_contract.py 第 7 条同口径：标点与空白不参与比对。 */
function squash(text: string): string {
  return text.replace(/[，,、。：:；;｜|\s]/g, "");
}

/**
 * 契约闸：写稿产出能不能进渲染。
 *
 * 只判「不判就会静默丢内容」的几条：没稿、形状不对、title 与屏句同句。
 * 落点/条目容量/变量可填这几条依赖版式（D3 未定），此时另立一套只会得到假绿；
 * 等版式定稿再接 oracle 的同一套判据。
 */
export function scriptVerdict(raw: unknown): GateVerdict {
  if (raw === null || raw === undefined) {
    return {
      gate: "contract",
      passed: false,
      rejections: [
        {
          ruleId: "contract.script_missing",
          snippet: "",
          why: "任务没有写稿产出，不许进渲染（写稿阶段失败或没跑过）",
        },
      ],
    };
  }

  const parsed = NewsScriptSchema.safeParse(raw);
  if (!parsed.success) {
    return {
      gate: "contract",
      passed: false,
      rejections: parsed.error.issues.map((issue) => ({
        ruleId: "contract.script_shape",
        snippet: issue.path.join(".") || "(root)",
        why: `字段形状不对：${issue.message}`,
      })),
    };
  }

  const script = parsed.data;
  const rejections = script.scenes.flatMap((scene, index) =>
    scene.onscreen && squash(script.title) === squash(scene.onscreen)
      ? [
          {
            ruleId: "contract.title_onscreen_same",
            snippet: `scenes.${index}.onscreen=${scene.onscreen.slice(0, 20)}`,
            why: "title 与屏句去掉标点后是同一句，版式会把同一句在领句位和屏句位各打一遍",
          },
        ]
      : [],
  );

  return { gate: "contract", passed: rejections.length === 0, rejections };
}
