# WSL2 生产环境 + Cloudflare 隧道对外发布

日期：2026-11-17
状态：已实施

## 背景与目标

把 `heron_prod` 这套生产形态部署到 WSL2（Ubuntu-22.04），复用 Windows 上已在运行的 Cloudflare
命名隧道，让人能通过 `heronaigc.top` 访问。约束：不加 Cloudflare Access，靠应用自身登录鉴权。

## 决策记录

### 1. 隧道复用 Windows 连接器，不往 WSL 装第二个

Windows 上 `Cloudflared` 是以 LocalSystem 跑的服务，参数为
`tunnel run --token-file C:\ProgramData\cloudflared\token`，属于 dashboard 托管的命名隧道。

被否决的做法：在 WSL 里再装一个连接器接同一条隧道。同一隧道的多个连接器由 Cloudflare 随机分发，
Windows 那个连接器会分到请求，一旦它到不了 WSL 的端口就有一半请求 502。

依赖：WSL2 的 localhost 端口转发。已验证（Windows `127.0.0.1:3307` 能到 WSL 内的 MySQL）。

### 2. 后端以容器跑

WSL 内没有安装 JDK，复用仓库现成的 `ruoyi-admin/Dockerfile`。

### 3. 「走 heron_prod」= `SPRING_PROFILES_ACTIVE=prod`

`application-prod.yml` 本来就指向 `localhost:3307/heron_prod` 与 Redis `localhost:6380`，
与 WSL 内的 `heron-dev-mysql`(3307) / `heron-dev-redis`(6380) 对应，配置零改动。

pom 的默认 profile 是 `dev`，所以构建用 `-Pprod` 把 prod 烤进 jar；compose 里再显式钉一次
`SPRING_PROFILES_ACTIVE=prod`，防止误用 dev 镜像。

### 4. 后端固定 8080，不改 nginx upstream 的端口

nginx 现有 upstream 是 `127.0.0.1:8080` + `8081` 双实例。生产只跑一个实例，删掉 8081 那一行，
8080 保持不变。Windows 上的 dev 实例用 8085，两者不冲突。

### 5. Redis 用 db 1 与 dev 隔离

dev 与 prod 原本都写 `spring.data.redis.database: 0`，缓存 key（字典、参数等）背后却是
`heron` 和 `heron_prod` 两个不同的库，会互相命中。生产容器里通过
`SPRING_DATA_REDIS_DATABASE: 1` 覆盖，不改仓库配置文件。

### 6. 生产库从 dev 库复制，而非干净初始化

`heron_prod` 原本是空库（0 张表）。Flyway 配了 `baseline-on-migrate: true` + `baseline-version: 45`，
在空库上会把「45 已完成」直接记进历史，只跑 `V46`，业务表全缺。

用 `mysqldump heron | mysql heron_prod` 复制，连 `flyway_schema_history` 一起带过去
（基线 45 + V46 已应用），结构与数据完全对等。

被否决的做法：按 `ry_vue.sql` → `ry_workflow.sql` → `ry_ai.sql` → `heron/001~045` 依次导入做干净初始化。
更干净，但页面是空的，当前阶段不需要。

## 架构与数据流

```
公网用户
   │  https://heronaigc.top
   ▼
Cloudflare 边缘（DNS 托管 + 边缘证书，免费版）
   │  命名隧道
   ▼
cloudflared.exe（Windows 服务，已有，未改动）
   │  http://127.0.0.1:80   ← WSL2 localhost 端口转发
   ▼
nginx-web 容器（WSL2，network_mode=host，:80）
   ├─  /            → /usr/share/nginx/html ← heron-ui dist
   └─  /prod-api/   → http://127.0.0.1:8080/
                          ▼
                     heron-server1 容器（WSL2，host 网络，:8080）
                     SPRING_PROFILES_ACTIVE=prod
                          ├─ MySQL  localhost:3307/heron_prod（heron-dev-mysql）
                          └─ Redis  localhost:6380 db 1（heron-dev-redis）
```

## 改动清单

### Cloudflare 面板

Zero Trust → Networks → Tunnels → 现有隧道 → 添加路由 → **已发布的应用程序**：

| 字段 | 值 |
| --- | --- |
| 子域 / 域 | 根域 `heronaigc.top` |
| 路径 | 留空 |
| 服务类型 | HTTP |
| URL | `http://127.0.0.1:80` |

要点：

- URL 必须写 `http://`。nginx 在 80 上是纯 HTTP，写 `https://` 会 TLS 握手失败。
- 写 `127.0.0.1` 而非 `localhost`，避免先解析到 IPv6 `::1` 而 WSL 转发只绑了 IPv4。
- 不要在 DNS 页面手建同名记录，隧道会自建 CNAME。
- SSL/TLS 模式保持 **Full**（出口是 http，TLS 在 CF 边缘终止）。

