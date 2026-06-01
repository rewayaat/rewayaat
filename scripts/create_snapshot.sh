#!/bin/bash
# Create ES snapshot before tag migration
# Usage: ./create_snapshot.sh [index_name]

INDEX_NAME=${1:-syn_v1}
SNAPSHOT_NAME="pre-tag-migration-$(date +%Y%m%d_%H%M%S)"
REPO_NAME="backups"

echo "Creating snapshot for index: $INDEX_NAME"
echo "Snapshot name: $SNAPSHOT_NAME"
echo "Repository: $REPO_NAME"

# First check if repository exists
echo "Checking for snapshot repository..."
REPO_CHECK=$(curl -s "http://${ELASTIC_HOST:-localhost}:${ELASTIC_PORT:-9200}/_snapshot/${REPO_NAME}" | jq -r '. // "error"')

if [[ "$REPO_CHECK" == "error" ]]; then
    echo "ERROR: Snapshot repository '${REPO_NAME}' not found."
    echo "Please create it first with:"
    echo "curl -X PUT \"http://${ELASTIC_HOST:-localhost}:${ELASTIC_PORT:-9200}/_snapshot/${REPO_NAME}\" -H 'Content-Type: application/json' -d '{"
    echo "  \"type\": \"fs\","
    echo "  \"settings\": {"
    echo "    \"location\": \"/mnt/es/backups\""
    echo "  }"
    echo "}'"
    exit 1
fi

# Create snapshot
echo "Creating snapshot..."
curl -X PUT "http://${ELASTIC_HOST:-localhost}:${ELASTIC_PORT:-9200}/_snapshot/${REPO_NAME}/${SNAPSHOT_NAME}" \
  -H 'Content-Type: application/json' \
  -d "{
    \"indices\": \"${INDEX_NAME}\",
    \"include_global_state\": false,
    \"metadata\": {
      \"description\": \"Pre-tag migration backup for ${INDEX_NAME}\",
      \"taken_by\": \"TagMigrationTool\",
      \"taken_at\": \"$(date -Iseconds)\"
    }
  }" | jq '.'

echo ""
echo "Snapshot created: ${SNAPSHOT_NAME}"
echo "To restore: curl -X POST \"http://${ELASTIC_HOST:-localhost}:${ELASTIC_PORT:-9200}/_snapshot/${REPO_NAME}/${SNAPSHOT_NAME}/_restore\" -H 'Content-Type: application/json' -d '{\"indices\": \"${INDEX_NAME}\"}'"
