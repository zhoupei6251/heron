import { describe, expect, it } from "vitest";

import {
  assertTransition,
  dedupeVerdict,
  formatErrorReason,
  gateForTransition,
  normKey,
  publishBlockers,
  publishGateOpen,
} from "./index";

describe("norm_key", () => {
  it("空白不参与去重键", () => {
    expect(normKey("  台风  巴威 登陆  ")).toBe(normKey("台风巴威登陆"));
  });

  it("标点不参与去重键", () => {
    expect(normKey("台风“巴威”登陆！")).toBe(normKey("台风巴威登陆"));
  });

  it('与 ledger.py 的 re.sub(r"[\\W_]+", "", title) 逐字符一致', () => {
    // CJK 在 Python 里算 \\w，下划线和 emoji 都不算：这三条是 oracle 的靶子，改动 normKey 必须先过它们
    expect(normKey("台风巴威登陆")).toBe("台风巴威登陆");
    expect(normKey("A_B_1")).toBe("AB1");
    expect(normKey("巴威🚨登陆")).toBe("巴威登陆");
  });

  it("不做小写折叠（折叠要先改 ledger.py 再重生成 fixtures）", () => {
    expect(normKey("Typhoon Bavi")).toBe("TyphoonBavi");
    expect(normKey("Typhoon Bavi")).not.toBe(normKey("typhoon bavi"));
  });

  it("不同标题不折叠成同一个键", () => {
    expect(normKey("台风巴威登陆")).not.toBe(normKey("台风巴威名称退役"));
  });
});

describe("去重闸", () => {
  it("空标题被拒且报出原因", () => {
    const verdict = dedupeVerdict({ normKey: normKey("！！！"), alreadyPublished: false });
    expect(verdict.passed).toBe(false);
    expect(verdict.rejections[0]?.ruleId).toBe("dedupe.norm_key.nonempty");
  });

  it("已发布过的 norm_key 被拒", () => {
    const verdict = dedupeVerdict({ normKey: "台风巴威登陆", alreadyPublished: true });
    expect(verdict.passed).toBe(false);
    expect(formatErrorReason(verdict)).toContain("dedupe.already_published");
  });
});

describe("状态机", () => {
  it("candidate 不能直接跳到 rendering", () => {
    expect(() => assertTransition("candidate", "rendering")).toThrow(/非法状态转移/);
  });

  it("闸挂在转移上而不是步骤上", () => {
    expect(gateForTransition("factcheck", "rendering")).toBe("contract");
    expect(gateForTransition("rendering", "review")).toBe("visual");
    expect(gateForTransition("review", "approved")).toBe("human");
    expect(gateForTransition("approved", "publishing")).toBe("compliance");
    expect(gateForTransition("claimed", "scripting")).toBeNull();
  });

  it("核查不过可以退回 scripting", () => {
    expect(() => assertTransition("factcheck", "scripting")).not.toThrow();
  });
});

describe("门禁位", () => {
  it("四道闸未搬齐时发布门禁不开", () => {
    expect(publishGateOpen()).toBe(false);
    expect(publishBlockers()).toContainEqual(expect.stringContaining("contract"));
  });
});
