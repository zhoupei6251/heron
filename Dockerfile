# syntax=docker/dockerfile:1

# 一个镜像同时喂 api 与 worker：两者跑的是同一个 workspace，只是入口不同。
# 拆成两个镜像就得把 packages/* 发成 npm 包，那不是现在该付的代价。
#
# 只有一层，没有 prod 阶段：tsx 是 devDependency，而进程就是靠它跑 TS 的；
# prune 掉 devDeps 会得到一个起不来的镜像。等有编译产物了再分阶段。
FROM node:24-alpine

ENV NODE_ENV=production \
    CI=1
WORKDIR /app

# 不用 corepack：它把 pnpm 缓存在 $HOME（构建期 root / 运行期 node 两个 home 不共享），
# 结果是每个容器第一次启动都重新下载 pnpm（实测 worker 卡在下载 registry 上）。
# 全局装一份，所有用户共用、启动时不碰网络。
RUN npm install -g pnpm@12.14.0

# 清单先行：pnpm-lock 没变时这一层命中缓存，改业务代码不重装依赖。
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml .npmrc tsconfig.base.json ./
COPY apps/api/package.json apps/api/
COPY apps/worker/package.json apps/worker/
COPY apps/web/package.json apps/web/
COPY packages/contracts/package.json packages/contracts/
COPY packages/db/package.json packages/db/
COPY packages/domain/package.json packages/domain/
COPY capabilities/news/package.json capabilities/news/

RUN pnpm install --frozen-lockfile

COPY . .

# 前端产物是 api 静态托管的对象（WEB_DIST = apps/web/dist），必须在镜像里。
RUN pnpm web:build

# 数据面挂点。命名卷首次挂载时继承这里的属主，所以 chown 要在 USER node 之前做完。
# 业务数据在 Postgres 里，这里只剩成片与诊断产物（api 与 worker 共享同一个卷）。
RUN mkdir -p /data && chown -R node:node /data
USER node

# 渲染（ffmpeg）与发布（Playwright 浏览器）不在 alpine 里，P4 接真渲染时要么装进镜像，
# 要么把 base 换成 mcr.microsoft.com/playwright —— 那是那一期该做的决定，不是现在注释掉就能糊过去的。
EXPOSE 3000
CMD ["pnpm", "--filter", "@heron/api", "start"]
