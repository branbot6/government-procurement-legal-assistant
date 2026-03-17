#!/usr/bin/env bash
set -euo pipefail

API_BASE_URL="${API_BASE_URL:-http://127.0.0.1:8080}"
COUNT="${COUNT:-30}"
OUT_DIR="${OUT_DIR:-/tmp}"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT_FILE="${OUT_DIR}/legal-eval-${STAMP}.jsonl"

declare -a LAWS=(
  "中华人民共和国政府采购法"
  "中华人民共和国政府采购法实施条例"
  "政府采购货物和服务招标投标管理办法"
  "政府采购非招标采购方式管理办法"
  "政府采购竞争性磋商采购方式管理暂行办法"
  "政府采购质疑和投诉办法"
  "政府采购需求管理办法"
  "财政部关于进一步加强政府采购需求和履约验收管理的指导意见"
  "江苏省政府采购评审专家管理办法"
  "江苏省政府采购信用管理暂行办法"
)

declare -a TOPICS=(
  "政府采购"
  "竞争性磋商"
  "单一来源采购"
  "询价采购"
  "履约验收"
  "质疑答复"
  "投诉处理"
  "采购需求编制"
  "评审专家管理"
  "供应商信用记录"
  "重大违法记录"
  "框架协议采购"
)

declare -a ACTIONS=(
  "适用情形"
  "办理流程"
  "法定时限"
  "责任分工"
  "合规要求"
  "禁止性规定"
  "材料要求"
  "审查要点"
  "风险点"
  "处理规则"
)

declare -a ARTICLES=(
  "第二条" "第六条" "第十九条" "第二十六条" "第三十条" "第三十一条" "第三十六条" "第五十条"
)

declare -a DOC_NO=(
  "财库〔2022〕3号"
  "财库〔2021〕22号"
  "财库〔2024〕84号"
  "财库〔2020〕10号"
  "财库〔2016〕99号"
  "财库〔2018〕2号"
)

declare -a REGIONS=(
  "国家层面"
  "江苏省"
  "扬州市"
)

pick() {
  local name="$1"
  local size idx
  case "$name" in
    LAWS)
      size="${#LAWS[@]}"
      idx=$((RANDOM % size))
      echo "${LAWS[$idx]}"
      ;;
    TOPICS)
      size="${#TOPICS[@]}"
      idx=$((RANDOM % size))
      echo "${TOPICS[$idx]}"
      ;;
    ACTIONS)
      size="${#ACTIONS[@]}"
      idx=$((RANDOM % size))
      echo "${ACTIONS[$idx]}"
      ;;
    ARTICLES)
      size="${#ARTICLES[@]}"
      idx=$((RANDOM % size))
      echo "${ARTICLES[$idx]}"
      ;;
    DOC_NO)
      size="${#DOC_NO[@]}"
      idx=$((RANDOM % size))
      echo "${DOC_NO[$idx]}"
      ;;
    REGIONS)
      size="${#REGIONS[@]}"
      idx=$((RANDOM % size))
      echo "${REGIONS[$idx]}"
      ;;
    *)
      echo ""
      ;;
  esac
}

build_item() {
  local mode="$1"
  local law topic action article docno region q anchor
  law="$(pick LAWS)"
  topic="$(pick TOPICS)"
  action="$(pick ACTIONS)"
  article="$(pick ARTICLES)"
  docno="$(pick DOC_NO)"
  region="$(pick REGIONS)"

  case "$mode" in
    0)
      q="${topic}的法律定义是什么？"
      anchor="${topic}"
      ;;
    1)
      q="根据《${law}》，${article}主要规定了什么？"
      anchor="${law}"
      ;;
    2)
      q="${region}关于${topic}的${action}有哪些硬性要求？"
      anchor="${topic}"
      ;;
    3)
      q="${topic}在实务中应当遵循哪些程序步骤？"
      anchor="${topic}"
      ;;
    4)
      q="${docno}对${topic}的核心要求是什么？"
      anchor="${docno}"
      ;;
    5)
      q="供应商出现重大违法记录后，参加政府采购活动有什么限制？"
      anchor="重大违法记录"
      ;;
    6)
      q="采购人采用${topic}时，最容易违规的环节有哪些？"
      anchor="${topic}"
      ;;
    7)
      q="请解释${topic}与公开招标的适用边界。"
      anchor="${topic}"
      ;;
    8)
      q="在${region}，${topic}的投诉处理时限如何计算？"
      anchor="投诉"
      ;;
    *)
      q="请按法规依据说明${topic}的${action}。"
      anchor="${topic}"
      ;;
  esac

  printf '%s\t%s\n' "$q" "$anchor"
}

