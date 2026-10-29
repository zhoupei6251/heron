import "reflect-metadata";

import { randomBytes, createHash } from "node:crypto";
import { mkdirSync, writeFileSync } from "node:fs";

import { Test } from "@nestjs/testing";

import { protoArtifacts } from "@heron/db";
import {
  createCandidate,
  createPlatformOperator,
  createTenant,
  recordUsage,
  protoProjects,
  protoRevisions,
  tasks,
  createUser,
  migrate,
  openDb,
  withTenant,
} from "@heron/db/pg";
import { MIN_PASSWORD_LENGTH, USAGE_KIND_UNITS } from "@heron/contracts";
import { hashPassword } from "@heron/domain";

import { AppModule } from "../app.module";
import { API_PORT } from "../config/runtime";
import { PG_DB } from "../db/pg";
import { applyTransport } from "../transport";
import { snowflakeId } from "@heron/db";

// 数据库重启即清空；演示产物落 HERON_HOME，固定 ID 防止重启后重复堆积。
const DEMO_PROTO_REVIEW_ID = "201";
const DEMO_PROTO_FAILED_ID = "202";
/** 一张线框占位图（真 PNG）：预览链接与图片标签都要有能渲染的字节。 */
const DEMO_PREVIEW_PNG =
  "iVBORw0KGgoAAAANSUhEUgAAAPAAAACWCAIAAABvmpKCAAACcElEQVR4nO3aoW0dQBRFwV+daZp2DYYBkcIMDMyTBgL98nWP5+oUsFoNfI8fL7+lTI+nv0D6woBWKqCVCmilAlqpgFYqoJUKaKUCWqmAViqglQpopQJaqYBWKqCVCmilAlqpHu/vnxO9vr5N98f+y4AGOjWggU4NaKBTAxro1IAGOjWggU4NaKBTAxro1IAGOjWggU4NaKBTAxro1IAGOjWggU4NaKBTmwH97I+yjQFtqQFtqQFtqQFtqQFtqQFtqQFtqQFtqQFtqZVB//z1oZWABjoV0ECnAhroVEADnQpooFMBDXQqoIFOBTTQqYAGOhXQQKcCGuhUQAOdCmigUwENdCqggU4FNNCpgAY6FdBApwIa6FRAA50KaKBTAQ10KqCBTgU00KmABjoV0ECnAhroVEADnQpooFMBDXQqoIFOBTTQqYAGOhXQQKcCGuhUQAOdCmigUwENdCqggU4FNNCpgAY6FdBApwIa6FRAA50KaKBTAQ10KqCBTgU00KmABjoV0ECnAhroVEADnQpooFMBDXQqoIFOBTTQqYAGOhXQQKcC2uwfA9pSA9pSA9pSA9pSA9pSA9pSA9pSA9pSA9pSA9pSK4N++n3C1hVEY0DPd6BieEDPd6BieEDPd6BieEDPd6BieEDPd6BieEDPd6BieEDPd6BieEDPd6BieEDPd6BieEDPd6BieEDPd6BieEDPd6BieEDPd6BieEDPd6BieEDPd6BieEDPd6BieEDPd6BieEDPd6BieEDPd6BieGXQ9g0HtKUGtKUGtKUGtKUGtKUGtKUGtKUGtKUGtKUGtKUGtKUGtKUGtKUGtKX2F4LdKN4pyxcGAAAAAElFTkSuQmCC";

