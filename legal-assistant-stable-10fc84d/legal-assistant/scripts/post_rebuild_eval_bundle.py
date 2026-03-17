#!/usr/bin/env python3
import csv
import html
import json
import random
import re
import sys
import time
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Optional, Tuple
from urllib import error, request

API_URL = "https://xn--xhqt48colas13k.tech/api/v1/chat/ask"
BASE_DESKTOP = Path("/Users/brandonbot/Desktop")
OLD_HARD200 = BASE_DESKTOP / "200题高难回归_20260305-061031" / "legal-eval-hard-200-20260305-061031.jsonl"
DEFAULT_VECTOR = Path("/tmp/vector-store-server-latest.json")


def now_stamp() -> str:
    return datetime.now().strftime("%Y%m%d-%H%M%S")


def read_jsonl(path: Path) -> List[Dict]:
    arr = []
    for line in path.read_text("utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        arr.append(json.loads(line))
    return arr


def post_question(question: str, timeout_sec: int = 75) -> Dict:
    payload = json.dumps({"question": question}, ensure_ascii=False).encode("utf-8")
    req = request.Request(
        API_URL,
        data=payload,
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST",
    )
    with request.urlopen(req, timeout=timeout_sec) as resp:
        body = resp.read().decode("utf-8", errors="ignore")
        return json.loads(body)


def norm_status(answer: str, api_ok: bool = True) -> str:
    if not api_ok:
        return "ERR"
    if "未找到足够依据" in answer:
        return "MISS"
    return "OK"


def short(s: str, n: int = 240) -> str:
    s = (s or "").replace("\r", "").replace("\n", " ").strip()
    return s if len(s) <= n else s[:n] + "..."


def compute_metrics(rows: List[Dict]) -> Dict[str, float]:
    total = len(rows)
    ok = sum(1 for r in rows if r.get("status") == "OK")
    miss = sum(1 for r in rows if r.get("status") == "MISS")
    err = sum(1 for r in rows if r.get("status") == "ERR")
    fallback = sum(1 for r in rows if r.get("fallback"))
    empty_evidence = sum(1 for r in rows if int(r.get("evidence_count", 0)) == 0)
    anchor_hit = sum(
        1
        for r in rows
        if (r.get("anchor") or "")
        and ((r.get("anchor") or "") in (r.get("top_title") or "") or (r.get("anchor") or "") in (r.get("answer") or ""))
    )
    scores = [float(r.get("top_score", 0) or 0) for r in rows if r.get("status") != "ERR"]
    avg_top = sum(scores) / len(scores) if scores else 0.0
    return {
        "total": total,
        "ok": ok,
        "miss": miss,
        "err": err,
        "fallback": fallback,
        "empty_evidence": empty_evidence,
        "anchor_hit": anchor_hit,
        "avg_top_score": round(avg_top, 4),
    }


def category_stats(rows: List[Dict]) -> List[Dict]:
    bucket = defaultdict(list)
    for r in rows:
        bucket[r.get("category") or "未分类"].append(r)
    out = []
    for cat, arr in sorted(bucket.items(), key=lambda kv: (-len(kv[1]), kv[0])):
        n = len(arr)
        ok = sum(1 for x in arr if x.get("status") == "OK")
        miss = sum(1 for x in arr if x.get("status") == "MISS")
        scores = [float(x.get("top_score", 0) or 0) for x in arr if x.get("status") != "ERR"]
        avg = sum(scores) / len(scores) if scores else 0.0
        out.append({"category": cat, "n": n, "ok": ok, "miss": miss, "avg_top_score": f"{avg:.4f}"})
    return out


def write_csv(path: Path, headers: List[str], rows: List[Dict]) -> None:
    with path.open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=headers)
        w.writeheader()
        for r in rows:
            w.writerow({k: r.get(k, "") for k in headers})


def write_question_pages(rows: List[Dict], pages_dir: Path, with_old: bool = False) -> None:
    pages_dir.mkdir(parents=True, exist_ok=True)
    for i, r in enumerate(rows, 1):
        p = pages_dir / f"{i:03d}.md"
        lines = []
        lines.append(f"# 第 {i:03d} 题（{r.get('category') or '未分类'}）")
        lines.append("")
        lines.append("## 问题")
        lines.append(r.get("question", ""))
        lines.append("")
        if with_old:
            lines.append("## 旧版结果")
            lines.append(f"- old_status: {r.get('old_status','')}")
            lines.append(f"- old_top_title: {r.get('old_top_title','')}")
            lines.append("- old_answer_snippet: " + short(r.get("old_answer", ""), 480))
            lines.append("")
        lines.append("## 新版结果")
        lines.append(f"- status: {r.get('status','')}")
        lines.append(f"- evidence_count: {r.get('evidence_count',0)}")
        lines.append(f"- top_score: {r.get('top_score',0)}")
        lines.append(f"- anchor: {r.get('anchor','')}")
        lines.append("")
        lines.append("## Top1 证据标题")
        lines.append(r.get("top_title", ""))
        lines.append("")
        if with_old:
            lines.append("## 对比结论")
            lines.append(f"- compare: {r.get('compare','')}")
            lines.append(f"- notes: {r.get('compare_notes','')}")
            lines.append("")
        lines.append("## 回答")
        lines.append(r.get("answer", ""))
        p.write_text("\n".join(lines), encoding="utf-8")


