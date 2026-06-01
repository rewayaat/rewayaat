#!/usr/bin/env bash
set -euo pipefail

cd /home/zir0/git/rewayaat

export REWAYAAT_INDEX="${REWAYAAT_INDEX:-rewayaat_thaqalayn_20260320}"
export ELASTIC_HOST="${ELASTIC_HOST:-127.0.0.1}"
export ELASTIC_PORT="${ELASTIC_PORT:-9200}"
export TOPIC_TAGS_FORCE="${TOPIC_TAGS_FORCE:-true}"
export TOPIC_TAGS_USE_AI="${TOPIC_TAGS_USE_AI:-true}"
export TOPIC_TAGS_CLASSIFIER_MODE="${TOPIC_TAGS_CLASSIFIER_MODE:-ai_refine_all}"
export TOPIC_TAGS_AI_AGENT_URL="${TOPIC_TAGS_AI_AGENT_URL:-https://mpxss7h5zioonghjbrcog7za.agents.do-ai.run/api/v1/chat/completions}"
export TOPIC_TAGS_AI_AGENT_KEY="${TOPIC_TAGS_AI_AGENT_KEY:-}"
export TOPIC_TAGS_AI_BATCH_SIZE="${TOPIC_TAGS_AI_BATCH_SIZE:-5}"
export TOPIC_TAGS_AI_TEMPERATURE="${TOPIC_TAGS_AI_TEMPERATURE:-0}"
export TOPIC_TAGS_AI_SEND_REASONING_EFFORT="${TOPIC_TAGS_AI_SEND_REASONING_EFFORT:-false}"
export TOPIC_TAGS_AI_MAX_COMPLETION_TOKENS="${TOPIC_TAGS_AI_MAX_COMPLETION_TOKENS:-0}"
export TOPIC_TAGS_AI_MAX_PROMPT_TOKENS="${TOPIC_TAGS_AI_MAX_PROMPT_TOKENS:-16000}"
export TOPIC_TAGS_PROGRESS_EVERY="${TOPIC_TAGS_PROGRESS_EVERY:-10}"
export TOPIC_TAGS_CHECKPOINT_INTERVAL="${TOPIC_TAGS_CHECKPOINT_INTERVAL:-10}"
export TOPIC_TAGS_CHECKPOINT_FILE="${TOPIC_TAGS_CHECKPOINT_FILE:-/tmp/topic-tags-backfill-hadith-v15.json}"
export TOPIC_TAGS_AI_PROPOSAL_DEBUG_FILE="${TOPIC_TAGS_AI_PROPOSAL_DEBUG_FILE:-/tmp/topic-tags-ai-proposals.log}"
export TOPIC_TAGS_PROPOSAL_TAXONOMY_FILE="${TOPIC_TAGS_PROPOSAL_TAXONOMY_FILE:-/home/zir0/git/rewayaat/src/main/resources/static/taxonomy.proposals.json}"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export PATH="$JAVA_HOME/bin:$PATH"

exec "$JAVA_HOME/bin/java" -cp "target/classes:$(cat /tmp/rewayaat.cp)" com.rewayaat.tools.TopicTagsBackfillTool