async function main(): Promise<void> {
  const password = process.env.HERON_DEMO_PASSWORD ?? "";
  if (password.length < MIN_PASSWORD_LENGTH || password.length > 128) {
    throw new Error(
      `HERON_DEMO_PASSWORD 必须给且为 ${String(MIN_PASSWORD_LENGTH)}–128 位：演示账号的口令不写默认值。`,
    );
  }
  process.env.HERON_JWT_SECRET ??= randomBytes(32).toString("hex");

  const db = openDb("memory");
  await migrate(db);

  const tenantId = await createTenant(db, { name: "演示工作空间", slug: "default" });
  // 第二家只为右上角那个切换器而有：它没有成员，平台运营切进去看到的是真空间
  await createTenant(db, { name: "第二工作空间", slug: "second" });
  const accounts = [
    ["owner@example.com", "owner", "演示所有者"],
    ["reviewer@example.com", "reviewer", "演示审核员"],
    ["viewer@example.com", "viewer", "演示只读"],
  ] as const;
  const userId = new Map<string, string>();
  for (const [email, role, displayName] of accounts) {
    userId.set(
      role,
      await withTenant(db, tenantId, (t) =>
        createUser(t, { email, displayName, passwordHash: hashPassword(password), role }),
      ),
    );
  }

  await createPlatformOperator(db, {
    username: "platform",
    displayName: "演示平台管理员",
    passwordHash: hashPassword(password),
  });

  // Deliberately missing render files: the review UI must show its unavailable state.
  // These are labelled fixtures, not evidence of successful production or publishing.
  for (const [stage, title] of [
    ["candidate", "【演示数据】候选新闻样本"],
    ["review", "【演示数据】待审核样本（尚无渲染产物）"],
    ["failed", "【演示数据】失败诊断样本"],
  ] as const) {
    await withTenant(db, tenantId, async (t) => {
      const candidateId = await createCandidate(t, {
        title,
        summary: "仅供界面验收，不是真实新闻或发布记录",
        heat: 0,
      });
      const at = new Date().toISOString();
      await t.db.insert(tasks).values({
        id: snowflakeId(),
        tenantId,
        capability: "p0-fake",
        candidateId,
        stage,
        errorReason:
          stage === "failed" ? "演示诊断：未运行渲染器，未生成成片；没有调用外部发布平台" : null,
        createTime: at,
        updateTime: at,
      });
    });
  }

  // 用量页要有数才看得出单位分组与条形图；这几行是演示记账，不是计费账单。
  for (const [kind, quantity] of [
    ["llm_tokens_in", "128400"],
    ["llm_tokens_out", "41200"],
    ["render_seconds", "1830"],
    ["publish", "3"],
  ] as const) {
    await withTenant(db, tenantId, (t) =>
      recordUsage(t, { kind, quantity, unit: USAGE_KIND_UNITS[kind], detail: { demo: true } }),
    );
  }

  // 原型工坊的演示行：状态、版本、四道闸与产物凭证都要在页面上看得见，所以产物文件也真落盘——
  // 下载接口逐次校验盘上指纹，只插库行的假数据验不出预览、下载与确认这条链。
  const demoArt = protoArtifacts(DEMO_PROTO_REVIEW_ID, 2);
  const demoPreview = Buffer.from(DEMO_PREVIEW_PNG, "base64");
  const demoRp = Buffer.from("【演示数据】不是 Axure 工程，只用来验证下载与指纹校验。\n");
  const demoSource = `${JSON.stringify({ pages: [{ name: "工单列表", components: [] }] }, null, 2)}\n`;
  const demoAudit = {
    gates: { color_ok: true, font_ok: true, semantic_naming_ok: true, group_coverage_ok: true },
    coverage: {
      font: { passed: 42, applicable: 42 },
      semanticNaming: { passed: 31, applicable: 39 },
      groupCoverage: { passed: 12, applicable: 12 },
    },
  };
  mkdirSync(demoArt.engineDir, { recursive: true });
  writeFileSync(demoArt.source, demoSource);
  writeFileSync(demoArt.audit, `${JSON.stringify(demoAudit, null, 2)}\n`);
  writeFileSync(demoArt.preview, demoPreview);
  writeFileSync(demoArt.rp, demoRp);
  const previousArt = protoArtifacts(DEMO_PROTO_REVIEW_ID, 1);
  mkdirSync(previousArt.dir, { recursive: true });
  writeFileSync(previousArt.source, demoSource);

  const sha = (data: Buffer): string => createHash("sha256").update(data).digest("hex");
  const engineDown =
    "原型引擎未就绪：未配置 ZEE_RP_HOME：原型生成需要 zee-rp 引擎；未配置 AXURE_BLANK_TEMPLATE_RP9：装配 .rp 需要一份 Axure RP9 空白工程模板";
  const demoAt = new Date().toISOString();
  await withTenant(db, tenantId, async (t) => {
    await t.db.insert(protoProjects).values([
      {
        id: DEMO_PROTO_REVIEW_ID,
        tenantId,
        title: "【演示数据】客服工单后台",
        requirement:
          "三页：工单列表（筛选状态与优先级、点行进详情）、工单详情（时间线加处理输入框）、新建工单表单。" +
          "配色收敛在一套品牌蓝，正文用系统中文字体。仅供界面验收，不是真原型。",
        target: "web",
        theme: "enterprise-light",
        stage: "review",
        errorReason: null,
        attempts: 2,
        createBy: userId.get("owner") ?? null,
        createTime: demoAt,
        updateTime: demoAt,
      },
      {
        id: DEMO_PROTO_FAILED_ID,
        tenantId,
        title: "【演示数据】移动端审批中心（引擎未就绪）",
        requirement: "移动端：待我审批列表、单据详情、同意/驳回两个动作。仅供界面验收。",
        target: "mobile",
        theme: "mobile-lowfi",
        stage: "failed",
        errorReason: engineDown,
        attempts: 1,
        createBy: userId.get("owner") ?? null,
        createTime: demoAt,
        updateTime: demoAt,
      },
    ]);
    await t.db.insert(protoRevisions).values([
      {
        // 旧的一版：视觉闸没过，所以没有 .rp 与预览——详情页要能同时呈现「留着的失败版」
        id: snowflakeId(),
        projectId: DEMO_PROTO_REVIEW_ID,
        version: 1,
        rp1Source: demoSource,
        model: "演示模型",
        tokensIn: 1200,
        tokensOut: 480,
        failureKind: "visual",
        failureReason: "视觉闸：语义命名未过（semantic_naming_ok=fail（31/39））",
        createTime: demoAt,
      },
      {
        id: snowflakeId(),
        projectId: DEMO_PROTO_REVIEW_ID,
        version: 2,
        rp1Source: demoSource,
        engineVersion: "演示引擎 0.0.0",
        audit: demoAudit,
        model: "演示模型",
        tokensIn: 1500,
        tokensOut: 620,
        rpSha256: sha(demoRp),
        rpBytes: demoRp.length,
        previewSha256: sha(demoPreview),
        createTime: demoAt,
      },
      {
        id: snowflakeId(),
        projectId: DEMO_PROTO_FAILED_ID,
        version: 1,
        failureKind: "engine",
        failureReason: engineDown,
        createTime: demoAt,
      },
    ]);
  });

  const moduleRef = await Test.createTestingModule({ imports: [AppModule] })
    .overrideProvider(PG_DB)
    .useValue(db)
    .compile();
  const app = moduleRef.createNestApplication({ logger: ["warn", "error"] });
  applyTransport(app);
  await app.listen(API_PORT, "127.0.0.1");

  console.log(`heron 开发台（内存 pglite）：http://127.0.0.1:${String(API_PORT)}`);
  console.log(`工作空间：default（演示工作空间）`);
  for (const [email, role] of accounts) console.log(`  ${role.padEnd(8)} ${email}`);
  console.log("统一登录：邮箱直接进自己的工作空间；用户名 platform 进平台台");
  console.log("  平台运营登录后用右上角的切换器只读查看任一工作空间（演示口令同上）");
  console.log(
    "数据只在内存里：重启即清空；演示口令来自 HERON_DEMO_PASSWORD；不启动 worker 或外部发布。",
  );

  const stop = (): void => {
    void app.close().then(() => process.exit(0));
  };
  process.on("SIGINT", stop);
  process.on("SIGTERM", stop);
}

main().catch((error: unknown) => {
  console.error("开发台启动失败：", error);
  process.exit(1);
});
