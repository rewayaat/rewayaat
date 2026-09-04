#!/usr/bin/env python3
"""Generate semantic embeddings for all hadith using multilingual-e5-large.

Uses sentence-transformers locally (no ES inference dependency).
Includes topic tags in the embedding input for better topical clustering.
Bulk imports 1024-dim vectors into Elasticsearch.

Usage:
  python3 scripts/embeddings/generate_hadith_embeddings.py
  python3 scripts/embeddings/generate_hadith_embeddings.py --model intfloat/multilingual-e5-large
  python3 scripts/embeddings/generate_hadith_embeddings.py --batch-size 64 --force
  python3 scripts/embeddings/generate_hadith_embeddings.py --eval tmp/eval/similar_hadith_eval.json
"""
import argparse
import json
import subprocess
import sys
import tempfile
import os
import time
from pathlib import Path


def es_request(es_url, method, path, data=None, content_type="application/json", timeout=300):
    import requests
    url = f"{es_url.rstrip('/')}/{path.lstrip('/')}"
    headers = {"Content-Type": content_type}
    resp = requests.request(method, url, data=data, headers=headers, timeout=timeout)
    return resp.json() if resp.text.strip() else {}


def scroll_all(es_url, index, fields, batch_size=1000):
    """Scroll through all documents and yield batches."""
    payload = {
        "size": batch_size,
        "sort": ["_doc"],
        "_source": fields,
        "query": {"match_all": {}}
    }
    resp = es_request(es_url, "POST", f"/{index}/_search?scroll=5m",
                      json.dumps(payload).encode())
    scroll_id = resp.get("_scroll_id", "")

    while True:
        hits = resp.get("hits", {}).get("hits", [])
        if not hits:
            break
        yield hits
        resp = es_request(es_url, "POST", "/_search/scroll",
                          json.dumps({"scroll": "5m", "scroll_id": scroll_id}).encode())
        scroll_id = resp.get("_scroll_id", scroll_id)

    # Clear scroll
    try:
        es_request(es_url, "DELETE", "/_search/scroll",
                   json.dumps({"scroll_id": scroll_id}).encode())
    except Exception:
        pass


def build_embedding_text(source):
    """Build the embedding input text from document fields.

    Must match the format used by export_hadith_for_embeddings.py so
    training and inference text are aligned.
    """
    matn = (source.get("semantic_matn_source") or "").strip()
    if not matn:
        return ""

    matn = matn.replace("<", " ").replace(">", " ")
    for honorific in ["عليه السلام", "عليهما السلام", "عليهم السلام",
                      "صلّى الله عليه وآله", "صلى الله عليه وآله", "رحمه الله"]:
        matn = matn.replace(honorific, " ")

    while "  " in matn:
        matn = matn.replace("  ", " ")
    matn = matn.strip()
    while matn and matn[0] in " -:;,.،":
        matn = matn[1:].strip()
    if len(matn) > 3800:
        matn = matn[:3800].strip()
    if not matn:
        return ""

    body = matn

    english_hint = (source.get("semantic_english_hint_source") or "").strip()
    if english_hint:
        if len(english_hint) > 300:
            english_hint = english_hint[:300]
            for sep in [". ", "? ", "! "]:
                idx = english_hint.rfind(sep)
                if idx > 50:
                    english_hint = english_hint[:idx + 1]
                    break
            else:
                last_space = english_hint.rfind(" ")
                if last_space > 50:
                    english_hint = english_hint[:last_space]
        if english_hint:
            body += f" || {english_hint}"

    return body


def generate_embeddings(texts, model, batch_size):
    """Generate embeddings using sentence-transformers."""
    return model.encode(texts, batch_size=batch_size, show_progress_bar=False,
                        normalize_embeddings=True)


def bulk_update_vectors(es_url, index, ids, vectors):
    """Bulk update semantic_vector in ES."""
    lines = []
    for doc_id, vector in zip(ids, vectors):
        lines.append(json.dumps({"update": {"_index": index, "_id": doc_id}}))
        lines.append(json.dumps({"doc": {"semantic_vector": vector.tolist()}}))

    data = ("\n".join(lines) + "\n").encode("utf-8")
    resp = es_request(es_url, "POST", "/_bulk", data,
                      content_type="application/x-ndjson")

    if not resp.get("items"):
        print(f"  WARN: bulk returned no items! resp keys={list(resp.keys())}, errors={resp.get('error')}")

    errors = []
    for item in resp.get("items", []):
        result = item.get("update", {})
        if result.get("error"):
            errors.append(f"{result.get('_id')}: {result['error']}")

    return len(ids) - len(errors), errors


