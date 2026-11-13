# 技能（Skill）

> 上游：<https://java.agentscope.io/v2/zh/docs/harness/skill> · 快照：`_upstream/harness-skill.md` · 基线 `v2.0.4` · 抓取 2026-11-13
> 上游副标题：四层技能合成、技能市场、自学习闭环

一个 skill 就是一份写好的能力包：一个目录里放一份 `SKILL.md`（说明用途、给 agent 看的指令），可以再带参考文档、脚本或样例。写好后丢给 agent，它会在合适的时候自己用。

Harness 让 skill 从两个地方装进来：

- **技能市场** —— Git 仓库、Nacos、MySQL、classpath、自定义后端；
- **工作区** —— `workspace/skills/` 下大家共用；`<userId>/skills/` 下按用户隔离。

两类来源同时生效，不需要二选一。除此之外还可以打开**自学习闭环**：agent 自己起草 skill → 审核 → 后台周期性整理。

一个 skill 目录长这样：

```
code-reviewer/
├── SKILL.md           # 必需，YAML frontmatter（name + description）+ 给 agent 看的指令
├── references/        # 可选，长篇参考资料，agent 按需读取
│   └── style-guide.md
└── scripts/           # 可选，agent 可以通过 shell 调用的脚本
    └── run-checks.sh
```

SKILL.md 写法：

```markdown
---
name: code-reviewer
description: 当用户需要代码评审、风格反馈或 PR 审核时使用。
---

# Code Reviewer

步骤：
1. 读 `references/style-guide.md` 获取项目规范
2. 跑 `scripts/run-checks.sh <目标路径>`，把结果汇总给用户
```

## 一个例子

```java
HarnessAgent agent = HarnessAgent.builder()
        .name("assistant")
        .model(model)
        .workspace(workspace)
        .skillRepository(new GitSkillRepository("https://github.com/your-org/team-skills.git"))
        .build();
```

后续推理时 agent 能看到这个仓库里的 skill，需要哪个就调 `load_skill_through_path` 加载详情。

## 接技能市场

`skillRepository(...)` 是统一入口，传什么后端都可以。

### Git

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-skill-git-repository</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

```java
.skillRepository(new GitSkillRepository("https://github.com/your-org/team-skills.git"))
```

默认每次读取做轻量远端检查，HEAD 变了才 pull。仓库根下有 `skills/` 子目录会优先读它，否则读根目录。想自己控制同步节奏：`new GitSkillRepository(url, false)`，然后手动 `repo.sync()`。

### Nacos

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-nacos-skill</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

```java
NacosSkillRepository market = new NacosSkillRepository(aiService, "namespace");
HarnessAgent.builder()
        .skillRepository(market)
        .build();
```

适合需要在线下发、变更订阅的场景。`market` 是 `AutoCloseable`，应用退出时关掉以释放订阅。

### MySQL

```java
MysqlSkillRepository registry = MysqlSkillRepository.builder(dataSource)
        .databaseName("agentscope")
        .skillsTableName("skills")
        .createIfNotExist(true)
        .writeable(true)
        .build();

HarnessAgent.builder()
        .skillRepository(registry)
        .build();
```

平台侧统一管理 skill 时常用。`writeable(true)` 后可从 agent 侧写回；只读分发传 `false`。

### Classpath

把 skill 跟 JAR 一起发：

```
src/main/resources/skills/
└── code-reviewer/
    └── SKILL.md
```

```java
.skillRepository(new ClasspathSkillRepository("skills"))
```

兼容标准 JAR 和 Spring Boot Fat JAR。

### 接多个

`skillRepository(...)` 可重复调用，**后注册的优先级更高**：

```java
HarnessAgent.builder()
        .skillRepository(communityMarket)
        .skillRepository(internalRegistry)
        .skillRepository(teamGitRepo)
        .build();
```

## 把 skill 放到工作区

工作区里的 skill 不用注册，目录放好就生效。

### 大家共用

```
workspace/skills/
└── code-reviewer/
    ├── SKILL.md
    ├── references/
    │   └── style-guide.md
    └── scripts/
        └── run-checks.sh
```

适合项目特有的规范、内部约定。

### 单个用户用

给某个用户单独装 skill、或覆盖共用版，放到他 `userId` 命名的子目录：

```
workspace/
├── skills/code-reviewer/SKILL.md   ← 共用版
└── alice/
    └── skills/
        └── code-reviewer/
            └── SKILL.md            ← 只对 alice 生效，覆盖共用版
```

前提是调用时 `RuntimeContext.userId` 传了 `"alice"`。

