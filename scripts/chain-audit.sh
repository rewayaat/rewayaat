#!/usr/bin/env bash
set -euo pipefail

API_BASE="${API_BASE:-http://localhost:8002}"
ES_BASE="${ES_BASE:-http://localhost:9200/rewayaat_updated}"
SAMPLE_PER_BOOK="${SAMPLE_PER_BOOK:-24}"

SAMPLES_FILE="/tmp/rewayaat-chain-audit-samples.tsv"
ISSUES_FILE="/tmp/rewayaat-chain-audit-issues.tsv"
BOOKS_FILE="/tmp/rewayaat-chain-audit-books.tsv"

: > "$SAMPLES_FILE"
: > "$ISSUES_FILE"
: > "$BOOKS_FILE"

fetch_or_empty() {
  local output
  output="$(curl -sfS "$@" 2>/dev/null || true)"
  printf '%s' "$output"
}

books_json="$(fetch_or_empty -X POST "${ES_BASE}/_search" \
  -H 'Content-Type: application/json' \
  --data '{"size":0,"aggs":{"books":{"terms":{"field":"book.keyword","size":500}}}}')"

if [ -z "$books_json" ]; then
  echo "Unable to fetch books from Elasticsearch at ${ES_BASE}" >&2
  exit 1
fi

printf '%s' "$books_json" | node -e "
let d='';process.stdin.on('data',c=>d+=c);process.stdin.on('end',()=>{
  const j=JSON.parse(d||'{}');
  const buckets=(((j||{}).aggregations||{}).books||{}).buckets||[];
  for (const b of buckets) {
    if (!b || !b.key || !b.doc_count) continue;
    console.log(b.key + '\\t' + b.doc_count);
  }
});
" > "$BOOKS_FILE"

declare -A seen
while IFS=$'\t' read -r book count; do
  [ -z "${book:-}" ] && continue
  [ -z "${count:-}" ] && continue
  if [ "$count" -le 0 ]; then
    continue
  fi

  take="$SAMPLE_PER_BOOK"
  if [ "$count" -lt "$take" ]; then
    take="$count"
  fi
  selected=0
  attempts=0
  max_attempts=$((take * 12))
  if [ "$max_attempts" -lt 30 ]; then
    max_attempts=30
  fi

  while [ "$selected" -lt "$take" ] && [ "$attempts" -lt "$max_attempts" ]; do
    attempts=$((attempts + 1))
    offset=$((RANDOM % count))
    sample_json="$(fetch_or_empty -G "${ES_BASE}/_search" \
      --data-urlencode "q=book.keyword:\"${book}\"" \
      --data "size=1" \
      --data "from=${offset}" \
      --data "_source=false")"
    if [ -z "$sample_json" ]; then
      continue
    fi
    doc_id="$(printf '%s' "$sample_json" | node -e "
let d='';process.stdin.on('data',c=>d+=c);process.stdin.on('end',()=>{
  try {
    const j=JSON.parse(d||'{}');
    const h=((((j||{}).hits||{}).hits||[])[0]||{});
    if (h._id) process.stdout.write(h._id);
  } catch (e) {}
});
")"
    if [ -z "${doc_id:-}" ]; then
      continue
    fi
    key="${book}::${doc_id}"
    if [ -n "${seen[$key]:-}" ]; then
      continue
    fi
    seen[$key]=1
    printf '%s\t%s\n' "$book" "$doc_id" >> "$SAMPLES_FILE"
    selected=$((selected + 1))
  done
done < "$BOOKS_FILE"

while IFS=$'\t' read -r book hadith_id; do
  [ -z "${book:-}" ] && continue
  [ -z "${hadith_id:-}" ] && continue
  resp="$(fetch_or_empty -G "${API_BASE}/v1/narrations" \
    --data-urlencode "q=id:\"${hadith_id}\"" \
    --data "page=1" \
    --data "per_page=1" \
    --data "match_mode=strict")"

  if [ -z "$resp" ]; then
    printf '%s\t%s\trequest_failed\tempty response\n' "$book" "$hadith_id" >> "$ISSUES_FILE"
    continue
  fi

  printf '%s' "$resp" | BOOK="$book" HADITH_ID="$hadith_id" node -e '