def write_html_print(rows: List[Dict], path: Path, with_old: bool = False) -> None:
    cards = []
    for i, r in enumerate(rows, 1):
        old = ""
        if with_old:
            old = (
                f"<div><b>旧版状态:</b> {html.escape(str(r.get('old_status','')))}</div>"
                f"<div><b>旧版Top1:</b> {html.escape(str(r.get('old_top_title','')))}</div>"
                f"<div><b>对比:</b> {html.escape(str(r.get('compare','')))} / {html.escape(str(r.get('compare_notes','')))}</div>"
            )
        cards.append(
            "<article class='card'>"
            f"<h2>{i:03d}. {html.escape(str(r.get('category') or '未分类'))}</h2>"
            f"<div><b>问题:</b> {html.escape(str(r.get('question','')))}</div>"
            f"<div><b>状态:</b> {html.escape(str(r.get('status','')))} | <b>evidence:</b> {r.get('evidence_count',0)} | <b>top_score:</b> {r.get('top_score',0)}</div>"
            f"<div><b>Top1:</b> {html.escape(str(r.get('top_title','')))}</div>"
            + old
            + f"<pre>{html.escape(str(r.get('answer','')))}</pre>"
            "</article>"
        )
    doc = """<!doctype html><html><head><meta charset='utf-8'><title>每题汇总可打印</title>
<style>
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;padding:20px;line-height:1.45}
.card{border:1px solid #ddd;border-radius:10px;padding:12px;margin:10px 0;page-break-inside:avoid}
pre{white-space:pre-wrap;word-break:break-word;background:#fafafa;padding:8px;border-radius:8px}
</style></head><body><h1>每题汇总可打印</h1>{cards}</body></html>""".replace("{cards}", "\n".join(cards))
    path.write_text(doc, encoding="utf-8")


def pick_hard20(old_rows: List[Dict], n: int = 20, seed: int = 20260308) -> List[Dict]:
    rnd = random.Random(seed)
    by_cat = defaultdict(list)
    for r in old_rows:
        by_cat[r.get("category") or "未分类"].append(r)
    for k in by_cat:
        rnd.shuffle(by_cat[k])
    cats = sorted(by_cat.keys(), key=lambda c: (-len(by_cat[c]), c))
    out = []
    i = 0
    while len(out) < n:
        c = cats[i % len(cats)]
        if by_cat[c]:
            out.append(by_cat[c].pop())
        i += 1
        if i > n * 20:
            break
    if len(out) < n:
        remain = [x for arr in by_cat.values() for x in arr]
        rnd.shuffle(remain)
        out.extend(remain[: n - len(out)])
    return out[:n]


def extract_doc_no(text: str) -> str:
    if not text:
        return ""
    m = re.search(r"([\u4e00-\u9fa5]{1,8}[〔\[]\d{4}[〕\]]\d+号)", text)
    if m:
        return m.group(1)
    m = re.search(r"(\d{4}年第?\d+号)", text)
    return m.group(1) if m else ""


def load_pdf_candidates(vector_path: Path) -> List[Dict]:
    arr = json.loads(vector_path.read_text("utf-8"))
    by_path: Dict[str, Dict] = {}
    for r in arr:
        sp = r.get("sourcePath") or ""
        if not sp.lower().endswith(".pdf"):
            continue
        it = by_path.setdefault(sp, {
            "sourcePath": sp,
            "title": r.get("lawTitle") or r.get("title") or Path(sp).stem,
            "chunks": 0,
            "best_content": "",
            "articleNos": set(),
        })
        it["chunks"] += 1
        c = (r.get("content") or "").strip()
        if len(c) > len(it["best_content"]):
            it["best_content"] = c
        a = (r.get("articleNo") or "").strip()
        if a:
            it["articleNos"].add(a)
    docs = list(by_path.values())
    for d in docs:
        d["articleNos"] = sorted(d["articleNos"])
        d["docNo"] = extract_doc_no(d["title"]) or extract_doc_no(d["sourcePath"]) or ""
    docs = [d for d in docs if len(d.get("best_content", "")) >= 80]
    docs.sort(key=lambda x: (-x["chunks"], x["sourcePath"]))
    return docs


