import { createRouter, createWebHistory } from "vue-router";

import { installAuthGuards } from "./guards";

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", redirect: "/overview" },
    { path: "/overview", component: () => import("../pages/OverviewView.vue") },
    { path: "/login", component: () => import("../pages/LoginView.vue"), meta: { public: true } },
    {
      path: "/platform/login",
      component: () => import("../pages/LoginView.vue"),
      meta: { public: true, platform: true },
    },
    { path: "/candidates", component: () => import("../pages/CandidateFeed.vue") },
    { path: "/production", component: () => import("../pages/Production.vue") },
    { path: "/review", component: () => import("../pages/Review.vue") },
    { path: "/publish", component: () => import("../pages/Publish.vue") },
    { path: "/library", component: () => import("../pages/LibraryView.vue") },
    { path: "/members", component: () => import("../pages/MembersView.vue") },
    { path: "/roles", component: () => import("../pages/RolesView.vue") },
    { path: "/feeds", component: () => import("../pages/FeedsView.vue") },
    { path: "/settings", component: () => import("../pages/Settings.vue") },
    { path: "/usage", component: () => import("../pages/UsageView.vue") },
    { path: "/audit", component: () => import("../pages/AuditView.vue") },
    { path: "/profile", component: () => import("../pages/ProfileView.vue") },
    {
      path: "/platform",
      component: () => import("../pages/PlatformOverviewView.vue"),
      meta: { platform: true },
    },
    {
      path: "/platform/tenants",
      component: () => import("../pages/PlatformTenantsView.vue"),
      meta: { platform: true },
    },
    {
      path: "/platform/usage",
      component: () => import("../pages/UsageView.vue"),
      meta: { platform: true },
    },
    {
      path: "/platform/audit",
      component: () => import("../pages/AuditView.vue"),
      meta: { platform: true },
    },
    {
      path: "/platform/health",
      component: () => import("../pages/PlatformHealthView.vue"),
      meta: { platform: true },
    },
    {
      path: "/platform/:pathMatch(.*)*",
      component: () => import("../pages/NotFoundView.vue"),
      meta: { platform: true },
    },
    { path: "/:pathMatch(.*)*", component: () => import("../pages/NotFoundView.vue") },
  ],
});

installAuthGuards(router);
