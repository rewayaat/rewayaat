# Similar Hadith e5-large Rollout

This rollout upgrades similar-hadith embeddings from `384`-dim `.multilingual-e5-small` to `1024`-dim `intfloat/multilingual-e5-large`.

## Why A New Index Is Required

`semantic_vector.dims` cannot be changed in place. Moving from `384` to `1024` requires:

1. Creating a new index.
2. Copying documents into it without the old `semantic_vector`.
3. Rebuilding semantic source fields.
4. Re-embedding documents with the new model.
5. Cutting traffic over to the new index.

## Current Target Index

The codebase now points by default to:

`rewayaat_updated_e5large_20260320`

You can still override it with:

```bash
export REWAYAAT_INDEX=rewayaat_updated_e5large_20260320
```

## Preconditions

The target Elasticsearch cluster must have inference available.

For a self-managed cluster, `multilingual-e5-large` is not a built-in package model. Upload it first with Eland under a custom model id such as `rewayaat-multilingual-e5-large`.

The model is large enough that a small single-node Docker instance may fail to deploy it. In local validation, a node with roughly `8 GB` total system memory and the default `30%` ML memory cap was not enough to start the deployment reliably. Size the ML capacity accordingly before cutover.

Check:

```bash
curl -s http://localhost:9200/_inference/text_embedding/rewayaat-semantic
curl -s -X POST http://localhost:9200/_inference/text_embedding/rewayaat-semantic \
  -H 'Content-Type: application/json' \
  -d '{"input":["passage: الماء طاهر"]}'
```

If inference is unavailable or unlicensed, vector generation will not work.

## 1. Create The New Index

Clone the old index settings/mapping, but set:

- `semantic_vector.dims = 1024`
- `semantic_english_hint_source` present as a text field

If you already have a clean target index, skip this.

## 2. Copy Documents Without Old Vectors

Do not copy `semantic_vector` from the old index.

```bash
curl -s -X POST 'http://localhost:9200/_reindex?wait_for_completion=true&refresh=true' \
  -H 'Content-Type: application/json' \
  -d '{
    "source": {
      "index": "rewayaat_updated",
      "_source": {
        "excludes": ["semantic_vector"]
      }
    },
    "dest": {
      "index": "rewayaat_updated_e5large_20260320",
      "op_type": "create"
    }
  }'
```

## 3. Backfill Semantic Source Fields

Build the semantic matn source and English hint:

```bash
mvn -B -q -DskipTests dependency:build-classpath -Dmdep.outputFile=/tmp/rewayaat.cp

REWAYAAT_INDEX=rewayaat_updated_e5large_20260320 \
java -cp "target/classes:$(cat /tmp/rewayaat.cp)" \
  com.rewayaat.tools.SemanticMatnSourceBackfillTool
```

Then rebuild significant terms:

```bash
REWAYAAT_INDEX=rewayaat_updated_e5large_20260320 \
java -cp "target/classes:$(cat /tmp/rewayaat.cp)" \
  com.rewayaat.tools.SemanticSignificantTermsBackfillTool
```

## 4. Upload The Model, Recreate The Inference Endpoint, And Embed

On a self-managed cluster, upload the model first:

```bash
docker run --rm --add-host=host.docker.internal:host-gateway \
  docker.elastic.co/eland/eland \
  eland_import_hub_model \
  --url http://host.docker.internal:9200 \
  --hub-model-id intfloat/multilingual-e5-large \
  --task-type text_embedding \
  --es-model-id rewayaat-multilingual-e5-large
```

If the deployment node is constrained, raise the ML memory cap before starting the model:

```bash
curl -s -X PUT http://localhost:9200/_cluster/settings \
  -H 'Content-Type: application/json' \
  -d '{"persistent":{"xpack.ml.max_machine_memory_percent":50}}'
```

If you are using a custom inference endpoint, delete the old one if it still points to the small model:

```bash
curl -s -X DELETE http://localhost:9200/_inference/text_embedding/rewayaat-semantic
```

Run the semantic setup script against the new index:

```bash
REWAYAAT_INDEX=rewayaat_updated_e5large_20260320 \
WAIT_FOR_COMPLETION=true \
bash scripts/setup_semantic_similarity.sh
```

That script now defaults to:

- `MODEL_ID=rewayaat-multilingual-e5-large`
- `INFERENCE_ID=rewayaat-multilingual-e5-large`
- `semantic_vector.dims=1024`

## 5. Validate Before Cutover

Check inference endpoint:

```bash
curl -s http://localhost:9200/_inference/text_embedding/rewayaat-semantic
```

Check vector count:

```bash
curl -s http://localhost:9200/rewayaat_updated_e5large_20260320/_count \
  -H 'Content-Type: application/json' \
  -d '{"query":{"exists":{"field":"semantic_vector"}}}'
```

Check one document:

```bash
curl -s http://localhost:9200/rewayaat_updated_e5large_20260320/_search \
  -H 'Content-Type: application/json' \
  -d '{
    "size": 1,
    "_source": [
      "semantic_text",
      "semantic_matn_source",
      "semantic_english_hint_source",
      "semantic_significant_terms_source",
      "semantic_vector"
    ],
    "query": { "exists": { "field": "semantic_vector" } }
  }'
```

Smoke-test the app:

```bash
curl -s 'http://localhost:8080/v1/narrations/similar?id=Al-Kafi-Volume-1-Kulayni:1&per_page=5'
```

## 6. Cutover

Preferred: use an alias so future index migrations do not require code changes.

Example alias swap:

```bash
curl -s -X POST http://localhost:9200/_aliases \
  -H 'Content-Type: application/json' \
  -d '{
    "actions": [
      { "remove": { "index": "rewayaat_updated", "alias": "rewayaat_live", "ignore_unavailable": true } },
      { "remove": { "index": "rewayaat_updated_e5large_20260320", "alias": "rewayaat_live", "ignore_unavailable": true } },
      { "add":    { "index": "rewayaat_updated_e5large_20260320", "alias": "rewayaat_live" } }
    ]
  }'
```

Then point the app to:

```bash
export REWAYAAT_INDEX=rewayaat_live
```

If you are not using an alias, point the app directly at:

```bash
export REWAYAAT_INDEX=rewayaat_updated_e5large_20260320
```

## 7. Rollback

If the new index misbehaves:

1. switch the app back to the old index or alias target
2. restart the app
3. inspect the new index offline

Alias rollback:

```bash
curl -s -X POST http://localhost:9200/_aliases \
  -H 'Content-Type: application/json' \
  -d '{
    "actions": [
      { "remove": { "index": "rewayaat_updated_e5large_20260320", "alias": "rewayaat_live", "ignore_unavailable": true } },
      { "add":    { "index": "rewayaat_updated", "alias": "rewayaat_live" } }
    ]
  }'
```

## Notes From Local Validation

On the local machine used for this change:

- code changes were completed
- `mvn -B test` passed
- the new target index was created and populated without old vectors
- semantic source fields were backfilled
- actual vector generation was blocked because Elasticsearch inference was not licensed/available

That last point is operational, not an application-code defect.