def make_scanned_questions(docs: List[Dict], n_normal: int = 20, n_hard: int = 20, seed: int = 20260308) -> Tuple[List[Dict], List[Dict]]:
    rnd = random.Random(seed)
    docs2 = docs[:]
    rnd.shuffle(docs2)
    chosen = docs2[: n_normal + n_hard + 10]
    normal_templates = [
        "《{title}》主要规范什么事项？请概括3个要点并说明适用主体。",
        "依据《{title}》，采购人最核心的合规要求有哪些？",
        "《{title}》对应的办理流程大致分哪几个阶段？",
        "按《{title}》，最容易被忽略的时限要求是什么？",
    ]
    hard_templates = [
        "请基于《{title}》给出一份实务执行清单：触发条件、程序节点、时限、责任主体、留痕材料。",
        "《{title}》在执行时若与上位法发生口径冲突，应该如何按法规范层级处理？请给可落地顺序。",
        "如果围绕《{title}》发生投诉争议，如何从证据充分性、程序合规、风险控制三维进行处置？",
        "请结合《{title}》设计“可审计”的内控动作，并指出3个高风险误区。",
    ]

    normal = []
    hard = []

    for i, d in enumerate(chosen):
        title = d["title"]
        doc_no = d.get("docNo") or ""
        anchor = doc_no or title[:14]
        if len(normal) < n_normal:
            t = normal_templates[len(normal) % len(normal_templates)]
            q = t.format(title=title)
            normal.append({
                "question": q,
                "anchor": anchor,
                "category": "扫描PDF-普通",
                "sourcePath": d["sourcePath"],
                "sourceTitle": title,
            })
        elif len(hard) < n_hard:
            t = hard_templates[len(hard) % len(hard_templates)]
            q = t.format(title=title)
            hard.append({
                "question": q,
                "anchor": anchor,
                "category": "扫描PDF-高难",
                "sourcePath": d["sourcePath"],
                "sourceTitle": title,
            })
        if len(normal) >= n_normal and len(hard) >= n_hard:
            break

    return normal, hard


def eval_questions(question_rows: List[Dict], sleep_sec: float = 0.12, label: str = "") -> List[Dict]:
    out = []
    total = len(question_rows)
    for i, q in enumerate(question_rows, 1):
        question = q["question"]
        print(f"[{label}] {i}/{total} {short(question, 52)}", flush=True)
        row = {
            "question": question,
            "anchor": q.get("anchor", ""),
            "category": q.get("category", "未分类"),
            "sourcePath": q.get("sourcePath", ""),
            "sourceTitle": q.get("sourceTitle", ""),
        }
        try:
            resp = post_question(question)
            answer = resp.get("answer", "")
            ev = resp.get("evidences") or []
            row["status"] = norm_status(answer, api_ok=True)
            row["answer"] = answer
            row["evidence_count"] = len(ev)
            row["top_title"] = ev[0].get("title", "") if ev else ""
            row["top_score"] = ev[0].get("score", 0) if ev else 0
            row["fallback"] = "当前大模型不可用" in answer
        except Exception as e:
            row["status"] = "ERR"
            row["answer"] = f"ERROR: {e}"
            row["evidence_count"] = 0
            row["top_title"] = ""
            row["top_score"] = 0
            row["fallback"] = False
        out.append(row)
        time.sleep(sleep_sec)
    print(f"[{label}] done: {len(out)}", flush=True)
    return out


def compare_with_old(new_rows: List[Dict], old_map: Dict[str, Dict]) -> None:
    for r in new_rows:
        o = old_map.get(r["question"], {})
        r["old_status"] = o.get("status", "")
        r["old_top_title"] = o.get("top_title", "")
        r["old_answer"] = o.get("answer", "")
        if not o:
            r["compare"] = "NEW_ONLY"
            r["compare_notes"] = "旧集中无该问题"
            continue
        if o.get("status") == "MISS" and r.get("status") == "OK":
            r["compare"] = "IMPROVED"
            r["compare_notes"] = "由MISS提升为OK"
        elif o.get("status") == "OK" and r.get("status") == "MISS":
            r["compare"] = "REGRESSED"
            r["compare_notes"] = "由OK回退为MISS"
        elif o.get("status") == r.get("status"):
            r["compare"] = "SAME"
            if short(o.get("answer", ""), 120) != short(r.get("answer", ""), 120):
                r["compare_notes"] = "状态相同但回答内容有变化"
            else:
                r["compare_notes"] = "状态与回答骨架基本一致"
        else:
            r["compare"] = "CHANGED"
            r["compare_notes"] = f"状态变化 {o.get('status')} -> {r.get('status')}"