def ensure_mapping(es_url, index):
    """Ensure semantic_vector dense_vector field exists."""
    mapping = {
        "properties": {
            "semantic_vector": {
                "type": "dense_vector",
                "dims": 1024,
                "index": True,
                "similarity": "cosine"
            }
        }
    }
    try:
        es_request(es_url, "PUT", f"/{index}/_mapping",
                   json.dumps(mapping).encode())
        print("Mapping updated with semantic_vector (1024-dim, cosine)")
    except Exception as e:
        print(f"Mapping update note: {e}")


def run_eval(es_url, index, eval_path, model, embed_batch_size):
    """Evaluate embedding quality on the eval set."""
    eval_data = json.loads(Path(eval_path).read_text())
    positive_pairs = eval_data.get("positive_pairs", [])
    negative_pairs = eval_data.get("negative_pairs", [])

    # Collect all unique IDs
    all_ids = set()
    for pair in positive_pairs + negative_pairs:
        all_ids.add(pair["id_a"])
        all_ids.add(pair["id_b"])

    print(f"\nEval: loading {len(all_ids)} docs for {len(positive_pairs)} positive, {len(negative_pairs)} negative pairs")

    # Fetch documents
    id_list = list(all_ids)
    docs = {}
    for i in range(0, len(id_list), 500):
        chunk = id_list[i:i+500]
        payload = {"size": len(chunk), "query": {"ids": {"values": chunk}},
                   "_source": ["semantic_matn_source", "arabic", "semantic_english_hint_source",
                               "topic_tags", "semantic_significant_terms_source"]}
        resp = es_request(es_url, "POST", f"/{index}/_search",
                          json.dumps(payload).encode())
        for hit in resp.get("hits", {}).get("hits", []):
            docs[hit["_id"]] = hit["_source"]

    print(f"Eval: fetched {len(docs)} docs")

    # Generate embeddings
    texts = []
    ordered_ids = []
    for doc_id in all_ids:
        source = docs.get(doc_id, {})
        text = build_embedding_text(source)
        if text:
            texts.append(text)
            ordered_ids.append(doc_id)

    print(f"Eval: generating embeddings for {len(texts)} docs")
    embeddings = generate_embeddings(texts, model, embed_batch_size)
    emb_map = {doc_id: embeddings[i] for i, doc_id in enumerate(ordered_ids)}

    # Compute cosine similarities (embeddings are already normalized)
    import numpy as np

    pos_sims = []
    for pair in positive_pairs:
        a, b = emb_map.get(pair["id_a"]), emb_map.get(pair["id_b"])
        if a is not None and b is not None:
            sim = float(np.dot(a, b))
            pos_sims.append(sim)

    neg_sims = []
    for pair in negative_pairs:
        a, b = emb_map.get(pair["id_a"]), emb_map.get(pair["id_b"])
        if a is not None and b is not None:
            sim = float(np.dot(a, b))
            neg_sims.append(sim)

    if not pos_sims or not neg_sims:
        print("Eval: not enough pairs to evaluate")
        return

    # Compute metrics
    pos_mean = np.mean(pos_sims)
    neg_mean = np.mean(neg_sims)
    gap = pos_mean - neg_mean

    # Precision@k: fraction of positive pairs above threshold
    thresholds = [0.60, 0.65, 0.70, 0.75, 0.80]
    print(f"\n=== Embedding Quality Evaluation ===")
    print(f"Positive pairs: {len(pos_sims)}, mean similarity: {pos_mean:.4f}")
    print(f"Negative pairs: {len(neg_sims)}, mean similarity: {neg_mean:.4f}")
    print(f"Gap (pos - neg): {gap:.4f}")
    print(f"\nSimilarity distribution:")
    print(f"  Positive: min={min(pos_sims):.4f}, median={np.median(pos_sims):.4f}, max={max(pos_sims):.4f}")
    print(f"  Negative: min={min(neg_sims):.4f}, median={np.median(neg_sims):.4f}, max={max(neg_sims):.4f}")
    print(f"\nPrecision (positive pair above threshold):")
    for t in thresholds:
        precision = sum(1 for s in pos_sims if s >= t) / len(pos_sims)
        print(f"  @{t:.2f}: {precision:.2%}")

    # Also eval by positive pair type
    for reason in ["cross-collection parallel", "same topic", "variant narration"]:
        reason_sims = []
        for pair in positive_pairs:
            if pair.get("reason") == reason:
                a, b = emb_map.get(pair["id_a"]), emb_map.get(pair["id_b"])
                if a is not None and b is not None:
                    reason_sims.append(float(np.dot(a, b)))
        if reason_sims:
            print(f"\n  {reason}: mean={np.mean(reason_sims):.4f}, n={len(reason_sims)}")

    # Separability: what threshold maximizes accuracy
    best_acc = 0
    best_t = 0
    for t_int in range(40, 95):
        t = t_int / 100.0
        tp = sum(1 for s in pos_sims if s >= t)
        tn = sum(1 for s in neg_sims if s < t)
        acc = (tp + tn) / (len(pos_sims) + len(neg_sims))
        if acc > best_acc:
            best_acc = acc
            best_t = t
    print(f"\nBest threshold: {best_t:.2f} (accuracy: {best_acc:.2%})")
    print(f"======================================")


