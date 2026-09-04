#!/usr/bin/env python3
"""
Extract relevant short excerpts from tafsir commentary_text for Quranic Light pipeline.
For each hadith candidate snippet, find the 1-3 sentence passage that best explains
the connection between the Quranic verse and the hadith.

Usage: python3 scripts/quranic-insights/extract_qlight_excerpts_batch.py <batch_number>
"""

import json
import sys
import re
import os

TAFSIR_NAME_TO_SLUG = {
    "Al-Bayan fi Tafsir al-Quran (The Elucidation of Quran Exegesis)": "al-bayan",
    "Tafsir al-Mizan (WOFIS)": "al-mizan",
    "تفسير الأمثل": "ar-amthal",
    "كنز الدقائق": "ar-kanz-al-daqaiq",
    "تفسير القمي": "ar-tafsir-al-qummi",
    "The Glorious Quran - Divine Lights (Chinoy)": "divine-lights",
    "An Enlightening Commentary into the Light of the Holy Quran": "enlightening-commentary",
    "Fatima Zahra in the Noble Quran": "fatima-zahra",
    "Tafsir of Imam Hasan al-Askari": "imam-askari",
    "A Commentary on the Chapter of Praise (Tafsir Surah al-Hamd) - Imam Khomeini": "khomeini-hamd",
    "The Holy Quran: The Final Testament - Pooya/Mir Ahmad Ali": "pooya-mir-ahmad-ali",
    "Quranic Reflections": "quranic-reflections",
}

# Keywords that indicate a connection between verse and hadith topics
RELEVANCE_KEYWORDS = [
    # Fasting / Ramadan
    "fast", "fasting", "ramadan", "sawm", "siyam", "siyām",
    # Piety / righteousness
    "taqwa", "taqwā", "piety", "righteous", "godfearing",
    # Prayer
    "pray", "prayer", "salat", "salāt", "namaz",
    # General Islamic concepts
    "obligation", "obligatory", "command", "decreed", "prescribed",
    "allah", "god", "divine", "belief", "believer",
    "charity", "zakat", "zakāt", "poor", "orphans",
    "pilgrimage", "hajj", "holy", "sacred",
    "imam", "prophet", "messenger", "ahl al-bayt", "ahlulbayt",
    "quran", "revelation", "verse", "guidance",
    "patience", "sabr", "thankful", "gratitude",
    "sin", "forgive", "forgiveness", "mercy",
    "hereafter", "paradise", "hell", "judgment", "resurrection",
    "faith", "iman", "islam", "submit",
    "purify", "purification", "clean", "wudu",
    "family", "marriage", "divorce", "women",
    "knowledge", "wisdom", "learn", "teach",
    "justice", "fair", "oppress", "wrong",
    "heart", "soul", "spirit", "mind",
]


def split_sentences(text):
    """Split text into sentences, handling English text."""
    # Split on sentence-ending punctuation followed by space or end
    sentences = re.split(r'(?<=[.!?])\s+', text)
    return [s.strip() for s in sentences if s.strip()]


def score_sentence(sentence, hadith_text, verse_text):
    """Score a sentence for relevance to the hadith-verse connection."""
    score = 0.0
    sent_lower = sentence.lower()
    hadith_lower = hadith_text.lower()
    verse_lower = verse_text.lower()

    # Check for shared significant words between sentence and hadith
    hadith_words = set(re.findall(r'\b[a-z]{4,}\b', hadith_lower))
    sent_words = set(re.findall(r'\b[a-z]{4,}\b', sent_lower))
    overlap = hadith_words & sent_words
    # Remove very common words
    stop_words = {'that', 'this', 'with', 'from', 'have', 'been', 'were', 'they',
                  'their', 'which', 'would', 'about', 'could', 'other', 'into',
                  'also', 'when', 'what', 'your', 'than', 'them', 'these', 'those',
                  'some', 'very', 'even', 'must', 'should', 'shall', 'upon', 'among'}
    meaningful_overlap = overlap - stop_words
    score += len(meaningful_overlap) * 2.0

    # Check for shared words with verse
    verse_words = set(re.findall(r'\b[a-z]{4,}\b', verse_lower))
    verse_overlap = verse_words & sent_words - stop_words
    score += len(verse_overlap) * 1.5

    # Check for relevance keywords
    for kw in RELEVANCE_KEYWORDS:
        if kw in sent_lower:
            score += 1.0

    # Penalize very short or very long sentences
    word_count = len(sentence.split())
    if word_count < 5:
        score -= 2.0
    elif word_count > 50:
        score -= 1.0

    # Penalize sentences that are mostly quotes or references
    if sentence.count('"') > 2 or sentence.count('(') > 2:
        score -= 0.5

    # Bonus for sentences that reference the verse or hadith topic directly
    if any(w in sent_lower for w in ['this verse', 'the verse', 'this chapter', 'the quran']):
        score += 1.5

    return score


