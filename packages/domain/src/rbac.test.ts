import { DEFAULT_CONSOLE_MENU_CONFIG, ROLES } from "@heron/contracts";
import { describe, expect, it } from "vitest";

import {
  ACTIONS,
  allowedMenuRoles,
  can,
  consoleAccess,
  isAction,
  menuConfigProblem,
  requiredRoleFor,
} from "./rbac";

describe("RBAC 纯函数", () => {
  it("returns only authorized workspace navigation and action permissions", () => {
    const viewer = consoleAccess("viewer");
    expect(viewer.permissions).toEqual(["usage.read"]);
    expect(viewer.menus.map((menu) => menu.id)).toEqual([
      "overview",
      "candidates",
      "production",
      "review",
      "publish",
      "library",
      "proto",
      "roles",
      "usage",
      "sessions",
      "profile",
    ]);
    expect(consoleAccess("admin").menus.map((menu) => menu.id)).toContain("settings");
    expect(consoleAccess("admin").menus.map((menu) => menu.id)).not.toContain("members");
    expect(consoleAccess("owner").menus.map((menu) => menu.id)).toContain("members");
    expect(consoleAccess("owner").menus.some((menu) => menu.path.startsWith("/platform"))).toBe(
      false,
    );
  });

  it("never grants tenant actions or content routes to the platform role", () => {
    const access = consoleAccess("platform");
    expect(access.roles).toEqual(["platform"]);
    expect(access.menus.map((menu) => menu.id)).toEqual([
      "platform-overview",
      "tenants",
      "platform-settings",
      "platform-menus",
      "platform-usage",
      "platform-audit",
      "health",
    ]);
    expect(access.groups.map((group) => group.key)).toEqual([
      "platform-operations",
      "platform-config",
      "platform-monitor",
    ]);
    expect(access.permissions).not.toContain("review.confirm");
    expect(access.permissions).not.toContain("user.manage");
  });

  it("租户菜单分组跟随默认配置：两条业务线 + 系统管理 + 系统监控", () => {
    const access = consoleAccess("owner");
    expect(access.groups.map((group) => group.key)).toEqual(["news", "proto", "system", "monitor"]);
    expect(new Set(access.menus.map((menu) => menu.group))).toEqual(
      new Set(access.groups.map((group) => group.key)),
    );
    // 个人页不在导航里出现，但路由仍然存在：头像菜单还要能点进去
    expect(access.menus.find((menu) => menu.id === "profile")?.hidden).toBe(true);
  });

  it("菜单配置能改名、排序、隐藏与收窄角色，但路径永远来自白名单", () => {
    const config = structuredClone(DEFAULT_CONSOLE_MENU_CONFIG);
    config.groups[0].label = "新闻线";
    config.groups[0].items[0].hidden = true;
    config.groups[0].items[1].roles = ["reviewer"];
    config.groups[0].items.push(config.groups[0].items.shift()!);
    const access = consoleAccess("viewer", config);
    expect(access.groups[0]).toMatchObject({ key: "news", label: "新闻线" });
    // 收窄到 reviewer 后 viewer 看不到候选管理，组内其余页面照旧
    expect(access.menus.map((menu) => menu.id)).not.toContain("candidates");
    expect(access.menus.find((menu) => menu.id === "production")?.path).toBe("/production");
    // 隐藏只影响导航，不影响路由与路径解析
    expect(access.menus.find((menu) => menu.id === "overview")).toMatchObject({
      hidden: true,
      path: "/overview",
    });
    // 配置里的顺序就是菜单顺序，隐藏的页面仍然排在原处
    expect(access.menus.filter((menu) => menu.group === "news").map((menu) => menu.id)).toEqual([
      "production",
      "review",
      "publish",
      "library",
      "overview",
    ]);
  });

  it("页面动作门槛不进配置：可见角色只能收窄，不能越过接口判据", () => {
    expect(allowedMenuRoles("members")).toEqual(["owner"]);
    expect(allowedMenuRoles("feeds")).toEqual(["owner", "admin"]);
    expect(allowedMenuRoles("review")).toEqual([...ROLES]);
  });

  it("保存前的语义闸：越权放人、或让某档角色一条菜单都看不到，都要拒", () => {
    expect(menuConfigProblem(DEFAULT_CONSOLE_MENU_CONFIG)).toBeNull();

    const tooWide = structuredClone(DEFAULT_CONSOLE_MENU_CONFIG);
    tooWide.groups[2].items[0].roles = ["viewer"];
    expect(menuConfigProblem(tooWide)).toContain("可见角色超出");

    const allHidden = structuredClone(DEFAULT_CONSOLE_MENU_CONFIG);
    for (const group of allHidden.groups) {
      for (const item of group.items) item.hidden = true;
    }
    expect(menuConfigProblem(allHidden)).toContain("至少");
  });

  /**
   * RANK 是从 ROLES 的**数组顺序**推出来的（从大到小），所以这条断言不是重复名单，
   * 是钉住推导依据：谁改顺序就得在这里红一次，知道自己动了什么。
   */
  it("ROLES 的顺序就是权限高低：owner 最高，viewer 最低", () => {
    expect(ROLES).toEqual(["owner", "admin", "reviewer", "operator", "viewer"]);
  });

  it("人点确认不能是只读账号，也不能是操作账号", () => {
    expect(can("viewer", "review.confirm")).toBe(false);
    expect(can("operator", "review.confirm")).toBe(false);
    expect(can("reviewer", "review.confirm")).toBe(true);
  });

  it("设置与 AIGC 姿态是 admin 及以上，成员管理只有 owner", () => {
    expect(can("admin", "settings.write")).toBe(true);
    expect(can("reviewer", "settings.write")).toBe(false);
    // AIGC 姿态一改就能把角标和声明绕掉，它是最高危的一次写
    expect(can("reviewer", "aigc.set")).toBe(false);
    expect(can("admin", "aigc.set")).toBe(true);
    expect(can("admin", "user.manage")).toBe(false);
    expect(can("owner", "user.manage")).toBe(true);
  });

  it("认领要 operator 及以上：认领是人的动作，机器不算人", () => {
    expect(can("viewer", "task.claim")).toBe(false);
    expect(can("operator", "task.claim")).toBe(true);
  });

  it("用量谁都能看自己的：界面置灰要的那句人话由 requiredRoleFor 给", () => {
    expect(requiredRoleFor("review.confirm")).toBe("reviewer");
    expect(requiredRoleFor("usage.read")).toBe("viewer");
    expect(requiredRoleFor("aigc.set")).toBe("admin");
    expect(can("viewer", "usage.read")).toBe(true);
  });

  it("每个动作的放行集合都是 ROLES 的前缀：高角色不会比低角色权限少", () => {
    for (const action of ACTIONS) {
      const min = requiredRoleFor(action);
      expect(ROLES).toContain(min);
      expect(ROLES.filter((role) => can(role, action))).toEqual(
        ROLES.slice(0, ROLES.indexOf(min) + 1),
      );
    }
  });

  it("七个动作是闭合名单，一个不多一个不少", () => {
    expect(ACTIONS).toEqual([
      "task.claim",
      "review.confirm",
      "settings.write",
      "aigc.set",
      "user.manage",
      "usage.read",
      "session.manage",
    ]);
  });

  it("名单外的动作名判不出来：守卫靠这个显式拒绝，而不是靠 RANK[undefined] 恰好是 NaN", () => {
    expect(isAction("review.confirm")).toBe(true);
    expect(isAction("task.rename")).toBe(false);
    expect(isAction("")).toBe(false);
    // 大小写与空格不算：动作名是标识符，不是自由文本
    expect(isAction("Review.Confirm")).toBe(false);
    for (const action of ACTIONS) expect(isAction(action)).toBe(true);
  });
});
