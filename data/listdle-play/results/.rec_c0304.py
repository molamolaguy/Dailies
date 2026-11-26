import json,sys
d=json.loads(sys.argv[1])
open('/home/user/Dailies/data/listdle-play/results/c03-04.jsonl','a').write(json.dumps(d,ensure_ascii=False)+'\n')
