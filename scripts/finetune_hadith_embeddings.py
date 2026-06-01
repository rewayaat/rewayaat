#!/usr/bin/env python3
"""Fine-tune multilingual-e5-large with LoRA for hadith similarity.

Uses PEFT/LoRA to reduce memory from ~12GB to ~4GB.
Manual training loop — no HuggingFace Trainer overhead.

Usage:
  python3 scripts/finetune_hadith_embeddings.py --eval-baseline
  python3 scripts/finetune_hadith_embeddings.py --eval-baseline --epochs 2
"""

import argparse
import json
import os
import sys
import gc
from pathlib import Path

import numpy as np


def build_embedding_text(source, include_topics=False):
    """Build embedding text from hadith fields.

    include_topics: False for training (prevents shortcut learning).
    Production embedding generation in generate_hadith_embeddings.py includes topics.
    """
    matn = (source.get("semantic_matn_source") or source.get("matn") or "").strip()
    if not matn:
        matn = (source.get("arabic") or "").strip()
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
    english_hint = (source.get("semantic_english_hint_source") or
                    source.get("english") or "").strip()[:120]
    if english_hint:
        body += f" || en_hint: {english_hint}"
    topic_tags = source.get("topic_tags") or []
    if include_topics and topic_tags:
        readable_tags = [t.replace("-", " ") for t in topic_tags[:8]]
        body += f"|| topics: {', '.join(readable_tags)}"
    terms = (source.get("semantic_significant_terms_source") or "").strip()
    if terms:
        body += f"|| key_terms: {terms}"
    return f"passage: {body}"


def load_pairs(split_path, es_url, index):
    """Load pairs from a split file and fetch corresponding hadith text from ES."""
    import requests

    data = json.loads(Path(split_path).read_text(encoding="utf-8"))
    pairs = data.get("pairs", [])

    all_ids = set()
    for p in pairs:
        all_ids.add(p["id_a"])
        all_ids.add(p["id_b"])

    print(f"Loading {len(pairs)} pairs ({len(all_ids)} unique hadith)...")
    docs = {}
    id_list = list(all_ids)
    for i in range(0, len(id_list), 500):
        chunk = id_list[i:i+500]
        payload = {
            "size": len(chunk),
            "query": {"ids": {"values": chunk}},
            "_source": ["semantic_matn_source", "arabic", "english",
                        "semantic_english_hint_source", "topic_tags",
                        "semantic_significant_terms_source"]
        }
        resp = requests.post(
            f"{es_url}/{index}/_search",
            json=payload, headers={"Content-Type": "application/json"}, timeout=60)
        for hit in resp.json().get("hits", {}).get("hits", []):
            docs[hit["_id"]] = hit["_source"]

    print(f"  Fetched {len(docs)} hadith documents")

    texts_a, texts_b, labels, pair_types = [], [], [], []
    skipped = 0
    for p in pairs:
        text_a = build_embedding_text(docs.get(p["id_a"], {}))
        text_b = build_embedding_text(docs.get(p["id_b"], {}))
        if not text_a or not text_b:
            skipped += 1
            continue
        texts_a.append(text_a)
        texts_b.append(text_b)
        labels.append(p["label"])
        pair_types.append(p.get("pair_type", "unknown"))

    print(f"  {len(texts_a)} valid pairs, {skipped} skipped")
    return texts_a, texts_b, labels, pair_types


