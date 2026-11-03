# 项目指令

以下路径相对本仓库根目录；单独打开后端仓库时也按此入口读取。

## 编码前读取

1. [CLAUDE.md](CLAUDE.md)：项目定位、业务边界、代码约定与验证命令。
2. [后端编码 Skill](.codex/skills/ruoyi-plus-ai-coding/SKILL.md) 和 [后端 reference](.codex/skills/ruoyi-plus-ai-coding/references/backend.md)。
3. 需要场景细化或委派时读取 [后端角色入口](.claude/agents/backend-engineering.md)，再按其路由读取适用角色，不展开全部角色。
4. 涉及前端时同时读取 [前端入口](../heron-ui/AGENTS.md)；业务需求查阅 [业务规格](docs/plans/2026-10-29-heron-business-spec.md)。

Qoder 可直接读取以上指导材料；只有会话已列出的 Skill 和 Agent 才能按原生名称调用，文件存在不代表同名能力已注册。不要复制 Claude/Codex 的平台配置、权限或 Hooks。

产品改名，若依基座不动；业务代码放在 `ruoyi-modules/ruoyi-heron`，公共模块只读参考，修改需用户另行明确授权。保留已有未提交工作；提交、推送、运行服务和数据库变更分别确认授权范围。

## 已有能力复用

- 新增工具类或通用辅助方法前，必须检索同模块、`ruoyi-common` 和已有依赖中的同类能力；满足业务语义且当前模块可用时直接复用，不添加无业务语义的包装。
- 检索流程、后端语义核对与交付自检见 `.codex/skills/ruoyi-plus-ai-coding/SKILL.md` 及其适用 reference。
