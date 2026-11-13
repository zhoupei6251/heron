# 数据机会分析：首次真跑

**日期：** 2026-11-13
**触发：** `POST /heron/dataOpportunity/analyze`（默认窗口 120 点）
**结果：** 107 个源 → **1 条机会**，耗时 154.4 秒

---

## 一、返回

```json
{
  "sources": 107, "analyzed": 22, "mergedAway": 6,
  "created": 1, "updated": 0, "skipped": 57,
  "failed": 28, "unhealthy": 0
}
```

落库那一行：

| 字段 | 值 |
|---|---|
| opportunity_id | `2121151796750819328` |
| source_key | `frankfurter-audcny` |
| entity / metric | `aud_cny` / `rate` |
| points | **65** |
| score | 69.94 |
| status | `new` |
| headline | Frankfurter·澳元兑人民币 近 7 日 -2.39%，创窗口新低（3.6σ）（同向 7 个源） |

---

## 二、一个必须更正的判断

Phase 1 真跑时我用的是构造数据，只有 **8 个点**，模型因此判 `OBSERVE`，理由是「拿不到 30 日均值基线，无法核验 -1.8σ」。

**真实数据有 65 个点。** 也就是说那次 `OBSERVE` 是**我的桩造成的**，不是数据线的问题。
我据此写的「短板在证据供给、属于数据线问题」这个判断，前提不成立。

结论要改成：**那条机会在真实数据下大概率可核验**（65 点足够算 30 日基线，信号给出的 z 是 3.6σ）。
下一步应当用真实机会 id 重跑一次研究，看模型是否改判 `PRODUCE`——这才是对 Agent 能力的有效检验。

---

## 三、失败与跳过

- **失败 28 个**，集中在 `eastmoney-*`（`header parser received no bytes`）与 `cheapshark-*`（`Conversion = 'R'`），
  是既有的源端问题，不是本次引入。
- **跳过 57 个**：源不新鲜、变化幅度不可信等，属信号层既有门槛。
- `mergedAway 6`：同事实合并生效（7 个同向源并成 1 条）。
- `unhealthy 0`：没有一个源因健康探测被排除。

---

## 四、端口变更

后端默认端口从 `8080` 改成 `8085`，前后端同步：

| 位置 | 改动 |
|---|---|
| `heron/ruoyi-admin/src/main/resources/application.yml` | `server.port: 8080 → 8085` |
| `heron-ui/.env.development.local` | `VITE_APP_PROXY_TARGET → http://localhost:8085` |
| `heron-ui/vite.config.ts` | 代理兜底地址 `8080 → 8085` |

**验证：** 重新打包后启动，`GET http://127.0.0.1:8085/auth/code` 返回 `200 操作成功`。

注意两点：

1. 配置在 jar 里，**改完必须重新 `package`**，否则仍旧读旧端口（第一次就踩了这个）。
2. `application.yml` 是全局的，`application-prod.yml` 没覆盖端口，所以**生产也会变成 8085**——若生产要走 8080，需在 prod 里显式写回。

---

## 五、本次启动用的临时参数

为了自动化调用，启动时带了两个覆盖参数：

```
--captcha.enable=false        # 登录有图形验证码，脚本无法识别
--api-decrypt.enabled=false   # 登录接口带 @ApiEncrypt，明文请求会被拒
```

**这两个只用于本地联调，没有写进任何配置文件。** 正常开发应保持验证码与接口加密开启。
