#!/bin/bash
# Tag Migration Wrapper Script
# Runs the complete tag migration process

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
INDEX_NAME=${REWAYAAT_INDEX:-syn_v1}
DRY_RUN=${TAG_MIGRATION_DRY_RUN:-false}

echo "========================================"
echo "Rewayaat Tag Migration"
echo "========================================"
echo "Index: $INDEX_NAME"
echo "Dry run: $DRY_RUN"
echo ""

# Step 0: Create snapshot (unless dry run)
if [[ "$DRY_RUN" != "true" ]]; then
    echo "Step 0: Creating snapshot backup..."
    "$SCRIPT_DIR/create_snapshot.sh" "$INDEX_NAME"
    echo ""
else
    echo "Step 0: Skipping snapshot (dry run mode)"
    echo ""
fi

# Step 1 & 2: Already done (taxonomy.json and seed terms updated)
echo "Step 1-2: Taxonomy already updated (204 tags)"
echo ""

# Step 3: Run ES migration (remap/strip tags)
echo "Step 3: Running ES tag migration..."
cd "$PROJECT_ROOT"
mvn exec:java -Dexec.mainClass="com.rewayaat.tools.TagMigrationTool" \
    -DREWAYAAT_INDEX="$INDEX_NAME" \
    -DTAG_MIGRATION_DRY_RUN="$DRY_RUN"
echo ""

# Step 4: Re-tag orphaned hadith
if [[ -f "/tmp/tag_migration_retag_queue.json" ]]; then
    echo "Step 4: Re-tagging orphaned hadith..."
    # Extract hadith IDs and run TopicTagsBackfillTool
    HADITH_IDS=$(jq -r '.hadith_ids[]' /tmp/tag_migration_retag_queue.json | tr '\n' ',' | sed 's/,$//')

    if [[ -n "$HADITH_IDS" && "$HADITH_IDS" != "," ]]; then
        echo "Found hadith to re-tag: $(jq '.hadith_ids | length' /tmp/tag_migration_retag_queue.json)"

        mvn exec:java -Dexec.mainClass="com.rewayaat.tools.TopicTagsBackfillTool" \
            -DREWAYAAT_INDEX="$INDEX_NAME" \
            -DTOPIC_TAGS_CLASSIFIER_MODE="ai_refine_all" \
            -DTOPIC_TAGS_USE_AI="true" \
            -DTOPIC_TAGS_DRY_RUN="$DRY_RUN"
    else
        echo "No hadith to re-tag"
    fi
    echo ""
else
    echo "Step 4: No orphaned hadith to re-tag"
    echo ""
fi

# Step 5: Parent tag inference
echo "Step 5: Parent tag inference..."
# This would be implemented as a separate tool or step
echo "TODO: Implement parent tag inference"
echo ""

echo "========================================"
echo "Migration complete!"
echo "========================================"
