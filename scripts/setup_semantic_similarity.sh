#!/usr/bin/env bash
set -euo pipefail

ES_URL="${ES_URL:-http://127.0.0.1:9200}"
INDEX="${REWAYAAT_INDEX:-rewayaat_updated}"
INFERENCE_ID="${INFERENCE_ID:-rewayaat-multilingual-e5-large}"
MODEL_ID="${MODEL_ID:-rewayaat-multilingual-e5-large}"
DEPLOYMENT_ID="${DEPLOYMENT_ID:-}"
PIPELINE_ID="${PIPELINE_ID:-rewayaat-semantic-pipeline}"
NUM_THREADS="${NUM_THREADS:-1}"
NUM_ALLOCATIONS="${NUM_ALLOCATIONS:-1}"
START_TRIAL_IF_NEEDED="${START_TRIAL_IF_NEEDED:-true}"
WAIT_FOR_COMPLETION="${WAIT_FOR_COMPLETION:-false}"
SLICES="${SLICES:-1}"
REQUESTS_PER_SECOND="${REQUESTS_PER_SECOND:--1}"
SCROLL="${SCROLL:-30m}"
SCROLL_SIZE="${SCROLL_SIZE:-1000}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PIPELINE_SOURCE_FILE="${SCRIPT_DIR}/semantic_text_pipeline.painless"

echo "Configuring semantic similarity for index '${INDEX}' on ${ES_URL}..."

create_inference_endpoint() {
  local service_settings
  if [ -n "${DEPLOYMENT_ID}" ]; then
    service_settings="{
      \"deployment_id\": \"${DEPLOYMENT_ID}\"
    }"
  else
    service_settings="{
      \"model_id\": \"${MODEL_ID}\",
      \"num_threads\": ${NUM_THREADS},
      \"num_allocations\": ${NUM_ALLOCATIONS}
    }"
  fi

  curl -sS -X PUT "${ES_URL}/_inference/text_embedding/${INFERENCE_ID}" \
    -H 'Content-Type: application/json' \
    -d "{
      \"service\": \"elasticsearch\",
      \"service_settings\": ${service_settings}
    }"
}

if [ -n "${DEPLOYMENT_ID}" ]; then
  INFERENCE_GET_CODE="$(curl -sS -o /tmp/rewayaat-inference-get.json -w '%{http_code}' "${ES_URL}/_inference/text_embedding/${INFERENCE_ID}" || true)"
  if [ "${INFERENCE_GET_CODE}" = "200" ]; then
    INFERENCE_RESP="$(cat /tmp/rewayaat-inference-get.json)"
  else
    INFERENCE_RESP="$(create_inference_endpoint || true)"
    if echo "${INFERENCE_RESP}" | rg -q '"license\\.expired\\.feature":"inference"'; then
      if [ "${START_TRIAL_IF_NEEDED}" = "true" ]; then
        echo "Inference requires a non-basic license. Starting Elasticsearch trial..."
        curl -sS -X POST "${ES_URL}/_license/start_trial?acknowledge=true" >/dev/null
        INFERENCE_RESP="$(create_inference_endpoint || true)"
      fi
    fi
  fi

  if [ -z "${INFERENCE_RESP:-}" ]; then
    echo "Failed to contact Elasticsearch inference endpoint at ${ES_URL}."
    exit 1
  fi

  if echo "${INFERENCE_RESP}" | rg -q '"error"'; then
    echo "Failed to create inference endpoint:"
    echo "${INFERENCE_RESP}"
    exit 1
  fi

  echo "Inference endpoint '${INFERENCE_ID}' is ready."
else
  echo "Using trained model id '${INFERENCE_ID}' directly."
fi

MAPPING_RESP="$(curl -sS -X PUT "${ES_URL}/${INDEX}/_mapping" \
  -H 'Content-Type: application/json' \
  -d '{
    "properties": {
      "semantic_text": { "type": "text" },
      "semantic_matn_source": { "type": "text" },
      "semantic_english_hint_source": { "type": "text" },
      "semantic_significant_terms_source": { "type": "text" },
      "semantic_vector": {
        "type": "dense_vector",
        "dims": 1024,
        "index": true,
        "similarity": "cosine"
      }
    }
  }')"

