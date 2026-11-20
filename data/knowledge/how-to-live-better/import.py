#!/usr/bin/env python3
"""从 HowToLiveBetter 的 book/ 目录生成 manifest.json 与 entries.json。

源仓库需在 manifest.json 记录的 pinnedCommit 上；重新导入时用同一脚本，
entryId 由「章节号-条号」固定生成，不随文件顺序变化。
"""
import json
import re
import sys
from pathlib import Path

ENTRY = re.compile(r"^###\s+(\d+)\.\s+(.*)$")
CHAPTER = re.compile(r"^#\s+(\d+)\.\s+(.*)$")
TAG = re.compile(r"<!--\s*成本标签:\s*(.*?)\s*-->")
FIELD = re.compile(r"^- (成本|说人话|收益|证据等级|来源|备注)：")
URL = re.compile(r"https?://[^\s<>）)；;，]+")


def parse_chapter(path):
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()
    chapter_no = int(path.name.split("-", 1)[0])
    title = ""
    for line in lines:
        match = CHAPTER.match(line)
        if match:
            title = match.group(2).strip()
            break
    entries = []
    current = None
    last_field = None
    for line in lines:
        heading = ENTRY.match(line)
        if heading:
            if current:
                entries.append(current)
            number = int(heading.group(1))
            current = {
                "entryId": f"{chapter_no:02d}-{number:02d}",
                "chapterNo": chapter_no,
                "chapterTitle": title,
                "number": number,
                "title": heading.group(2).strip(),
                "tags": {},
                "fields": {},
            }
            last_field = None
            continue
        if current is None:
            continue
        tag = TAG.search(line)
        if tag:
            for pair in tag.group(1).split():
                if "=" in pair:
                    key, value = pair.split("=", 1)
                    current["tags"][key] = value
            continue
        field = FIELD.match(line)
        if field:
            last_field = field.group(1)
            current["fields"][last_field] = line[field.end():].strip()
            continue
        if last_field and line.strip():
            # 字段折行：没有标记的行并回上一个字段
            current["fields"][last_field] += "\n" + line.strip()
    if current:
        entries.append(current)
    return title, entries


def entry_json(item):
    fields = item["fields"]
    source_text = fields.get("来源", "")
    return {
        "entryId": item["entryId"],
        "chapterNo": item["chapterNo"],
        "chapterTitle": item["chapterTitle"],
        "number": item["number"],
        "title": item["title"],
        "cost": fields.get("成本", ""),
        "plain": fields.get("说人话", ""),
        "benefit": fields.get("收益", ""),
        "evidenceLevel": fields.get("证据等级", ""),
        "sourceText": source_text,
        "sourceUrls": URL.findall(source_text),
        "note": fields.get("备注", ""),
        "tags": item["tags"],
    }


def main():
    src = Path(sys.argv[1])
    out = Path(sys.argv[2])
    chapters = []
    entries = []
    for path in sorted(src.glob("*.md")):
        title, items = parse_chapter(path)
        chapter_no = int(path.name.split("-", 1)[0])
        chapters.append({"chapterNo": chapter_no, "title": title, "file": f"chapters/{path.name}"})
        entries.extend(entry_json(item) for item in items)
    if len(entries) != 772:
        raise SystemExit(f"条目数不符：{len(entries)} != 772")
    ids = [item["entryId"] for item in entries]
    if len(set(ids)) != len(ids):
        raise SystemExit("entryId 有重复")
    (out / "entries.json").write_text(
        json.dumps({"schemaVersion": 1, "entries": entries}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    manifest = json.loads((out / "manifest.json").read_text(encoding="utf-8"))
    manifest["chapters"] = chapters
    manifest["entryCount"] = len(entries)
    (out / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"entries={len(entries)} chapters={len(chapters)}")


if __name__ == "__main__":
    main()
