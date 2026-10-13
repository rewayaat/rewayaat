"""Measure the current build against a snapshot, like for like, both sides from people.json.

Prints people, cross-book share, verdict and kunyah clashes, the twelve most-cited narrators (the
people bearing each one's name and marks, and which person holds each of his main-book entries),
agent separations now inside one person, refused unions, and the sanction check: every person
combining two or more snapshot people must be explained by applied agent or reviewer answers —
by the named runs, or failing that by earlier ones; a join explained by neither is a rule's alone.

Usage:
    python3 scripts/narrators/measure_build.py tmp/narrators_archive/<snapshot> <l3 run name>...
"""
import json, sys, os, collections
W = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
sys.path.insert(0, f'{W}/scripts/narrators'); os.chdir(W)
from narrator_schema import normalize_arabic as NA, fold_kunyah
from identity import load_record, live
A = sys.argv[1]  # snapshot taken just before the run(s)
POS = {'thiqa', 'saduq', 'hasan', 'qawi'}; NEG = {'daif', 'very_weak', 'kadhdhab'}
old = json.load(open(f'{A}/people.json')); new = json.load(open('tmp/narrators_identity/people.json'))
oldmem = json.load(open(f'{A}/membership.json')); newmem = json.load(open('tmp/narrators_identity/membership.json'))
def shape(ps):
    multi = [p for p in ps if len(p['source_keys']) >= 2]
    g = {frozenset(p['source_keys']) for p in multi if {x['grade'] for x in p['reliability_grades']} & POS and {x['grade'] for x in p['reliability_grades']} & NEG}
    k = {frozenset(p['source_keys']) for p in multi if len({fold_kunyah(e['value']) for e in p['kunyahs_arabic']} - {None, ''}) >= 2}
    cross = sum(1 for p in ps if len({x.split(':')[0] for x in p['source_keys']}) > 1)
    return len(ps), cross, g, k, max(len(p['source_keys']) for p in ps)
n0, c0, g0, k0, m0 = shape(old); n1, c1, g1, k1, m1 = shape(new)
print(f"people {n1} (was {n0}, {n1-n0:+d}) | on >1 book {c1} = {c1/n1*100:.1f}% (was {c0} = {c0/n0*100:.1f}%) | largest person {m1} sources (was {m0})")
print(f"grade clashes {len(g1)} (was {len(g0)}; {len(g1-g0)} new) | kunyah clashes {len(k1)} (was {len(k0)}; {len(k1-k0)} new)")
moved = sum(1 for k in oldmem if k in newmem and oldmem[k] != newmem[k])
print(f"sources whose person id changed: {moved} of {len(oldmem)}")
by_key = {k: p for p in new for k in p['source_keys']}
newclash = sorted(g1 - g0, key=len, reverse=True)
print("new grade clashes (largest first):")
for s in newclash[:8]:
    p = by_key[next(iter(s))]
    print(f"   {p['person_id']} {len(s)}src {p['primary_arabic_name'][:40]} grades {sorted({x['grade'] for x in p['reliability_grades']})}")
FAMOUS = [('سهل بن زياد', {'ابو سعيد', 'الادمي', 'الرازي'}), ('زرارة بن اعين', {'الشيباني', 'ابو الحسن', 'ابو علي'}),
          ('محمد بن مسلم', {'الثقفي', 'الطحان'}), ('محمد بن ابي عمير', {'ابو احمد', 'الازدي'}),
          ('ابن ابي عمير', {'ابو احمد', 'الازدي'}), ('يونس بن عبد الرحمن', {'ابو محمد'}),
          ('الحسين بن سعيد', {'الاهوازي'}), ('احمد بن محمد بن عيسى', {'الاشعري', 'القمي'}),
          ('ابراهيم بن هاشم', {'القمي', 'ابو اسحاق'}), ('الفضل بن شاذان', {'النيسابوري'}),
          ('صفوان بن يحيى', {'البجلي', 'بياع السابري'}), ('جميل بن دراج', {'النخعي', 'ابو علي'})]
def fam(ps):
    out = {}
    for base, marks in FAMOUS:
        b = NA(base); mk = {NA(m) for m in marks}; sizes = []
        for p in ps:
            if not any(NA(e['value']) == b or NA(e['value']).startswith(b + ' ') for e in p['primary_names']): continue
            if ({fold_kunyah(e['value']) for e in p['kunyahs_arabic']} | {NA(e['value']) for e in p['titles']}) & mk: sizes.append(len(p['source_keys']))
        out[base] = sorted(sizes, reverse=True)
    return out
