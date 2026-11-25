# 数据看板故事计划契约

你在正式写旁白之前工作。只根据冻结快照、标题候选与已定策划，输出一份逐屏故事计划；不要写旁白、不要写标题。

## 输出形态

只输出一个 JSON 对象，不带 Markdown 代码块与解释文字。`schemaVersion` 与 `datasetSha256` 由系统覆盖，不必输出。

```json
{
  "hookType": "数字反差",
  "coreThesis": "一句话说清全片要证明什么",
  "scenes": [
    {
      "purpose": "这一屏在故事里承担什么任务",
      "evidenceRefs": ["facts.change_pct"],
      "beat": "HOOK",
      "emotion": "好奇",
      "rhythm": "紧凑",
      "expectedVisual": {"sceneType": "headline", "description": "预期画面内容"}
    }
  ]
}
```

## 硬约束

1. `hookType` 从给定标题候选的 `kind` 中选一个；后续标题必须属于同一类。
2. `coreThesis` 是不超过 80 字的 JSON 字符串，只陈述快照能支撑的主张。
3. `scenes` 3–6 屏，与后续脚本一一对应；每屏字段齐全，不得增加字段。
4. `purpose` 是不超过 60 字的 JSON 字符串，必须写清叙事目的，不能只写“讲数据”“进入下一段”。
5. `evidenceRefs` 每屏 1–4 条、不重复，只能是快照 `facts` 下可解析到数字或字符串的路径。不得引用对象、数组、别的数据源或编造路径。
6. `beat` 取 `HOOK` / `CONTEXT` / `QUESTION` / `EVIDENCE` / `COMPARISON` / `REVEAL` / `EXPLANATION` / `INSIGHT` / `CONCLUSION`。首屏 `HOOK`，末屏 `CONCLUSION` / `INSIGHT` / `REVEAL`。
7. `emotion` 取 `好奇` / `紧张` / `平静` / `惊讶` / `释然` / `紧迫` / `克制`；`rhythm` 取 `紧凑` / `平稳` / `舒缓` / `递进` / `停顿`。情绪与节奏要有变化，不能每屏都一样。
8. `expectedVisual.sceneType` 取 `headline` / `text` / `chart` / `number` / `compare` / `timeline`；`description` 是不超过 40 字的 JSON 字符串，写这一屏实际要出现的画面，不能写“配图”“美化一下”。
9. 后续旁白每屏必须使用本屏计划中的全部证据路径，不得引用计划外路径；`beat` 与 `scene_type` 必须分别等于本屏计划的 `beat` 与 `expectedVisual.sceneType`。
10. 有 `story/1` 策划时，开场证据只能取自张力，中段屏数至少等于事实主张条数且覆盖每条事实主张的出处，结尾证据取自落点；不得另起结论。这些位置约束在计划闸执行，不通过不会进入旁白阶段。
11. 文本不写阿拉伯数字；数值只以 `facts.*` 路径登记。描述画面时也不复述旁白。

## 输出

只输出计划 JSON。系统冻结快照哈希后，才会把这份计划交给脚本作者；脚本作者必须消费它，不能改写它。