def main():
    parser = argparse.ArgumentParser(description="Generate hadith embeddings")
    parser.add_argument("--es-url", default="http://localhost:9200")
    parser.add_argument("--index", default="rewayaat_updated")
    parser.add_argument("--model", default="intfloat/multilingual-e5-large",
                        help="Sentence-transformers model name")
    parser.add_argument("--batch-size", type=int, default=64,
                        help="Embedding batch size")
    parser.add_argument("--scroll-size", type=int, default=1000,
                        help="ES scroll batch size")
    parser.add_argument("--force", action="store_true",
                        help="Regenerate embeddings even if already present")
    parser.add_argument("--eval", default=None,
                        help="Path to eval set JSON (runs eval only, no embedding generation)")
    parser.add_argument("--skip-existing", action="store_true", default=True,
                        help="Skip docs that already have semantic_vector")
    args = parser.parse_args()

    # Force unbuffered output
    sys.stdout.reconfigure(line_buffering=True)

    # Limit resource usage
    import torch
    torch.set_num_threads(2)
    torch.set_num_interop_threads(1)
    os.environ["PYTORCH_CUDA_ALLOC_CONF"] = ""  # no GPU
    import gc
    gc.set_threshold(700, 10, 10)  # more aggressive GC

    # Load model
    print(f"Loading model: {args.model}")
    from sentence_transformers import SentenceTransformer
    model = SentenceTransformer(args.model)
    print(f"Model loaded. Dimensions: {model.get_sentence_embedding_dimension()}")

    # If the saved model has LoRA weights (PEFT format), merge them
    auto_model = model[0].auto_model
    model_type = type(auto_model).__name__
    if model_type == 'PeftModel':
        model[0].auto_model = auto_model.merge_and_unload()
        print("LoRA weights merged.")
    elif model_type == 'XLMRobertaModel':
        # Check if saved with PEFT naming but no adapter_config (needs manual merge)
        import safetensors.torch as st
        import torch
        safetensors_path = Path(args.model) / "model.safetensors"
        if safetensors_path.exists():
            state = st.load_file(safetensors_path)
            if any("lora_A" in k for k in state.keys()):
                print("  Detected unmerged LoRA weights, merging...")
                r = 16
                alpha = 32
                scaling = alpha / r
                new_state = {}
                merged_count = 0
                for key, tensor in state.items():
                    if "lora_A.default.weight" in key or "lora_B.default.weight" in key:
                        continue
                    if "base_layer" in key:
                        base_key = key.replace(".base_layer", "")
                        if "base_layer.weight" in key:
                            a_key = key.replace("base_layer.weight", "lora_A.default.weight")
                            b_key = key.replace("base_layer.weight", "lora_B.default.weight")
                            if a_key in state and b_key in state:
                                merged = tensor + (state[b_key] @ state[a_key]) * scaling
                                new_state[base_key] = merged
                                merged_count += 1
                                continue
                        new_state[base_key] = tensor
                    else:
                        new_state[key] = tensor
                print(f"  Merged {merged_count} LoRA pairs")
                # Backup and save
                backup = safetensors_path.with_suffix(".safetensors.bak")
                if not backup.exists():
                    import shutil
                    shutil.copy2(safetensors_path, backup)
                st.save_file(new_state, str(safetensors_path))
                # Reload model with merged weights
                model = SentenceTransformer(args.model)
                print("  Reloaded model with merged weights.")
        print("Model ready.")

    # Eval-only mode
    if args.eval:
        run_eval(args.es_url, args.index, args.eval, model, args.batch_size)
        return

    # Ensure mapping
    ensure_mapping(args.es_url, args.index)

    # Collect documents needing embeddings
    fields = ["semantic_matn_source", "arabic", "semantic_english_hint_source",
              "topic_tags", "semantic_significant_terms_source", "semantic_vector"]

    print(f"Scanning {args.index} for documents needing embeddings...")
    docs_to_embed = []
    total_scanned = 0

    for hits in scroll_all(args.es_url, args.index, fields, args.scroll_size):
        for hit in hits:
            total_scanned += 1
            source = hit["_source"]

            if not args.force:
                existing = source.get("semantic_vector")
                if existing:
                    continue

            text = build_embedding_text(source)
            if text:
                docs_to_embed.append({"id": hit["_id"], "text": text})

        if total_scanned % 5000 == 0:
            print(f"  Scanned {total_scanned}, queued {len(docs_to_embed)}")

    print(f"Total scanned: {total_scanned}, needing embeddings: {len(docs_to_embed)}")

    if not docs_to_embed:
        print("All documents already have embeddings. Done!")
        if args.eval:
            pass  # eval is run separately
        return

    # Pre-extract texts/ids and free the dict list to reduce memory pressure
    all_texts = [d["text"] for d in docs_to_embed]
    all_ids = [d["id"] for d in docs_to_embed]
    del docs_to_embed
    gc.collect()

    # Generate embeddings and bulk update
    total_updated = 0
    total_failed = 0
    start_time = time.time()

    for i in range(0, len(all_texts), args.batch_size):
        texts = all_texts[i:i + args.batch_size]
        ids = all_ids[i:i + args.batch_size]

        try:
            embeddings = generate_embeddings(texts, model, args.batch_size)
            updated, errors = bulk_update_vectors(args.es_url, args.index, ids, embeddings)
            total_updated += updated
            total_failed += len(errors)
            if i == 0:
                # Debug first batch
                print(f"  DEBUG first batch: {len(ids)} ids, {updated} updated, {len(errors)} errors")
                print(f"  DEBUG first 3 ids: {ids[:3]}")
                if errors:
                    for e in errors[:3]:
                        print(f"  DEBUG error: {e}")
            del embeddings
            import gc; gc.collect()
            # Periodic heavy cleanup every 10 batches
            if (i // args.batch_size) % 10 == 0:
                import torch
                if torch.cuda.is_available():
                    torch.cuda.empty_cache()
                gc.collect()
        except Exception as e:
            print(f"  Batch {i}-{i+len(batch)} failed: {e}")
            total_failed += len(batch)

        # Periodic ES refresh to ensure data is flushed
        if total_updated % 500 == 0 and total_updated > 0:
            try:
                es_request(args.es_url, "POST", f"/{args.index}/_refresh")
            except Exception:
                pass

        elapsed = time.time() - start_time
        rate = total_updated / elapsed if elapsed > 0 else 0
        remaining = (len(all_texts) - i - len(texts)) / rate if rate > 0 else 0
        print(f"  Progress: {total_updated}/{len(all_texts)} "
              f"({total_updated/len(all_texts)*100:.1f}%) "
              f"rate={rate:.0f}/s eta={remaining/60:.1f}m")

    elapsed = time.time() - start_time
    print(f"\nDone. Updated {total_updated}/{len(all_texts)} in {elapsed:.0f}s")
    if total_failed:
        print(f"Failed: {total_failed}")


if __name__ == "__main__":
    main()
