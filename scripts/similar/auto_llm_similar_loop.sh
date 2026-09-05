#!/bin/bash
# Auto-loop LLM Similar Hadith pipeline
# Runs claude sessions back-to-back until all batches are processed
# Launch in tmux: tmux new-session -d -s llm-pipe "bash scripts/similar/auto_llm_similar_loop.sh"

set -e
cd /home/zir0/git/rewayaat

LOGFILE="tmp/auto_loop.log"
exec > >(tee -a "$LOGFILE") 2>&1

SESSION=0

while true; do
    SESSION=$((SESSION + 1))
    echo ""
    echo "========================================="
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] SESSION #$SESSION starting"
    echo "========================================="

    # Quick stats
    python3 -c "
import json
cache = json.load(open('tmp/pairs_cache.json'))
similar = sum(1 for v in cache.values() if isinstance(v, dict) and v.get('verdict') == 'similar')
print(f'Cache: {len(cache)} total, {similar} similar ({100*similar/len(cache):.1f}%)')
" 2>/dev/null || true

    claude -p "You are continuing the LLM Similar Hadith pipeline. Read docs/llm-similar-hadith-resume.md and follow ALL steps exactly. Start from Step 1, check current state, merge any results, find uncached batches, spawn 7 agents (smallest batches first, skip known timeout batches listed in the doc), and keep looping. Keep text output to absolute minimum. Report ONE LINE status after each wave: cache/similar/remaining. Continue until all batches are cached or you run out of context." \
        --dangerously-skip-permissions \
        --model sonnet \
        --verbose 2>&1 || true

    echo "[$(date '+%Y-%m-%d %H:%M:%S')] SESSION #$SESSION ended (exit: $?)"

    # Quick completion check
    REMAINING=$(python3 -c "
import json, glob
cache = json.load(open('tmp/pairs_cache.json'))
count = 0
for d in ['tmp/batches', 'tmp/batches_new']:
    for f in glob.glob(f'{d}/batch_*.json'):
        try:
            data = json.load(open(f))
        except:
            continue
        entries = data.get('entries', []) if isinstance(data, dict) else data
        has_uncached = False
        for e in entries:
            sid = e.get('source_id', '')
            for c in e.get('uncached_candidates', e.get('candidates', [])):
                cid = c.get('id', c) if isinstance(c, dict) else c
                key = '||'.join(sorted(f'{sid}||{cid}'.split('||')))
                if key not in cache:
                    has_uncached = True
                    break
            if has_uncached:
                break
        if has_uncached:
            count += 1
print(count)
" 2>/dev/null)

    if [ "$REMAINING" = "0" ]; then
        echo "[$(date '+%Y-%m-%d %H:%M:%S')] ALL BATCHES COMPLETE!"
        break
    fi

    echo "[$(date '+%Y-%m-%d %H:%M:%S')] ~${REMAINING} batches remaining. Restarting in 5s..."
    sleep 5
done

echo "[$(date '+%Y-%m-%d %H:%M:%S')] Pipeline finished after $SESSION sessions."
