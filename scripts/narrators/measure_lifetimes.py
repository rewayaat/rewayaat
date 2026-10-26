"""Men the books place under Imams no one life could have reached.

A rijal book says «of the companions of al-Sadiq» and nothing else about when a man lived,
so the Imams named for him are the only clock we have. That clock also catches a bad join
for free: if one person carries «a companion of Ali» and «a companion of al-Jawad», the two
periods are 180 years apart and he is two men, whatever their names look like.

The test is deliberately the conservative one. A man need only be alive at some point inside
each period named, not live through all of them, so the shortest life that could do it runs
from the END of the earliest period to the START of the latest. Only when that exceeds a
long life is the join impossible rather than merely surprising. Measuring it the naive way —
the first period's start to the last one's end — flags twice as many people and most of the
extra are honest: al-Mahdi's period runs to 329 in the chronology, so every man who saw the
eleventh Imam and is said to have seen the twelfth gets 69 years he never had to live.

It is a precision check and nothing else. A clean run does not mean the identities are right;
a dirty one means some of them are certainly wrong, which is the more useful half.

Usage:
    python3 scripts/narrators/measure_lifetimes.py                      # the current build
    python3 scripts/narrators/measure_lifetimes.py --against <snapshot> # and what it was before
    python3 scripts/narrators/measure_lifetimes.py --list 40            # the worst, to go and read
"""
import argparse, json, os, sys

W = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
sys.path.insert(0, f'{W}/scripts/narrators')
from narrator_schema import imam_generations

PEOPLE = f'{W}/tmp/narrators_identity/people.json'
CHRONOLOGY = f'{W}/src/main/resources/data/chronology.json'

# A long life, in years. Men of 90 are recorded in these books and we do not want to call
# them impossible; nobody reaches 120.
LONG_LIFE = 90


def periods():
    """Each Imam's ordinal to the years his period ran, from the page's own chronology."""
    with open(CHRONOLOGY) as handle:
        return {e['order']: (e['fromAh'], e['toAh']) for e in json.load(handle)['eras']}


def imams_of(person):
    """Every Imam ordinal any book files this person under."""
    found = set()
    for statement in person.get('generations') or []:
        value = statement.get('value') if isinstance(statement, dict) else statement
        if value:
            found |= imam_generations(value)
    return found


def shortest_life(ordinals, years):
    """The years a life would have to cover to reach every period named. None if one or none."""
    known = sorted(o for o in ordinals if o in years)
    if len(known) < 2:
        return None
    return years[known[-1]][0] - years[known[0]][1]


def survey(path, years, limit=LONG_LIFE):
    with open(path) as handle:
        people = json.load(handle)
    multi, impossible = 0, []
    for person in people:
        needs = shortest_life(imams_of(person), years)
        if needs is None:
            continue
        multi += 1
        if needs > limit:
            impossible.append((needs, len(person['source_keys']), person['person_id'],
                               (person.get('primary_arabic_name') or '')[:44]))
    impossible.sort(reverse=True)
    return len(people), multi, impossible


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--against', help='a tmp/narrators_archive snapshot to compare with')
    parser.add_argument('--list', type=int, default=0, metavar='N',
                        help='print the N worst, to go and read the entries')
    parser.add_argument('--people', default=PEOPLE)
    parser.add_argument('--long-life', type=int, default=LONG_LIFE,
                        help=f'years a long life is allowed to run (default {LONG_LIFE})')
    args = parser.parse_args()

    years = periods()
    total, multi, bad = survey(args.people, years, args.long_life)
    share = 100 * len(bad) / max(multi, 1)
    print(f'{total:7d} people, {multi} of them filed under two or more Imams')
    print(f'{len(bad):7d} of those could not be one man ({share:.2f}%) '
          f'— a life would have to run past {args.long_life} years')

    if args.against:
        other = os.path.join(args.against, 'people.json')
        if not os.path.exists(other):
            other = os.path.join(W, 'tmp/narrators_archive', args.against, 'people.json')
        if os.path.exists(other):
            was_total, was_multi, was_bad = survey(other, years, args.long_life)
            was_share = 100 * len(was_bad) / max(was_multi, 1)
            moved = len(bad) - len(was_bad)
            print(f'{was_total:7d} people before, {len(was_bad)} impossible ({was_share:.2f}%)'
                  f' -> {moved:+d} people, {share - was_share:+.2f} points')
        else:
            print(f'  no people.json under {args.against}')

    for needs, sources, person_id, name in bad[:args.list]:
        print(f'  {needs:4d}y  {sources:3d} sources  {person_id}  {name}')

    # A failing precision check is worth a non-zero exit, so a build script can stop on it.
    return 1 if share > 12 else 0


if __name__ == '__main__':
    sys.exit(main())
