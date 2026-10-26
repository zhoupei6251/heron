import { createRouter, createWebHistory } from "vue-router";

import { installAuthGuards } from "./guards";

/**
 * 静态路由只剩「入口」和「兜底」；业务页面按登录后下发的菜单动态注册。
 * 这样「菜单里没有的页面」不是被藏起来，而是根本不存在，直接访问会落到 404。
 */
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: "/login",
      name: "login",
      component: () => import("../pages/LoginView.vue"),
      meta: { public: true },
    },
    // 旧的平台登录地址保留一个转址，书签不会打到 404。
    { path: "/platform/login", redirect: "/login" },
    {
      path: "/not-found",
      name: "not-found",
      component: () => import("../pages/NotFoundView.vue"),
      meta: { public: true },
    },
    {
      path: "/:pathMatch(.*)*",
      name: "unmatched",
      component: () => import("../pages/NotFoundView.vue"),
      meta: { public: true },
    },
  ],
});

installAuthGuards(router);
