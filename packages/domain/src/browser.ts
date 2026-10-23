/**
 * 浏览器能看的那一半：全是纯判定，不碰 Node 内建模块。
 * password.ts 走 node:crypto，只给服务端用；它留在 index.ts 里，从这个入口漏出去就会
 * 让 Vite 把 node:crypto 外置成空壳，页面在导航那一刻才炸——构建期看不出来。
 */
export * from "./transitions";
export * from "./error-reason";
export * from "./rbac";
export * from "./gates/dedupe";
export * from "./gates/refusal";
export * from "./gates/status";
export * from "./gates/compliance";