def evaluate(model, texts_a, texts_b, labels, pair_types, batch_size=32):
    """Evaluate model on a test set."""
    embeddings_a = model.encode(texts_a, batch_size=batch_size,
                                normalize_embeddings=True, show_progress_bar=False)
    embeddings_b = model.encode(texts_b, batch_size=batch_size,
                                normalize_embeddings=True, show_progress_bar=False)

    similarities = np.sum(embeddings_a * embeddings_b, axis=1)

    pos_sims = [s for s, l in zip(similarities, labels) if l == 1]
    neg_sims = [s for s, l in zip(similarities, labels) if l == 0]

    if not pos_sims or not neg_sims:
        return {"accuracy": 0, "gap": 0}

    pos_mean = np.mean(pos_sims)
    neg_mean = np.mean(neg_sims)
    gap = pos_mean - neg_mean

    best_acc, best_t = 0, 0
    for t_int in range(30, 90):
        t = t_int / 100.0
        tp = sum(1 for s in pos_sims if s >= t)
        tn = sum(1 for s in neg_sims if s < t)
        acc = (tp + tn) / (len(pos_sims) + len(neg_sims))
        if acc > best_acc:
            best_acc = acc
            best_t = t

    type_stats = {}
    for pt in set(pair_types):
        idxs = [i for i, t in enumerate(pair_types) if t == pt]
        if idxs:
            type_sims = [similarities[i] for i in idxs]
            type_labels = [labels[i] for i in idxs]
            type_pos = [s for s, l in zip(type_sims, type_labels) if l == 1]
            type_neg = [s for s, l in zip(type_sims, type_labels) if l == 0]
            type_stats[pt] = {
                "n": len(idxs),
                "pos_mean": float(np.mean(type_pos)) if type_pos else 0,
                "neg_mean": float(np.mean(type_neg)) if type_neg else 0,
            }

    return {
        "pos_mean": float(pos_mean), "neg_mean": float(neg_mean),
        "gap": float(gap), "best_threshold": float(best_t),
        "accuracy": float(best_acc), "pos_n": len(pos_sims),
        "neg_n": len(neg_sims), "per_type": type_stats,
    }


