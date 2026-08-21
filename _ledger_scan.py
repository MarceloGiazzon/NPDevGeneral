import yaml, glob
from collections import Counter
items = []
for p in glob.glob('ledger/items/*.yml'):
    d = yaml.safe_load(open(p, encoding='utf-8'))
    if d: items.append(d)
print('STATUS COUNTS:', dict(Counter(i.get('status','?') for i in items)))
print('TOTAL ITEMS:', len(items))
print('---- NON-DONE ----')
for i in sorted(items, key=lambda x: x.get('id','')):
    s = i.get('status','?')
    if s != 'DONE':
        t = (i.get('title') or '').replace('\n',' ').strip()
        print(i.get('id'), '|', s, '|', i.get('type'), '|', i.get('severity'), '|', t[:90])
