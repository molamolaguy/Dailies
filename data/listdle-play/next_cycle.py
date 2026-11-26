"""Picks the next unplayed games for a run: 12 groups of 4 (python3 next_cycle.py <cycle name>).
Prints one line per group: '<batch name> <id> <id> <id> <id>'. A game is played once it has a line in results/*.jsonl."""
import json, glob, sys, os
here = os.path.dirname(os.path.abspath(__file__))
done = set()
for f in glob.glob(os.path.join(here, 'results', '*.jsonl')):
    for line in open(f, encoding='utf8'):
        try: done.add(int(json.loads(line)['id']))
        except Exception: pass
ids = [json.loads(l)['id'] for l in open(os.path.join(here, 'games.jsonl'), encoding='utf8')]
todo = [i for i in ids if i not in done]
cycle = sys.argv[1] if len(sys.argv) > 1 else 'c'
print(f'# {len(done)} played, {len(todo)} left')
for g in range(12):
    group = todo[g * 4:(g + 1) * 4]
    if group: print(f'{cycle}-{chr(97 + g)}', *group)
