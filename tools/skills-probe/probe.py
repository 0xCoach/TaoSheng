#!/usr/bin/env python3
"""发送 Kotlin 实现导出的三份有界请求。仅操作本机副本，不连接手机、不改原库。
密钥只在脚本内读取；原始请求/响应保留在被 Git 忽略的 private 目录。
"""
import argparse
import json
import pathlib
import time
import urllib.request
import urllib.error

ROOT = pathlib.Path(__file__).resolve().parent
IDS = ("meeting", "evening_note", "weekly")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="deepseek-flash")
    args = parser.parse_args()
    directory = ROOT / "private"
    key = pathlib.Path("~/.config/resound/deepseek.key").expanduser().read_text().strip()
    for skill_id in IDS:
        request_file = directory / f"{skill_id}-request.json"
        source = json.loads(request_file.read_text())
        response_file = directory / f"{skill_id}-response.json"
        if response_file.exists():
            print(f"{skill_id}: 已有结果，跳过，避免重复花费")
            continue
        body = {"model": args.model, "temperature": 0.2, "response_format": {"type": "json_object"},
                "messages": [{"role": "system", "content": source["system"]},
                             {"role": "user", "content": source["user"]}]}
        req = urllib.request.Request("https://api.deepseek.com/chat/completions", json.dumps(body).encode(),
                                     {"Authorization": "Bearer " + key, "Content-Type": "application/json"})
        started = time.monotonic()
        try:
            with urllib.request.urlopen(req, timeout=200) as reply:
                result = json.load(reply)
        except urllib.error.HTTPError as exc:
            raise SystemExit(f"{skill_id}: HTTP {exc.code}，未输出服务端正文") from None
        except Exception as exc:
            raise SystemExit(f"{skill_id}: {type(exc).__name__}，未输出请求或凭据") from None
        response_file.write_text(json.dumps(result, ensure_ascii=False, indent=2))
        content = result["choices"][0]["message"]["content"]
        parsed = json.loads(content.removeprefix("```json").removesuffix("```").strip())
        cards = parsed.get("cards", [])
        invalid_refs = 0
        quote_count = 0
        for card in cards:
            for ref in card.get("evidence", []):
                label = str(ref) if str(ref).startswith("L") else f"L{ref}"
                invalid_refs += label not in source["lines"]
            for ref in card.get("values", {}).get("quotes", []):
                label = str(ref) if str(ref).startswith("L") else f"L{ref}"
                quote_count += label in source["lines"]
        summary = dict(skill=skill_id, model=args.model, date=source["date"], elapsedSeconds=round(time.monotonic()-started, 1),
                       inputLines=len(source["lines"]), cards=len(cards), titles=[c.get("title") for c in cards],
                       nothingReason=parsed.get("nothing_reason"), usage=result.get("usage", {}), invalidEvidence=invalid_refs,
                       validQuoteRefs=quote_count)
        (directory / f"{skill_id}-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2))
        print(json.dumps(summary, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