f0, f1 = fam(old), fam(new)
print("famous narrators — people that are him (count, largest sizes), before -> after:")
for base in f1: print(f"   {base:22s} {len(f0[base]):2d} {f0[base][:4]}  ->  {len(f1[base]):2d} {f1[base][:4]}")
rec = load_record('tmp/narrators_identity/decisions.jsonl')
agent = [d for d in live(rec) if d['actor'] == 'agent' and d['kind'] in ('partition', 'not_same')]
viol = collections.Counter()
for d in agent:
    ps = [{newmem[k] for k in g if k in newmem} for g in d['groups']]
    if any(ps[i] & ps[j] for i in range(len(ps)) for j in range(i + 1, len(ps))): viol[d['kind']] += 1
print(f"agent separations now inside one person: {dict(viol)} (of {len(agent)})")

# --- the stricter check: which person holds each main-book entry of a famous man ---
MAIN = ('najashi', 'fihrist', 'kashshi', 'tusi', 'duafa', 'ardabili')
def main_entries(ps):
    out = {}
    for base, marks in FAMOUS:
        b = NA(base); mk = {NA(m) for m in marks}; held = {}
        for p in ps:
            if not any(NA(e['value']) == b or NA(e['value']).startswith(b + ' ') for e in p['primary_names']): continue
            if not ({fold_kunyah(e['value']) for e in p['kunyahs_arabic']} | {NA(e['value']) for e in p['titles']}) & mk: continue
            m = [k for k in p['source_keys'] if k.split(':')[0] in MAIN]
            if m: held[p['person_id']] = m
        out[base] = held
    return out
e0, e1 = main_entries(old), main_entries(new)
print("\nmain-book entries of each famous narrator, by person (before -> after):")
for base in e1:
    print(f"   {base:22s} {len(e0[base])} people -> {len(e1[base])}: " + "; ".join(f"{pid} {ks[:5]}" for pid, ks in e1[base].items()))

# --- split-specific checks ---
stats = json.load(open('tmp/narrators_identity/build_stats.json'))
print("\nrefused unions this build:", stats.get('refused_unions'))
print("registry this build:", stats.get('registry'))
events = [json.loads(l) for l in open('tmp/narrators_identity/person_ids.jsonl') if l.strip()]
splits = [e for e in events if e.get('event') == 'mint' and e.get('split_from')]
print(f"identifiers minted from a split, all time: {len(splits)}")
sayrafi = {k: newmem.get(k) for k in ['najashi:810', 'fihrist:507', 'khoei:12465', 'khoei:19338']}
print("'Amr b. Hurayth entries -> people:", sayrafi)
# people whose clash disappeared, and people whose clash is new
POSN = {p['person_id'] for p in new}
gone = [s for s in g0 - g1]
print(f"verdict clashes resolved (by source set): {len(g0 - g1)}; new: {len(g1 - g0)}")

# --- sanction check: every person combining >=2 snapshot people must be explained by applied agent
# joins from the named runs (attach/xform same/partition), chained through snapshot people ---
runs = {f"l3:{r}" for r in sys.argv[2:]}
def explainer(keep):
    """Union snapshot people over the applied same/partition decisions `keep` accepts."""
    parent = {}
    def find(x):
        parent.setdefault(x, x)
        while parent[x] != x:
            parent[x] = parent[parent[x]]; x = parent[x]
        return x
    for d in live(rec):
        if d.get('status') != 'applied' or not keep(d): continue
        groups = [d['sources']] if d['kind'] == 'same' else d['groups'] if d['kind'] == 'partition' else []
        for g in groups:
            ps = [oldmem.get(k) for k in g if oldmem.get(k)]
            for p in ps[1:]: parent[find(ps[0])] = find(p)
    return find
by_run = explainer(lambda d: d.get('origin', {}).get('run') in runs)
by_agent = explainer(lambda d: d['actor'] in ('agent', 'reviewer'))
# a split can lift the separation an entry held, letting an EARLIER agent join go through: that is
# explained, but not by this run; a join explained by no agent or reviewer answer is a rule's alone
joined, not_run, rule_only = 0, [], []
for p in new:
    olds = {oldmem[k] for k in p['source_keys'] if k in oldmem}
    if len(olds) < 2: continue
    joined += 1
    row = (p['person_id'], len(olds), p['primary_arabic_name'][:40])
    if len({by_run(o) for o in olds}) > 1: not_run.append(row)
    if len({by_agent(o) for o in olds}) > 1: rule_only.append(row)
print(f"\npeople combining >=2 snapshot people: {joined}; not explained by the runs' applied joins: {len(not_run)}; "
      f"explained by no applied agent or reviewer answer: {len(rule_only)}")
for u in not_run[:10]: print("    not this run:", u)
for u in rule_only[:10]: print("    RULE ONLY:", u)