mkdir -p "$OUT_DIR"
: > "$OUT_FILE"

total=0
ok=0
miss=0
err=0
fallback=0
empty_evidence=0
sum_score=0
score_count=0
anchor_hit=0

SEEN_FILE="$(mktemp /tmp/legal-eval-seen.XXXXXX)"
while [[ "$total" -lt "$COUNT" ]]; do
  item="$(build_item $((RANDOM % 10)))"
  q="${item%%$'\t'*}"
  anchor="${item#*$'\t'}"

  if grep -Fqx "$q" "$SEEN_FILE"; then
    continue
  fi
  echo "$q" >> "$SEEN_FILE"

  payload="$(jq -nc --arg q "$q" '{question:$q}')"
  if ! resp="$(curl -sS -m 120 -X POST "${API_BASE_URL}/api/v1/chat/ask" \
      -H 'Content-Type: application/json' \
      -d "$payload")"; then
    err=$((err + 1))
    total=$((total + 1))
    jq -nc --arg q "$q" --arg a "$anchor" --arg status "ERR" \
      '{question:$q,anchor:$a,status:$status}' >> "$OUT_FILE"
    continue
  fi

  answer="$(echo "$resp" | jq -r '.answer // ""')"
  ev_count="$(echo "$resp" | jq -r '.evidences | length')"
  top_title="$(echo "$resp" | jq -r '.evidences[0].title // ""')"
  top_score="$(echo "$resp" | jq -r '.evidences[0].score // 0')"

  status="OK"
  if [[ "$answer" == *"未找到足够依据"* ]]; then
    status="MISS"
    miss=$((miss + 1))
  else
    ok=$((ok + 1))
  fi

  if [[ "$answer" == *"当前大模型不可用"* ]]; then
    fallback=$((fallback + 1))
  fi
  if [[ "$ev_count" -eq 0 ]]; then
    empty_evidence=$((empty_evidence + 1))
  fi

  if [[ "$top_score" != "0" ]]; then
    sum_score="$(awk -v a="$sum_score" -v b="$top_score" 'BEGIN{printf "%.6f", a+b}')"
    score_count=$((score_count + 1))
  fi

  if [[ "$top_title" == *"$anchor"* || "$answer" == *"$anchor"* ]]; then
    anchor_hit=$((anchor_hit + 1))
  fi

  jq -nc \
    --arg q "$q" \
    --arg a "$anchor" \
    --arg status "$status" \
    --arg answer "$answer" \
    --arg top_title "$top_title" \
    --argjson ev_count "$ev_count" \
    --argjson top_score "$top_score" \
    '{question:$q,anchor:$a,status:$status,evidence_count:$ev_count,top_score:$top_score,top_title:$top_title,answer:$answer}' \
    >> "$OUT_FILE"

  total=$((total + 1))
done

rm -f "$SEEN_FILE"

avg_score="0"
if [[ "$score_count" -gt 0 ]]; then
  avg_score="$(awk -v s="$sum_score" -v c="$score_count" 'BEGIN{printf "%.4f", s/c}')"
fi

echo "EVAL_FILE=${OUT_FILE}"
echo "TOTAL=${total} OK=${ok} MISS=${miss} ERR=${err} FALLBACK=${fallback} EMPTY_EVIDENCE=${empty_evidence} ANCHOR_HIT=${anchor_hit} AVG_TOP_SCORE=${avg_score}"