def write_result_bundle(rows: List[Dict], out_dir: Path, title: str, with_old: bool = False) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    metrics = compute_metrics(rows)
    cats = category_stats(rows)

    jsonl_path = out_dir / "result.jsonl"
    with jsonl_path.open("w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    write_csv(
        out_dir / "总指标.csv",
        ["metric", "value"],
        [{"metric": k, "value": v} for k, v in metrics.items()],
    )
    write_csv(out_dir / "分类统计.csv", ["category", "n", "ok", "miss", "avg_top_score"], cats)

    report_lines = [
        f"# {title}",
        "",
        "## 总体结果",
    ]
    for k in ["total", "ok", "miss", "err", "fallback", "empty_evidence", "anchor_hit", "avg_top_score"]:
        report_lines.append(f"- {k.upper()}: {metrics[k]}")

    if with_old:
        cmp_cnt = Counter(r.get("compare") for r in rows)
        report_lines.extend(["", "## 新旧对比", f"- IMPROVED: {cmp_cnt.get('IMPROVED',0)}", f"- REGRESSED: {cmp_cnt.get('REGRESSED',0)}", f"- SAME: {cmp_cnt.get('SAME',0)}", f"- CHANGED: {cmp_cnt.get('CHANGED',0)}"])

    misses = [r for r in rows if r.get("status") == "MISS"][:8]
    if misses:
        report_lines.extend(["", "## MISS 样本"])
        for r in misses:
            report_lines.append(f"- 问题：{r.get('question','')}")
            report_lines.append(f"  - top_title: {r.get('top_title','')}")
            report_lines.append(f"  - answer_snippet: {short(r.get('answer',''), 380)}")

    if with_old:
        reg = [r for r in rows if r.get("compare") == "REGRESSED"][:8]
        if reg:
            report_lines.extend(["", "## 回退样本"])
            for r in reg:
                report_lines.append(f"- 问题：{r.get('question','')}")
                report_lines.append(f"  - old_status: {r.get('old_status','')} -> new_status: {r.get('status','')}")
                report_lines.append(f"  - notes: {r.get('compare_notes','')}")

    report_lines.extend([
        "",
        "## 文件",
        f"- 原始结果: {jsonl_path}",
        f"- 每题一页目录: {out_dir / '每题一页'}",
        f"- 可打印分页: {out_dir / '每题汇总可打印.html'}",
    ])
    (out_dir / "报告.md").write_text("\n".join(report_lines), encoding="utf-8")

    write_question_pages(rows, out_dir / "每题一页", with_old=with_old)
    write_html_print(rows, out_dir / "每题汇总可打印.html", with_old=with_old)


def main() -> int:
    stamp = now_stamp()
    root = BASE_DESKTOP / f"重建后抽样评测_{stamp}"
    root.mkdir(parents=True, exist_ok=True)

    old_rows = read_jsonl(OLD_HARD200)
    hard20_old = pick_hard20(old_rows, n=20, seed=20260308)

    # 1) 高难200抽样20 + 对比
    q1 = [{
        "question": r["question"],
        "anchor": r.get("anchor", ""),
        "category": r.get("category", "高难抽样"),
    } for r in hard20_old]
    new1 = eval_questions(q1, label="hard20_compare")
    old_map = {r["question"]: r for r in old_rows}
    compare_with_old(new1, old_map)
    write_result_bundle(new1, root / f"01_高难200抽样20对比_{stamp}", "高难200抽样20对比报告", with_old=True)

    # 2/3) 扫描PDF普通20 + 高难20
    vector_path = DEFAULT_VECTOR
    if not vector_path.exists():
        local_v = Path("/Users/brandonbot/APPDev/法律助手稳定版/legal-assistant/.run/vector-store.json")
        vector_path = local_v
    docs = load_pdf_candidates(vector_path)
    normal_q, hard_q = make_scanned_questions(docs, n_normal=20, n_hard=20, seed=20260308)

    normal_rows = eval_questions(normal_q, label="scan_normal20")
    write_result_bundle(normal_rows, root / f"02_扫描PDF普通20_{stamp}", "扫描PDF普通20评测报告", with_old=False)

    hard_rows = eval_questions(hard_q, label="scan_hard20")
    write_result_bundle(hard_rows, root / f"03_扫描PDF高难20_{stamp}", "扫描PDF高难20评测报告", with_old=False)

    # index file
    (root / "README.md").write_text(
        "\n".join([
            "# 重建后抽样评测结果",
            "",
            f"- 生成时间: {stamp}",
            f"- API: {API_URL}",
            f"- 题集1: 200题高难抽样20（含新旧对比）",
            f"- 题集2: 扫描PDF普通20",
            f"- 题集3: 扫描PDF高难20",
        ]),
        encoding="utf-8",
    )

    print(root)
    return 0


if __name__ == "__main__":
    sys.exit(main())