def extract_best_excerpt(commentary_text, hadith_english, hadith_arabic, verse_text, max_words=60):
    """Extract the most relevant 1-3 sentence excerpt from commentary_text."""
    if not commentary_text or len(commentary_text.strip()) < 20:
        return ""

    sentences = split_sentences(commentary_text)
    if not sentences:
        return ""

    # Score each sentence
    scored = [(i, score_sentence(s, hadith_english, verse_text), s) for i, s in enumerate(sentences)]

    # Sort by score descending
    scored.sort(key=lambda x: x[1], reverse=True)

    if not scored or scored[0][1] <= 0:
        # Fallback: return first 1-2 sentences
        result = []
        word_count = 0
        for s in sentences[:2]:
            wc = len(s.split())
            if word_count + wc <= max_words:
                result.append(s)
                word_count += wc
            else:
                break
        return " ".join(result) if result else ""

    # Try to build a contiguous excerpt of 1-3 sentences around the best one
    best_idx = scored[0][0]
    best_score = scored[0][1]

    # Get top-scoring sentences (up to 3)
    top_indices = sorted([x[0] for x in scored[:5] if x[1] > 0])

    # Find the best contiguous block of 1-3 sentences
    best_block = []
    best_block_score = -1

    for start in range(max(0, best_idx - 2), min(len(sentences), best_idx + 1)):
        for end in range(start + 1, min(len(sentences) + 1, start + 4)):
            block = sentences[start:end]
            block_text = " ".join(block)
            word_count = len(block_text.split())
            if word_count > max_words:
                continue

            block_score = sum(score_sentence(s, hadith_english, verse_text) for s in block)
            # Bonus for containing the top-scoring sentence
            if best_idx >= start and best_idx < end:
                block_score += best_score * 0.5
            # Bonus for contiguity (fewer gaps)
            block_score += (end - start) * 0.3

            if block_score > best_block_score:
                best_block_score = block_score
                best_block = block

    if not best_block:
        # Fallback to just the best single sentence
        best_block = [scored[0][2]]

    excerpt = " ".join(best_block)

    # Truncate if still too long
    words = excerpt.split()
    if len(words) > max_words:
        excerpt = " ".join(words[:max_words])
        # Try to end at a sentence boundary
        if not excerpt.endswith(('.', '!', '?')):
            last_period = excerpt.rfind('.')
            if last_period > len(excerpt) * 0.6:
                excerpt = excerpt[:last_period + 1]

    return excerpt


def process_batch(batch_number):
    input_path = f"tmp/qlight-judge-inputs/batch_{batch_number:04d}.jsonl"
    output_path = f"tmp/qlight-excerpt-outputs/batch_{batch_number:04d}.jsonl"

    if not os.path.exists(input_path):
        print(f"Input file not found: {input_path}")
        sys.exit(1)

    os.makedirs(os.path.dirname(output_path), exist_ok=True)

    results = []
    hadith_count = 0
    snippet_count = 0

    with open(input_path, 'r') as f:
        for line in f:
            hadith = json.loads(line)
            hadith_id = hadith['hadith_id']
            hadith_english = hadith.get('hadith_english', '')
            hadith_arabic = hadith.get('hadith_arabic', '')

            for candidate in hadith.get('candidates', []):
                verse_key = candidate['verse_key']
                verse_text = candidate.get('verse_text', '')

                for snippet in candidate.get('snippets', []):
                    tafsir_name = snippet.get('tafsir_name', '')
                    commentary_text = snippet.get('commentary_text', '')

                    if not commentary_text or len(commentary_text.strip()) < 20:
                        continue

                    tafsir_slug = TAFSIR_NAME_TO_SLUG.get(tafsir_name, '')
                    if not tafsir_slug:
                        # Try slugifying the name
                        tafsir_slug = tafsir_name.lower().replace(' ', '-').replace('(', '').replace(')', '')

                    excerpt = extract_best_excerpt(
                        commentary_text, hadith_english, hadith_arabic, verse_text
                    )

                    if excerpt:
                        results.append({
                            "hadith_id": hadith_id,
                            "verse_key": verse_key,
                            "tafsir_slug": tafsir_slug,
                            "relevant_excerpt": excerpt
                        })
                        snippet_count += 1

            hadith_count += 1

    with open(output_path, 'w') as f:
        for r in results:
            f.write(json.dumps(r, ensure_ascii=False) + '\n')

    print(f"Processed {hadith_count} hadith, {snippet_count} excerpts extracted")
    print(f"Output written to {output_path}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(f"Usage: {sys.argv[0]} <batch_number>")
        sys.exit(1)

    batch_number = int(sys.argv[1])
    process_batch(batch_number)