`workspace/<userId>/skills/` 是**逻辑路径**，不等于「一定是本机磁盘目录」。技能文件读写统一走 `AbstractFilesystem`，实际落点由[文件系统模式](https://java.agentscope.io/v2/zh/docs/harness/filesystem)决定：

- **本机 + shell** —— 宿主磁盘上的 `workspace/alice/skills/...`；
- **共享存储** —— `skills/` 前缀路由到 KV，用户隔离体现为命名空间键 `agents/<agentId>/users/alice/skills/...`，多副本一致，管理台改完下一轮推理即生效；
- **沙箱** —— 宿主侧用户目录在沙箱启动时通过 workspace projection 注入容器 `/workspace`，agent 读到同一份。

不管哪种模式，`<userId>/skills/` 都按同样优先级覆盖共用版。

## 同名冲突谁说了算

四个来源都可能给出同名 skill，优先级从低到高：

| 优先级 | 来源 | 怎么配 |
| - | - | - |
| 1（最低） | 项目全局目录 | `projectGlobalSkillsDir(Path)`，如 `~/.agentscope/skills/` |
| 2 | 市场 | `skillRepository(...)`，后注册的覆盖先注册的 |
| 3 | 工作区共用 | `workspace/skills/` |
| 4（最高） | 用户隔离 | `<userId>/skills/` |

下层独有的 skill 仍然保留，只在重名时被上层覆盖。

举例：团队 Git 上有通用 `code-reviewer`，项目 `workspace/skills/code-reviewer/` 写了项目专属版本 → agent 看到项目版；Alice 在自己目录又覆盖一份 → Alice 调用时拿到她自己的，其他用户仍是项目版。

## 常用 Builder 选项

| 方法 | 说明 |
| - | - |
| `skillRepository(repo)` | 追加一个市场；可重复调用 |
| `skillRepositories(list)` | 一次性替换所有市场 |
| `projectGlobalSkillsDir(path)` | 启用项目全局目录；目录不存在则跳过 |
| `disableDynamicSkills()` | 关掉「每次推理前重新合并」，改成 build 时合并一次 |

子 agent 自动继承父的市场列表和项目全局目录，不用重复配。

什么时候用 `disableDynamicSkills()`：单次任务跑完就退出；或市场后端慢、不想每轮拉。平时不用动。

## 自学习闭环（可选）

「让 agent 自己起草 / 沉淀 / 整理 skill」的闭环，各阶段独立可开。

### 第一步：让 agent 能自己写 skill

```java
HarnessAgent.builder()
    ...
    .enableSkillManageTool(SkillManageConfig.defaults())
    .build();
```

启用后 agent 获得两个工具：

- `propose_skill` —— 把新 skill 写成草稿到 `skills/_drafts/<name>/`，等审核；
- `skill_manage` —— 编辑已有 skill（创建 / 修改 / 添加附属文件 / 删除）。

不想要「草稿 → 审核」两步、让 agent 写完直接生效：`.enableSkillManageTool(true)`（`autoPromote=true`）。**生产场景不建议。**

同时 agent 每次调 `load_skill_through_path` / `read_skill` 时框架自动记一笔使用计数，存到 `skills/.usage.json`——为清理、灰度发布提供数据。

### 第二步：加审核闸门 + 可见性过滤

```java
.enableSkillPromotionGate(
    new LocalApprovalGate(LocalApprovalGate.defaultPrompter()),    // 谁来批
    new CompositeFilter(List.of(                                    // 怎么暴露
        new EnvironmentFilter("prod", skillUsageStore),
        new CanaryFilter(0.10, skillUsageStore)
    )))
.environment("prod")
```

- **闸门** —— 草稿变正式 skill 必须经过它。内置三种：直接拒绝（默认）、本地人工确认（stdin 等）、推消息后等。
- **可见性过滤** —— 决定 agent 推理时能看到哪些「agent 自己创建」的 skill。可按部署环境、灰度比例、白名单组合。

### 第三步：后台周期性整理

```java
.enableSkillCurator(SkillCuratorConfig.builder()
    .intervalHours(7 * 24)        // 一周跑一次
    .staleAfterDays(30)
    .archiveAfterDays(90)
    .build())
```

按节流闸门跑：超过 30 天没用的 skill 标为 stale，超过 90 天归档到 `skills/.archive/`。可选叠加一个 LLM「伞合并」扫描（默认只 dry-run，输出报告，不实际改）。

### 程序化触发

```java
List<SkillAuditLog.Entry> entries = agent.queryAudit(LocalDate.now(), e -> true);

agent.runCuratorOnce()                                       // 立刻跑一次整理（绕过节流）
     .subscribe(report -> System.out.println(report));

agent.promoteSkill("notes-taker", "alice")                   // 手动晋升一份草稿
     .subscribe(result -> System.out.println(result));
```

## agent 怎么读取和执行 skill

每轮推理时 agent 在 system prompt 里看到 `<available_skills>` 块，列出当前可见的所有 skill：

```xml
<available_skills>
<skill>
  <name>code-reviewer</name>
  <description>当用户需要代码评审、风格反馈或 PR 审核时使用。</description>
  <skill-id>code-reviewer_workspace-namespaced</skill-id>
  <files-root>/workspace/skills/code-reviewer</files-root>
</skill>
...
</available_skills>
```

每个条目只带最少元数据，方便 agent 判断要不要加载详情。`<files-root>`（如果有）是 agent 通过 shell 执行该 skill 脚本时使用的绝对路径。

### 读 SKILL.md 和资源文件

加载某个 skill 时 agent 调内置工具 `load_skill_through_path`：

- `load_skill_through_path(skillId, path="SKILL.md")` 返回 markdown 正文；
- `load_skill_through_path(skillId, path="references/style-guide.md")` 返回该 skill 目录下任意文件。

具体怎么取文件取决于 skill 来源：

| Skill 来源 | path 解析方式 |
| - | - |
| 项目全局目录（Layer 1） | 注册时预载到内存 |
| 市场——Git / MySQL / Nacos / classpath（Layer 2） | 由后端预载到内存 |
| `workspace/skills/` 共用（Layer 3） | 注册时预载到内存 |
| `<userId>/skills/` 用户隔离（Layer 4） | `SKILL.md` 预载；其它文件按需通过 `AbstractFilesystem` 读（自动遵循 per-user namespace + sandbox 路由） |

agent 感知不到这种差异。底层查找顺序是「内存命中 → 文件系统读取 → 找不到时返回所有真正可用的路径列表」，所以传错 path 也只会拿到清单而不是死路。

### `<files-root>` 和 shell 执行

skill 自带脚本（如 `scripts/run-checks.sh`）时，agent 需要绝对路径才能通过 `execute_shell_command` 调用。这个路径就是 skill 条目里的 `<files-root>`：

| 文件系统模式（是否有 shell） | 工作区 skill 的 `<files-root>` | 市场 skill 的 `<files-root>` |
| - | - | - |
| Sandbox | `/workspace/skills/<name>` | `/workspace/.skills-cache/<source>/<name>` |
| Local-with-shell | `<wsRoot>/skills/<name>` | `<wsRoot>/.skills-cache/<source>/<name>` |
| Local 不带 shell / Composite | （不渲染——没注册 shell 工具） | （不渲染） |

所以 agent 发出来的命令永远是 `execute_shell_command("python3 <files-root>/scripts/foo.py")`——不用猜路径、不用记每种来源对应哪个前缀。

### 市场 skill 文件实际落在哪儿

市场 skill 的资源最初只在内存里。要让 shell 能跑它们，harness 在每轮推理前物化到 `<wsRoot>/.skills-cache/<source>/<name>/`：

- 文件级 **SHA-256 去重**，只重写变化过的文件；
- 已下架的 skill（或从 builder 移除的整个仓库）留下的孤儿目录，同一轮顺手清掉；
- Sandbox 模式下 `.skills-cache` 默认包含在 workspace projection roots 里，沙箱启动时（及内容变化时）与 `workspace/skills/` 一起 hydrate 进沙箱。

工作区 skill（Layer 3 / 4）不需要 stage——它们本就在工作区目录里。

两个仓库返回相同 `getSource()` 时，第二个自动加后缀（`<source>_2`、`<source>_3`…）并打 warning，所以路径和 skill-id 不会撞。

## 在沙箱里运行 skill

沙箱模式下文件操作和 shell 都在隔离容器里执行，宿主不受影响。skill 脚本写在宿主侧、却要在容器里跑，harness 用「物化 → 投影 → 容器内执行」三步做成透明的。

### 哪些 skill 会进沙箱

| 来源 | 进沙箱前住哪儿 | 沙箱里的路径 |
| - | - | - |
| 工作区 skill（Layer 3 `workspace/skills/`、Layer 4 `<userId>/skills/`） | 本来就在工作区目录树里 | `/workspace/skills/<name>` |
| 市场 skill（Layer 1 项目全局、Layer 2 Git / MySQL / Nacos / classpath） | 资源最初只在内存里 | `/workspace/.skills-cache/<source>/<name>` |

### 第一步：把市场 skill 物化到宿主

每轮推理前 `MarketplaceStager` 把内存中的资源写到宿主 `<wsRoot>/.skills-cache/<source>/<name>/`：

- **文件级 SHA-256 去重**——只重写变化过的文件；
- **孤儿清理**——已下架 skill、或从 builder 移除的整个仓库，留下的目录同一轮删掉；
- **恢复执行位**——资源入库时被转成字符串，POSIX 权限丢了，stager 用启发式补回 `+x`：文件开头是 shebang（`#!`），或后缀是已知脚本类型（`.sh` / `.bash` / `.py` / `.rb` / `.pl` / `.js` / `.mjs`），就按 `chmod +x` 语义（只给本来有读权限的位加执行位）；纯静态资产（`.json` / `.md` / `.txt`）保持 644。

工作区 skill 跳过这一步。

### 第二步：把工作区投影进沙箱（Workspace Projection）

沙箱 `start()` 时，harness 把宿主工作区的静态资产打成 tar，hydrate 进容器 `/workspace`。默认投影根（`workspaceProjectionRoots`）正好覆盖 skill 需要的两个目录：

```
AGENTS.md  skills/  subagents/  knowledge/  .skills-cache/
```

投影对所有被包含文件按内容算整体 SHA-256，跟上次一样就跳过 hydrate，所以反复 `call()` 不会重复传相同文件；只有内容变了才重新注入。

| 方法 | 作用 |
| - | - |
| `workspaceProjectionRoots(List)` | 自定义投影哪些根目录（默认含 `skills`、`.skills-cache`） |
| `workspaceProjectionEnabled(false)` | 完全关掉投影——关了之后沙箱里没有 skill 文件，脚本自然跑不了 |

### 第三步：在容器里执行脚本

沙箱模式下 `<files-root>` 用**容器内**前缀渲染：

| skill 类型 | `<files-root>` |
| - | - |
| 工作区 skill | `/workspace/skills/<name>` |
| 市场 skill | `/workspace/.skills-cache/<source>/<name>` |

于是 agent 直接发：

```
execute_shell_command("python3 /workspace/skills/code-reviewer/scripts/run-checks.sh <目标路径>")
```

agent 不用知道 skill 来自哪一层，前缀由框架算好。若沙箱后端把工作区挂在非默认位置（如 AgentRun 的 `/home/agentscope/workspace`），`<files-root>` 前缀会跟着换。

### 跨调用保留脚本副作用

脚本装了依赖、生成了产物（`npm install`、`pip install`、编译输出），想下次 `call()` 还在，就给沙箱配快照（`snapshotSpec(...)`）。快照保存整个 `/workspace`，下次同 scope key 的调用先恢复快照、再叠加投影，装过的东西不用重装。

### 注意：读 SKILL.md 不需要沙箱

**读** skill（`load_skill_through_path` 取 `SKILL.md` / `references/`）走内存或宿主文件系统，与沙箱无关；只有**用 shell 跑脚本**才需要文件真的进容器。所以即便关了投影、或某个 skill 没带脚本，agent 依然能正常读说明和参考资料。

## 一些建议

- **`description` 决定 agent 用不用这个 skill。** 一开始只看得到 name 和 description，觉得相关才 load 详情。写「数据分析工具」远不如「当用户要算统计、出报表、做趋势图时使用」有效。
- **`SKILL.md` 保持精简。** 控制在 2k tokens 上下，详细参考资料放 `references/`，脚本放 `scripts/`。
- **SKILL.md 和脚本中只使用相对路径。** 框架会按当前文件系统模式自动生成每个 skill 的 `<files-root>` 绝对前缀；硬编码绝对路径（如 `/workspace/scripts/run.py`）会让 skill 只能在特定模式下工作。
- **通用能力放市场，项目特有的写工作区。** 代码评审、表格分析放团队 Git 集中维护；内部 RPC 规范、本项目命名约定写 `workspace/skills/` 跟着代码版本走。
- **用户目录用来「覆盖 + 补充」，不要当主存放。** 关键能力放所有用户都能看到的层。
- **自学习按顺序启用**：先 `enableSkillManageTool`，再加 promotion gate 让审核介入，最后用 curator 处理「老的不再用」。

## Heron 现状

`AgentRuntimeConfig` 没有调用 `.skillRepository(...)`、`.projectGlobalSkillsDir(...)`、`.enableSkillManageTool(...)`，并且显式调用了 `.disableDynamicSkills()` 和 `.disableDefaultWorkspaceSkills()`——技能体系**全部关闭**，agent 只有 `sysPrompt` 里写死的提示词，没有可加载的技能包。

要点：

- 技能目录落在工作区，而当前没有 `.workspace(...)`；只接市场仓库（Git / MySQL / classpath）也必须配工作区，因为 skill 的运行时视图与物化目录（`.skills-cache/`）都在工作区里。
- `disableDynamicSkills()` 会把「每轮重新合并」关掉；重新启用技能时这条要一并去掉，否则市场仓库变更不会在下一次推理生效。
- 若打算用 classpath 方式把技能随 JAR 发（对单租户自部署最省事），入口是 `new ClasspathSkillRepository("skills")`，技能放 `src/main/resources/skills/`。

相关：[harness-workspace.md](harness-workspace.md)、[tool.md](tool.md)（`load_skill_through_path` 工具侧说明）、[harness-subagent.md](harness-subagent.md)
