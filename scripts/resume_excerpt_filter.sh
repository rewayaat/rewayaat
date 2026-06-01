#!/bin/bash
# Resume excerpt-highlight filtering from where we left off.
# Usage: Run this in Claude Code, then paste the prompt below.
#
# Step 1: Check progress
#   python3 scripts/check_excerpt_progress.py
#
# Step 2: In Claude Code, say:
#   "Continue excerpt highlighting. Check tmp/qlight-excerpts/ for what's done,
#    spawn 8 agents for the next missing batches using the prompt in
#    docs/excerpt-agent-prompt.txt, and keep going until all done."

echo "=== Excerpt Highlight Progress ==="
python3 scripts/check_excerpt_progress.py
echo ""
echo "To resume in Claude Code, say:"
echo "  'Continue excerpt highlighting from where we left off.'"