let d="";
process.stdin.on("data", c => d += c);
process.stdin.on("end", () => {
  const book=process.env.BOOK || "";
  const hadithId=process.env.HADITH_ID || "";
  const emit=(type, detail, row={})=>{
    const strip=s=>String(s||"").replace(/<[^>]+>/g," ").replace(/\s+/g," ").trim();
    const eChain=strip(row.englishChain).slice(0,180);
    const eContent=strip(row.englishContent || row.english).slice(0,180);
    const aChain=strip(row.arabicChain).slice(0,160);
    const aContent=strip(row.arabicContent || row.arabic).slice(0,160);
    console.log([book, hadithId, type, detail, eChain, eContent, aChain, aContent].join("\t"));
  };

  let parsed={};
  try {
    parsed=JSON.parse(d||"{}");
  } catch (e) {
    emit("json_error", String(e.message || e));
    return;
  }
  const row=(((parsed||{}).collection)||[])[0];
  if (!row) {
    emit("missing_result", "id query returned no result");
    return;
  }
  const strip=s=>String(s||"").replace(/<[^>]+>/g," ").replace(/\s+/g," ").trim();
  const english=strip(row.english);
  const arabic=strip(row.arabic);
  const englishChain=strip(row.englishChain);
  const englishContent=strip(row.englishContent);
  const arabicChain=strip(row.arabicChain);
  const arabicContent=strip(row.arabicContent);

  if (!english && !arabic) {
    emit("missing_text", "both english and arabic are empty", row);
    return;
  }

  if (!englishChain) {
    const chainCue = /(narrated|narrated from|has narrated|on the authority of|it is narrated from)/i.test(english);
    const saidCue = /(who has said|who said|saying|he said|said:)/i.test(english);
    if (english.length > 180 && chainCue && saidCue) {
      emit("english_unsplit", "chain cues found but englishChain missing", row);
    }
  } else {
    const ratio = english ? englishChain.length / Math.max(1, english.length) : 0;
    if (ratio > 0.86 && englishContent.length < 110) {
      emit("english_chain_dominates", "chain occupies most english text", row);
    }
    if (/\b(then i said|i said to|i asked|then i asked)\b/i.test(englishChain)) {
      emit("english_chain_contains_dialogue", "dialogue text appears in englishChain", row);
    }
  }

  if (!arabicChain) {
    const chainCue = /(حدثنا|حدّثنا|اخبرنا|أخبرنا|وبهذا الاسناد|بهذا الاسناد|عن)/.test(arabic);
    const saidCue = /(قال|فقال|قلت)/.test(arabic);
    if (arabic.length > 140 && chainCue && saidCue) {
      emit("arabic_unsplit", "chain cues found but arabicChain missing", row);
    }
  } else {
    const ratio = arabic ? arabicChain.length / Math.max(1, arabic.length) : 0;
    if (ratio > 0.88 && arabicContent.length < 130) {
      emit("arabic_chain_dominates", "chain occupies most arabic text", row);
    }
    if (/(^|\s)(قلت|فقلت|ثم قلت)(\s|$)/.test(arabicChain)) {
      emit("arabic_chain_contains_dialogue", "dialogue text appears in arabicChain", row);
    }
  }
});
' >> "$ISSUES_FILE"
done < "$SAMPLES_FILE"

book_count="$(wc -l < "$BOOKS_FILE" | tr -d ' ')"
sample_count="$(wc -l < "$SAMPLES_FILE" | tr -d ' ')"
issue_count="$(wc -l < "$ISSUES_FILE" | tr -d ' ')"
high_severity_count="$(rg -e '(unsplit|contains_dialogue|missing_text|missing_result|json_error|request_failed)' -c "$ISSUES_FILE" || true)"

echo "BOOKS=${book_count}"
echo "SAMPLED=${sample_count}"
echo "ISSUES=${issue_count}"
echo "HIGH_SEVERITY=${high_severity_count:-0}"

if [ "${issue_count}" -gt 0 ]; then
  echo "--- Top issues ---"
  head -n 40 "$ISSUES_FILE"
fi