if [ -z "${MAPPING_RESP:-}" ]; then
  echo "Failed to contact Elasticsearch mapping endpoint at ${ES_URL}."
  exit 1
fi

if echo "${MAPPING_RESP}" | rg -q '"error"'; then
  echo "Failed to update mapping:"
  echo "${MAPPING_RESP}"
  exit 1
fi

echo "Mapping updated with 'semantic_text', 'semantic_matn_source', 'semantic_english_hint_source', 'semantic_significant_terms_source', and 'semantic_vector'."

PIPELINE_SOURCE="$(python3 - <<'PY' "${PIPELINE_SOURCE_FILE}"
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
print(json.dumps(path.read_text(encoding="utf-8")))
PY
)"

PIPELINE_RESP="$(curl -sS -X PUT "${ES_URL}/_ingest/pipeline/${PIPELINE_ID}" \
  -H 'Content-Type: application/json' \
  -d "{
    \"processors\": [
      {
        \"script\": {
          \"source\": ${PIPELINE_SOURCE}
        }
      },
      {
        \"remove\": {
          \"field\": \"semantic_vector\",
          \"ignore_missing\": true
        }
      },
      {
        \"inference\": {
          \"model_id\": \"${INFERENCE_ID}\",
          \"input_output\": [
            { \"input_field\": \"semantic_text\", \"output_field\": \"semantic_vector\" }
          ],
          \"ignore_missing\": true
        }
      }
    ]
  }")"

if [ -z "${PIPELINE_RESP:-}" ]; then
  echo "Failed to contact Elasticsearch ingest endpoint at ${ES_URL}."
  exit 1
fi

if echo "${PIPELINE_RESP}" | rg -q '"error"'; then
  echo "Failed to create ingest pipeline:"
  echo "${PIPELINE_RESP}"
  exit 1
fi

echo "Pipeline '${PIPELINE_ID}' created."

UPDATE_RESP="$(curl -sS -X POST "${ES_URL}/${INDEX}/_update_by_query?pipeline=${PIPELINE_ID}&conflicts=proceed&wait_for_completion=${WAIT_FOR_COMPLETION}&slices=${SLICES}&requests_per_second=${REQUESTS_PER_SECOND}&scroll=${SCROLL}&scroll_size=${SCROLL_SIZE}" \
  -H 'Content-Type: application/json' \
  -d '{
    "query": {
      "match_all": {}
    }
  }')"

if [ -z "${UPDATE_RESP:-}" ]; then
  echo "Failed to contact Elasticsearch update-by-query endpoint at ${ES_URL}."
  exit 1
fi

if echo "${UPDATE_RESP}" | rg -q '"error"'; then
  echo "Failed to start/update semantic backfill:"
  echo "${UPDATE_RESP}"
  exit 1
fi

if [ "${WAIT_FOR_COMPLETION}" = "true" ]; then
  echo "Backfill completed."
else
  TASK_ID="$(python3 - <<'PY'
import json, sys
raw = sys.stdin.read().strip()
if not raw:
    print("")
    raise SystemExit
payload = json.loads(raw)
print(payload.get("task", ""))
PY
<<<"${UPDATE_RESP}")"
  if [ -n "${TASK_ID}" ]; then
    echo "Backfill started asynchronously. Task: ${TASK_ID}"
    echo "Track progress: curl -s '${ES_URL}/_tasks/${TASK_ID}?pretty'"
  else
    echo "Backfill started."
  fi
fi

VECTOR_COUNT="$(curl -sS -X POST "${ES_URL}/${INDEX}/_count" \
  -H 'Content-Type: application/json' \
  -d '{"query":{"exists":{"field":"semantic_vector"}}}' | python3 - <<'PY'
import json, sys
payload = json.loads(sys.stdin.read() or "{}")
print(payload.get("count", 0))
PY
)"

echo "Documents currently containing semantic vectors: ${VECTOR_COUNT}"
echo "Semantic setup is complete."
