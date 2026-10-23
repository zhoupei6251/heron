import { createRouter, createWebHistory } from "vue-router";

import { installAuthGuards } from "./guards";

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", redirect: "/candidates" },
    { path: "/login", component: () => import("../pages/LoginView.vue"), meta: { public: true } },
    { path: "/candidates", component: () => import("../pages/CandidateFeed.vue") },
    { path: "/production", component: () => import("../pages/Production.vue") },
    { path: "/review", component: () => import("../pages/Review.vue") },
    { path: "/publish", component: () => import("../pages/Publish.vue") },
    { path: "/settings", component: () => import("../pages/Settings.vue") },
  ],
});

installAuthGuards(router);
