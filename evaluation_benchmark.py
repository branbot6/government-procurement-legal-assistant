#!/usr/bin/env python3
import argparse
import json
import statistics
import time
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor, as_completed
from urllib import request, error


def post_json(url: str, payload: dict, timeout: int = 90):
    body = json.dumps(payload, ensure_ascii=False).encode('utf-8')
    req = request.Request(
        url,
        data=body,
        headers={'Content-Type': 'application/json; charset=utf-8'},
        method='POST'
    )
    with request.urlopen(req, timeout=timeout) as resp:
        content = resp.read().decode('utf-8', errors='ignore')
        return resp.status, content


def call_one(api_url: str, mode: str, item: dict, timeout: int):
    q = item['question']
    payload = {
        'question': q,
        'mode': mode,
    }
    t0 = time.perf_counter()
    status = 'ERR'
    ans = ''
    err_msg = ''
    raw = None
    try:
        code, text = post_json(api_url, payload, timeout=timeout)
        raw = text
        if code == 200:
            try:
                obj = json.loads(text)
            except json.JSONDecodeError:
                obj = {'answer': text}
            ans = (obj.get('answer') or '').strip()
            if not ans and isinstance(obj.get('result'), str):
                ans = obj['result'].strip()
            status = 'OK' if ans else 'EMPTY'
        else:
            err_msg = f'HTTP {code}'
    except error.HTTPError as e:
        err_msg = f'HTTPError {e.code}: {e.reason}'
    except Exception as e:
        err_msg = repr(e)

    latency_ms = int((time.perf_counter() - t0) * 1000)
    anchor = (item.get('anchor') or '').strip()
    anchor_hit = bool(anchor and anchor in ans)

    return {
        'id': item['id'],
        'question': q,
        'source_group': item.get('source_group', ''),
        'category': item.get('category', ''),
        'anchor': anchor,
        'status': status,
        'latency_ms': latency_ms,
        'anchor_hit': anchor_hit,
        'answer': ans,
        'error': err_msg,
        'expected_answer': item.get('expected_answer', ''),
        'source_file': item.get('source_file', ''),
        'source_line': item.get('source_line', 0),
        'raw': raw,
    }


def percentile(values, p):
    if not values:
        return None
    values = sorted(values)
    k = (len(values) - 1) * (p / 100)
    f = int(k)
    c = min(f + 1, len(values) - 1)
    if f == c:
        return values[f]
    return int(values[f] * (c - k) + values[c] * (k - f))


def main():
    ap = argparse.ArgumentParser(description='Benchmark evaluator for legal-assistant API')
    ap.add_argument('--dataset', required=True, help='Path to benchmark dataset JSON')
    ap.add_argument('--api-url', default='http://127.0.0.1:8080/api/v1/chat/ask', help='Chat API URL')
    ap.add_argument('--mode', default='compat', choices=['compat', 'fast'], help='Query mode')
    ap.add_argument('--timeout', type=int, default=90, help='Per request timeout seconds')
    ap.add_argument('--limit', type=int, default=0, help='Run only first N questions, 0 for all')
    ap.add_argument('--workers', type=int, default=1, help='Parallel workers (recommend 1-3)')
    ap.add_argument('--out-dir', default='.', help='Output directory')
    args = ap.parse_args()

    dataset_path = Path(args.dataset)
    doc = json.loads(dataset_path.read_text(encoding='utf-8'))
    items = doc.get('data', [])
    if args.limit and args.limit > 0:
        items = items[:args.limit]

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    ts = time.strftime('%Y%m%d-%H%M%S')
    base = f'benchmark-{args.mode}-{ts}'
    jsonl_path = out_dir / f'{base}.jsonl'
    summary_path = out_dir / f'{base}.summary.json'
    md_path = out_dir / f'{base}.report.md'

    results = []
    if args.workers <= 1:
        for item in items:
            results.append(call_one(args.api_url, args.mode, item, args.timeout))
    else:
        with ThreadPoolExecutor(max_workers=args.workers) as ex:
            futs = [ex.submit(call_one, args.api_url, args.mode, item, args.timeout) for item in items]
            for fut in as_completed(futs):
                results.append(fut.result())
        order = {it['id']: i for i, it in enumerate(items)}
        results.sort(key=lambda x: order.get(x['id'], 10**9))

    with jsonl_path.open('w', encoding='utf-8') as f:
        for r in results:
            f.write(json.dumps(r, ensure_ascii=False) + '\n')

    lat = [r['latency_ms'] for r in results if r['status'] in ('OK', 'EMPTY')]
    ok = [r for r in results if r['status'] == 'OK']
    err = [r for r in results if r['status'] not in ('OK', 'EMPTY')]
    anchor_total = sum(1 for r in results if r['anchor'])
    anchor_hit = sum(1 for r in results if r['anchor_hit'])

    summary = {
        'dataset': str(dataset_path),
        'api_url': args.api_url,
        'mode': args.mode,
        'total': len(results),
        'ok': len(ok),
        'empty': sum(1 for r in results if r['status'] == 'EMPTY'),
        'err': len(err),
        'success_rate': round((len(ok) / len(results) * 100), 2) if results else 0,
        'anchor_total': anchor_total,
        'anchor_hit': anchor_hit,
        'anchor_hit_rate': round((anchor_hit / anchor_total * 100), 2) if anchor_total else None,
        'latency_ms': {
            'p50': percentile(lat, 50),
            'p95': percentile(lat, 95),
            'mean': round(statistics.mean(lat), 2) if lat else None,
        },
        'outputs': {
            'jsonl': str(jsonl_path),
            'summary_json': str(summary_path),
            'report_md': str(md_path),
        }
    }
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')

    lines = [
        f"# Benchmark Report ({args.mode})",
        '',
        f"- dataset: `{dataset_path}`",
        f"- api_url: `{args.api_url}`",
        f"- total: **{summary['total']}**",
        f"- ok: **{summary['ok']}**",
        f"- empty: **{summary['empty']}**",
        f"- err: **{summary['err']}**",
        f"- success_rate: **{summary['success_rate']}%**",
        f"- anchor_hit_rate: **{summary['anchor_hit_rate']}%** (anchor_total={anchor_total})" if summary['anchor_hit_rate'] is not None else "- anchor_hit_rate: N/A",
        f"- latency p50/p95/mean(ms): **{summary['latency_ms']['p50']} / {summary['latency_ms']['p95']} / {summary['latency_ms']['mean']}**",
        '',
        '## Errors',
    ]
    if err:
        for r in err[:20]:
            lines.append(f"- {r['id']} {r['status']}: {r['error']}")
    else:
        lines.append('- none')

    lines += ['', '## Sample Outputs (Top 10)', '']
    for r in results[:10]:
        ans = (r['answer'] or '').replace('\n', ' ')[:240]
        lines.append(f"- {r['id']} [{r['status']}] {r['question']}")
        lines.append(f"  - latency_ms: {r['latency_ms']}, anchor_hit: {r['anchor_hit']}")
        lines.append(f"  - answer: {ans}")

    md_path.write_text('\n'.join(lines), encoding='utf-8')

    print(str(summary_path))
    print(str(jsonl_path))
    print(str(md_path))
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