### 数据库

```bash
docker exec heron-dev-mysql sh -c \
  'mysqldump -uroot -pheron!2026 --single-transaction --routines heron | mysql -uroot -pheron!2026 heron_prod'
```

### 前端

```bash
cd heron-ui && npm run build:prod
cp -r dist/. /docker/nginx/html/
```

`/docker/nginx/html` 归 root 所有，复制需要 root 身份。nginx.conf 开了 `gzip_static on`，
正好吃 `VITE_BUILD_COMPRESS=gzip` 产出的 `.gz`。

### nginx（`/docker/nginx/conf/nginx.conf`，两处）

- `server_name 172.27.108.195;` → `server_name heronaigc.top;`
- 删除 upstream 里的 `server 127.0.0.1:8081 ...` 一行（8080 保持不动）

### 生产 compose（`/docker/prod/docker-compose.yml`，源在仓库 `script/docker/prod/`）

`heron-server1` 三处：

- `image`: `heron-server:6.1.0` → `heron:prod`
- 加 `SPRING_PROFILES_ACTIVE: prod`
- 加 `SPRING_DATA_REDIS_DATABASE: 1`

## 部署步骤

```bash
# 1. 后端产物
mvn -Pprod -pl ruoyi-admin -am package -DskipTests
docker build -t heron:prod -f ruoyi-admin/Dockerfile ruoyi-admin

# 2. 前端产物
cd heron-ui && npm run build:prod
wsl -d Ubuntu-22.04 -u root -- bash -lc 'cp -r /mnt/d/zhoupei/heron-ui/dist/. /docker/nginx/html/'

# 3. 建库
docker exec heron-dev-mysql sh -c \
  'mysqldump -uroot -pheron!2026 --single-transaction --routines heron | mysql -uroot -pheron!2026 heron_prod'

# 4. 起容器
cd /docker/prod
HERON_PASSWORD='heron!2026' docker compose up -d heron-server1 nginx-web

# 5. 起前端静态服务
docker compose -f /docker/docker-compose.yml up -d nginx-web
```

只起 `heron-server1` 与 `nginx-web`。MySQL / Redis 由 `heron-dev-*` 提供；minio / monitor /
snailjob / snailai 在这个场景下空转，不起。

## 验证（由内向外四层）

```bash
# ① 后端自身
docker logs --tail 30 heron-server1
docker exec heron-server1 curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/auth/code

# ② nginx → 后端（WSL 内）
curl -I http://127.0.0.1/
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1/prod-api/auth/code

# ③ Windows → WSL
curl.exe -I http://localhost/

# ④ 公网
curl -I https://heronaigc.top
curl -s -o /dev/null -w '%{http_code}\n' https://heronaigc.top/prod-api/auth/code
```

`/auth/code` 是未鉴权端点，适合当探针。生产前端开了接口加密，明文 curl 拿到的 body 不可读，
只看状态码。

## 失败映射

| 现象 | 原因 |
| --- | --- |
| 公网 502 / 1033 | cloudflared 到不了 `127.0.0.1:80`：WSL 没起、nginx 没起、或 Windows 80 被占 |
| 公网 525 / 526 | SSL/TLS 模式不是 Full |
| 域名不解析 | 面板 hostname 未保存，或 DNS 页手建了冲突记录 |
| 首页 200、接口 502 | `heron-server1` 没起，或端口与 upstream 不一致 |
| 后端起不来 | `docker logs heron-server1` 找 SQLException（MySQL 3307）或 NOAUTH（Redis 6380） |
| 页面白屏 | `/docker/nginx/html` 里必须直接是 `index.html`，不能再套一层 `dist/` |

## 已知风险与未覆盖

1. **生产库与 dev 库在同一个 MySQL 实例上**（`heron-dev-mysql`，3307，同一 root 账号）。
   靠库名隔离，一次连错库或漏写库名的 SQL 就可能动到生产数据。这是当前拓扑最脆的一环。
2. **生产库口令 = 仓库里明文的开发口令** `heron!2026`。`application-prod.yml` 里也明文写着。
   数据库端口只在 WSL 内可达，短期可接受。
3. **WSL 不随 Windows 开机自启**。WSL 没起来时公网访问直接 502。两个容器配了
   `restart: unless-stopped`，WSL 起来后会自行恢复，但 WSL 本身需要手动开或加计划任务。
4. **`heron_prod` 的账号密码与 dev 完全一致**（库是复制的）。给人用之前必须改。
5. **没有 Cloudflare Access、没有 IP 白名单**，登录页与接口直接暴露在公网。
6. 前端 dist 需要每次发版重新复制进 `/docker/nginx/html`，目前没有脚本化。
