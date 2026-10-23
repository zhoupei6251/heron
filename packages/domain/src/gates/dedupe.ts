import type { GateVerdict } from "@heron/contracts";

export function normKey(title: string): string {
  // 判据 = harness-factory ledger.py 的 re.sub(r"[\W_]+", "", title)，逐字符等价：
  // Python 的 \w 对 str 就是「字母或数字」（含 CJK），下划线被显式加进删除类，且**不做小写折叠**。
  // 想加 toLowerCase 必须先改 ledger.py 再重生成 fixtures——两边一起动，否则 oracle diff 直接红。
  return title.replace(/[^\p{L}\p{N}]/gu, "");
}

export interface DedupeInput {
  readonly normKey: string;
  readonly alreadyPublished: boolean;
}

export function dedupeVerdict(input: DedupeInput): GateVerdict {
  if (input.normKey.length === 0) {
    return {
      gate: "dedupe",
      passed: false,
      rejections: [
        {
          ruleId: "dedupe.norm_key.nonempty",
          snippet: "",
          why: "标题归一化后为空，无法建立去重键",
        },
      ],
    };
  }
  if (input.alreadyPublished) {
    return {
      gate: "dedupe",
      passed: false,
      rejections: [
        {
          ruleId: "dedupe.already_published",
          snippet: input.normKey,
          why: "同一 norm_key 已有 published 记录，不许再生成生产任务",
        },
      ],
    };
  }
  return { gate: "dedupe", passed: true, rejections: [] };
}
