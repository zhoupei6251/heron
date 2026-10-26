#!/usr/bin/env bash
# 无感发版的宿主侧：拉镜像仓库上 main 构建出来的 tag，镜像变了才重建容器。
# 装进 cron（README「自动构建与发版」），所以它必须自己扛住所有失败：
# 拉不到（凭证过期、tag 还没构出来、断网）就退出，正在跑的栈一个都不碰——
# 这一轮没发版而已，不是事故。宁可慢，不许把线上拉成半截。
set -euo pipefail

# compose 入口目录：那份用 include 引仓库里 deploy/docker-compose.yml 的宿主文件，不是仓库根。
cd "${HERON_DEPLOY_DIR:-/docker/heron}"

# compose 会自己读同目录的 .env，所以这里问的是「这台机器实际要用哪个镜像」而不是读环境变量。
# 还是默认的 heron:local 就说明它走本地 build——仓库里没有这个 tag，跳过，别让 cron 每五分钟积一条失败。
if docker compose config --images | grep -qx "heron:local"; then
  echo "api/worker 的镜像是本地构建的 heron:local（HERON_IMAGE 未设置），没有可拉的 tag，跳过这轮"
  exit 0
fi

# 只拉应用镜像：db/redis 的 tag 钉在版本上，跟着拉一遍只是白等。
docker compose pull api worker
# up -d 自己判断谁该重建：tag 指向的镜像 ID 变了才换容器，没变就什么都不做。
docker compose up -d
# 换下来的旧镜像成了 dangling，不留着占盘（带 tag 的镜像不会被动到）。
docker image prune -f
