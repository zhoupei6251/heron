import type { INestApplication } from "@nestjs/common";
import cookieParser from "cookie-parser";

/**
 * 进程外的这一层（URL 前缀 + cookie 解析 + 安全响应头）单独成函数，是为了让它**可测**：
 * 会话传输的正确性不该只靠 curl 那一次人肉验证，而 main.ts 一 import 就会起服务。
 * 于是主进程和 e2e 测试调用的是同一个函数，测的是真接线，不是它的抄本。
 * 也正因为两处共用，`/api` 前缀写在这里：测试里漏掉它，就会得到一个「所有路由都 404」的
 * 假失败，或更糟——一个只验到 404 响应头的安全头测试。
 */

/**
 * 安全头走 Nest 12 自带的 `useSecurityHeaders()`：它与 helmet 8 同一套默认值，
 * 还顺手去掉 `X-Powered-By`。所以本案不装 helmet——框架原生就有，多一个依赖多一分版本漂移。
 *
 * CSP 先 **report-only**。理由不是「还没配好」，是本案的版式链路真的会被它打死：
 * SFC → 构建 → headless 截图 → ffmpeg 合成，截图那一步由浏览器按同一份产物执行，
 * 一条 `script-src 'self'` 配错的症状是渲染出来的画面全黑，没人会往安全头上想。
 * report-only 只把违例打进 console，不拦任何东西——T9 在浏览器里跑一遍就能拿到违例清单，
 * 照着清单收紧后再摘掉 reportOnly。摘掉它之前，别把 CSP 当成已生效的防线。
 *
 * X-Frame-Options 用 deny 而不是默认的 sameorigin：本案没有任何「被人嵌一层」的正常用途，
 * 而被嵌入的页面带着会话 cookie，是最省事的 CSRF 入口。
 * Referrer-Policy 用 same-origin：深链里有任务 id，不许泄漏给第三方，但站内跳转要留得住。
 */
export function applyTransport(app: INestApplication): void {
  app.setGlobalPrefix("api");
  // 先挂头再挂 cookie 解析：Nest 的这组头是一个 pre-request hook，
  // 在它之后才注册的 app.use()（静态资源、SPA 回退）才会带上它，否则静态文件响应没有安全头。
  app.useSecurityHeaders({
    contentSecurityPolicy: { reportOnly: true },
    xFrameOptions: { action: "deny" },
    referrerPolicy: { policy: "same-origin" },
  });
  // 必须在守卫之前：JwtStrategy 的抽取器读的是 req.cookies，而那是这个中间件填的
  app.use(cookieParser());
}
