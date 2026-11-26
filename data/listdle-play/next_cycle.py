"""Picks the next unplayed games for a run: 20 groups of 10 (python3 next_cycle.py <cycle name> [groups] [size]).
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
GROUPS = int(sys.argv[2]) if len(sys.argv) > 2 else 20
SIZE = int(sys.argv[3]) if len(sys.argv) > 3 else 10
# Games another run is playing right now (listed in running.txt) are skipped.
running = set()
rp = os.path.join(here, 'running.txt')
if os.path.exists(rp): running = {int(x) for x in open(rp).read().split() if x.isdigit()}
todo = [i for i in todo if i not in running]
print(f'# {len(done)} played, {len(todo)} left')
for g in range(GROUPS):
    group = todo[g * SIZE:(g + 1) * SIZE]
    if group: print(f'{cycle}-{g + 1:02d}', *group)