def main():
    parser = argparse.ArgumentParser(description="Fine-tune hadith embeddings with LoRA")
    parser.add_argument("--es-url", default="http://localhost:9200")
    parser.add_argument("--index", default="rewayaat_updated")
    parser.add_argument("--data-dir", default="tmp/eval")
    parser.add_argument("--model", default="intfloat/multilingual-e5-large")
    parser.add_argument("--output-dir", default="tmp/finetuned_model")
    parser.add_argument("--epochs", type=int, default=2)
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--lr", type=float, default=2e-5)
    parser.add_argument("--warmup-steps", type=int, default=50)
    parser.add_argument("--max-seq-length", type=int, default=512)
    parser.add_argument("--lora-r", type=int, default=8, help="LoRA rank")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--eval-only", action="store_true")
    parser.add_argument("--eval-baseline", action="store_true")
    parser.add_argument("--max-ram-gb", type=float, default=10.0,
                        help="Abort if process RAM exceeds this (GB)")
    args = parser.parse_args()

    sys.stdout.reconfigure(line_buffering=True)

    import torch
    torch.manual_seed(args.seed)
    np.random.seed(args.seed)
    # LIMIT RESOURCES: 2 threads max to keep RAM under control
    torch.set_num_threads(2)
    torch.set_num_interop_threads(1)
    gc.set_threshold(700, 10, 10)

    MAX_RAM_GB = args.max_ram_gb
    import psutil
    mem = psutil.virtual_memory()
    print(f"System RAM: {mem.total / 1e9:.1f}GB total, {mem.available / 1e9:.1f}GB available")
    print(f"RAM limit: {MAX_RAM_GB}GB — will abort if exceeded")

    def check_ram(context=""):
        used = psutil.virtual_memory().used / 1e9
        if used > MAX_RAM_GB:
            print(f"ABORT: RAM usage {used:.1f}GB exceeds limit {MAX_RAM_GB}GB ({context})")
            sys.exit(1)
        return used

    device = torch.device("cpu")
    print(f"Device: cpu (2 threads, {MAX_RAM_GB}GB RAM limit)")

    data_dir = Path(args.data_dir)

    # Load data
    print("=== Loading training data ===")
    train_a, train_b, train_labels, train_types = load_pairs(
        str(data_dir / "train.json"), args.es_url, args.index)
    test_a, test_b, test_labels, test_types = [], [], [], []
    if (data_dir / "test.json").exists():
        test_a, test_b, test_labels, test_types = load_pairs(
            str(data_dir / "test.json"), args.es_url, args.index)

    print(f"\nTrain: {len(train_a)} pairs ({sum(train_labels)} pos / "
          f"{len(train_labels) - sum(train_labels)} neg)")
    if test_a:
        print(f"Test:  {len(test_a)} pairs ({sum(test_labels)} pos / "
              f"{len(test_labels) - sum(test_labels)} neg)")

    # Load model
    print(f"\n=== Loading model: {args.model} ===")
    from sentence_transformers import SentenceTransformer
    model = SentenceTransformer(args.model)
    model.max_seq_length = args.max_seq_length
    dims = model.get_sentence_embedding_dimension()
    print(f"Dimensions: {dims}")

    # Baseline evaluation
    baseline = None
    if args.eval_baseline or args.eval_only:
        print("\n=== Baseline Evaluation ===")
        if test_a:
            baseline = evaluate(model, test_a, test_b, test_labels, test_types)
            print(f"  Accuracy: {baseline['accuracy']:.2%}")
            print(f"  Pos mean: {baseline['pos_mean']:.4f}, Neg mean: {baseline['neg_mean']:.4f}")
            print(f"  Gap: {baseline['gap']:.4f}")
            print(f"  Best threshold: {baseline['best_threshold']:.2f}")
            for pt, stats in baseline.get("per_type", {}).items():
                print(f"    {pt}: n={stats['n']}, pos={stats['pos_mean']:.4f}, neg={stats['neg_mean']:.4f}")
        if args.eval_only:
            return

    # Apply LoRA
    print(f"\n=== Applying LoRA (r={args.lora_r}) ===")
    from peft import LoraConfig, get_peft_model, TaskType

    # Get the underlying transformer model
    transformer = model[0].auto_model  # the bert/xlm-roberta backbone

    lora_config = LoraConfig(
        task_type=TaskType.FEATURE_EXTRACTION,
        r=args.lora_r,
        lora_alpha=args.lora_r * 2,  # alpha = 2*r is standard
        lora_dropout=0.1,
        target_modules=["query", "value"],  # attention Q and V
    )
    transformer = get_peft_model(transformer, lora_config)
    trainable, total = transformer.get_nb_trainable_parameters()
    print(f"  Trainable: {trainable:,} / {total:,} ({100*trainable/total:.2f}%)")

    # Put the LoRA-wrapped transformer back
    model[0].auto_model = transformer
    check_ram("after LoRA setup")

    # Prepare training data — positive pairs only for MNR Loss
    pos_texts_a = [train_a[i] for i in range(len(train_a)) if train_labels[i] == 1]
    pos_texts_b = [train_b[i] for i in range(len(train_a)) if train_labels[i] == 1]
    print(f"\n  Positive pairs for training: {len(pos_texts_a)}")

    # Manual training loop with MNR loss — direct forward pass to keep gradients
    print(f"\n=== Training (LoRA, manual loop on {device}) ===")
    print(f"  Epochs: {args.epochs}, Batch size: {args.batch_size}, LR: {args.lr}")

    # Use tokenizer directly from the already-loaded model (avoids HF Hub download)
    tokenizer = model.tokenizer

    def encode_with_grad(texts):
        """Encode texts keeping gradient graph intact for backprop."""
        tokens = tokenizer(texts, padding=True, truncation=True,
                           max_length=args.max_seq_length, return_tensors="pt")
        tokens = {k: v.to(device) for k, v in tokens.items()}
        output = transformer(**tokens)
        # CLS pooling (first token)
        cls_embeddings = output.last_hidden_state[:, 0]
        # L2 normalize
        norms = torch.norm(cls_embeddings, p=2, dim=1, keepdim=True).clamp(min=1e-12)
        return cls_embeddings / norms

    optimizer = torch.optim.AdamW(
        [p for p in transformer.parameters() if p.requires_grad],
        lr=args.lr, weight_decay=0.01
    )

    # Cosine scheduler
    total_steps = (len(pos_texts_a) // args.batch_size) * args.epochs
    warmup_steps = args.warmup_steps
    print(f"  Total steps: {total_steps}, Warmup: {warmup_steps}")

    step = 0
    for epoch in range(args.epochs):
        # Shuffle
        indices = list(range(len(pos_texts_a)))
        np.random.shuffle(indices)

        epoch_loss = 0.0
        n_batches = 0

        for batch_start in range(0, len(pos_texts_a), args.batch_size):
            batch_idx = indices[batch_start:batch_start + args.batch_size]
            if len(batch_idx) < 2:
                continue

            batch_a = [pos_texts_a[i] for i in batch_idx]
            batch_b = [pos_texts_b[i] for i in batch_idx]

            # Encode with gradients
            emb_a = encode_with_grad(batch_a)
            emb_b = encode_with_grad(batch_b)

            # MNR Loss: score[i,j] = dot(emb_a[i], emb_b[j])
            # Diagonal should be highest (positive pairs)
            scores = torch.mm(emb_a, emb_b.t())  # (B, B)
            labels_tensor = torch.arange(len(batch_idx), device=device)

            loss = torch.nn.functional.cross_entropy(scores * 20.0, labels_tensor)

            # Also reverse direction
            scores_rev = torch.mm(emb_b, emb_a.t())
            loss_rev = torch.nn.functional.cross_entropy(scores_rev * 20.0, labels_tensor)
            loss = (loss + loss_rev) / 2

            # Warmup + cosine LR
            if step < warmup_steps:
                lr_scale = (step + 1) / warmup_steps
                for pg in optimizer.param_groups:
                    pg['lr'] = args.lr * lr_scale
            else:
                progress = (step - warmup_steps) / max(total_steps - warmup_steps, 1)
                for pg in optimizer.param_groups:
                    pg['lr'] = args.lr * 0.5 * (1 + np.cos(np.pi * progress))

            optimizer.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(
                [p for p in transformer.parameters() if p.requires_grad], 1.0)
            optimizer.step()

            epoch_loss += loss.item()
            n_batches += 1
            step += 1

            del emb_a, emb_b, scores, scores_rev, loss, loss_rev
            gc.collect()

            if n_batches % 20 == 0:
                ram_used = check_ram(f"step {n_batches}")
                print(f"  Epoch {epoch+1} step {n_batches}: "
                      f"loss={epoch_loss/n_batches:.4f}, "
                      f"RAM={ram_used:.1f}GB")

        avg_loss = epoch_loss / max(n_batches, 1)
        ram_used = check_ram(f"epoch {epoch+1} done")
        print(f"  Epoch {epoch+1} done: avg_loss={avg_loss:.4f}, "
              f"RAM={ram_used:.1f}GB used")

        # Eval after each epoch (uses model.encode which is fine for inference)
        if test_a:
            eval_result = evaluate(model, test_a, test_b, test_labels, test_types,
                                   batch_size=16)
            print(f"  Eval: accuracy={eval_result['accuracy']:.2%}, "
                  f"gap={eval_result['gap']:.4f}, "
                  f"threshold={eval_result['best_threshold']:.2f}")

    # Save model
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    # Save the full model (base + LoRA merged)
    model.save(str(output_dir / "final"))
    print(f"\nModel saved to {output_dir / 'final'}")

    # Also save just the LoRA adapter (smaller)
    transformer.save_pretrained(str(output_dir / "lora_adapter"))
    print(f"LoRA adapter saved to {output_dir / 'lora_adapter'}")

    # Final evaluation
    if test_a:
        print("\n=== Final Evaluation ===")
        final = evaluate(model, test_a, test_b, test_labels, test_types)
        print(f"  Accuracy: {final['accuracy']:.2%}")
        print(f"  Pos mean: {final['pos_mean']:.4f}, Neg mean: {final['neg_mean']:.4f}")
        print(f"  Gap: {final['gap']:.4f}")
        print(f"  Best threshold: {final['best_threshold']:.2f}")
        for pt, stats in final.get("per_type", {}).items():
            print(f"    {pt}: n={stats['n']}, pos={stats['pos_mean']:.4f}, neg={stats['neg_mean']:.4f}")

        if baseline:
            improvement = final['accuracy'] - baseline['accuracy']
            gap_improvement = final['gap'] - baseline['gap']
            print(f"\n  Improvement: accuracy {improvement:+.2%}, gap {gap_improvement:+.4f}")

    # Save results
    results = {
        "model": args.model,
        "method": "lora",
        "lora_r": args.lora_r,
        "epochs": args.epochs,
        "batch_size": args.batch_size,
        "lr": args.lr,
        "trainable_params": trainable,
        "total_params": total,
    }
    if test_a:
        results["final_eval"] = final
        if baseline:
            results["baseline_eval"] = baseline

    with open(output_dir / "training_results.json", 'w') as f:
        json.dump(results, f, indent=2, default=str)
    print(f"Results saved to {output_dir / 'training_results.json'}")


if __name__ == "__main__":
    main()
