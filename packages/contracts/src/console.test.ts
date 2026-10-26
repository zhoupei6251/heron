import { describe, expect, it } from "vitest";
import * as c from "./index";

const member = {
  email: " Boss@Example.COM ",
  displayName: " Boss ",
  password: "x".repeat(12),
  role: "owner",
};
const tenant = {
  name: " Workspace ",
  slug: "acme-news",
  ownerEmail: member.email,
  ownerDisplayName: member.displayName,
  ownerPassword: member.password,
};

describe("console contracts", () => {
  it("normalizes a single login without guessing a tenant or accepting invalid workspace codes", () => {
    expect(c.LoginBodySchema.parse({ email: member.email, password: member.password })).toEqual({
      tenant: "",
      email: "boss@example.com",
      password: member.password,
    });
    expect(
      c.LoginBodySchema.parse({ tenant: " alpha ", email: member.email, password: member.password })
        .tenant,
    ).toBe("alpha");
    for (const value of ["a/b", "a".repeat(64), "-", null]) {
      expect(
        c.LoginBodySchema.safeParse({
          tenant: value,
          email: member.email,
          password: member.password,
        }).success,
      ).toBe(false);
    }
  });
  it("exports bounded pagination with normalized search", () => {
    expect(c.PageQuerySchema).toBeDefined();
    expect(c.PageQuerySchema.parse({})).toEqual({ page: 1, pageSize: 20, q: "" });
    expect(c.PageQuerySchema.parse({ page: "2", pageSize: "3", q: " news " })).toEqual({
      page: 2,
      pageSize: 3,
      q: "news",
    });
    for (const input of [{ page: 0 }, { page: 1.5 }, { pageSize: 101 }, { q: "x".repeat(201) }]) {
      expect(c.PageQuerySchema.safeParse(input).success).toBe(false);
    }
  });
  it("requires nonnegative integer overview counts and typed recent tasks", () => {
    const overview = {
      candidates: 0,
      tasks: 2,
      review: 1,
      published: 0,
      failed: 1,
      members: 3,
      recentTasks: [],
    };
    expect(c.WorkspaceOverviewSchema.parse(overview)).toEqual(overview);
    expect(c.WorkspaceOverviewSchema.safeParse({ ...overview, tasks: -1 }).success).toBe(false);
    expect(c.WorkspaceOverviewSchema.safeParse({ ...overview, members: 0.5 }).success).toBe(false);
    expect(c.WorkspaceOverviewSchema.safeParse({ ...overview, recentTasks: [{}] }).success).toBe(
      false,
    );
  });
  it("normalizes members and rejects empty patches and invalid bounds", () => {
    expect(c.CreateMemberBodySchema).toBeDefined();
    expect(c.CreateMemberBodySchema.parse(member)).toEqual({
      ...member,
      email: "boss@example.com",
      displayName: "Boss",
    });
    for (const patch of [
      { password: "x".repeat(11) },
      { password: "x".repeat(129) },
      { displayName: " " },
      { displayName: "x".repeat(81) },
      { email: "a".repeat(250) + "@a.com" },
      { role: "editor" },
    ]) {
      expect(c.CreateMemberBodySchema.safeParse({ ...member, ...patch }).success).toBe(false);
    }
    expect(c.UpdateMemberBodySchema.safeParse({}).success).toBe(false);
    expect(c.UpdateMemberBodySchema.safeParse({ displayName: undefined }).success).toBe(false);
    expect(c.UpdateMemberBodySchema.parse({ disabled: false })).toEqual({ disabled: false });
    expect(c.ProfileBodySchema.parse({ displayName: " New " })).toEqual({ displayName: "New" });
    expect(
      c.PasswordBodySchema.safeParse({ currentPassword: "", newPassword: member.password }).success,
    ).toBe(false);
    expect(
      c.PasswordBodySchema.parse({ currentPassword: "old", newPassword: member.password })
        .newPassword,
    ).toBe(member.password);
    expect(c.WorkspaceBodySchema.safeParse({ name: "x".repeat(101) }).success).toBe(false);
  });
  it("accepts only bounded HTTP feeds without embedded credentials", () => {
    expect(c.FeedSourceBodySchema).toBeDefined();
    const feed = { name: " News ", url: "https://example.com/feed", frequencySec: 60 };
    expect(c.FeedSourceBodySchema.parse(feed)).toEqual({ ...feed, name: "News", enabled: true });
    for (const patch of [
      { url: "ftp://example.com" },
      { url: "https://user:secret@example.com" },
      { url: "http://user@example.com" },
      { url: "https://example.com/" + "x".repeat(2048) },
      { frequencySec: 59 },
      { frequencySec: 86401 },
      { frequencySec: 60.5 },
    ]) {
      expect(c.FeedSourceBodySchema.safeParse({ ...feed, ...patch }).success).toBe(false);
    }
  });
  it("validates separate platform login and tenant provisioning", () => {
    expect(c.PlatformLoginBodySchema).toBeDefined();
    expect(c.PlatformLoginBodySchema.parse({ email: member.email, password: "old" }).email).toBe(
      "boss@example.com",
    );
    expect(
      c.PlatformLoginBodySchema.safeParse({ email: member.email, password: "x".repeat(129) })
        .success,
    ).toBe(false);
    expect(c.CreateTenantBodySchema.parse(tenant).name).toBe("Workspace");
    for (const slug of ["aa", "AcmE", "-acme", "acme-", "中文", "a".repeat(64)]) {
      expect(c.CreateTenantBodySchema.safeParse({ ...tenant, slug }).success).toBe(false);
    }
    expect(c.TenantStatusBodySchema.parse({ suspended: false })).toEqual({ suspended: false });
  });
});
