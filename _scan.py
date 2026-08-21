import json, os
root = r'NPDevRuntimeHost'
host = root + r'\src\main\java\com\finalexec'
core = root + r'\runtimehost-core\src\main\java'
manifest = json.load(open(root + r'\src\main\resources\npdev\runtime-supported-controllers.json', encoding='utf-8'))

def find(name):
    locs = []
    for base, label in [(host,'host'), (core,'core')]:
        for dp, dn, fns in os.walk(base):
            if name + '.java' in fns:
                locs.append((label, os.path.relpath(dp, base)))
    return locs or [('ABSENT','')]

allc = manifest['allowedControllers'] + manifest['deferredControllers'] + manifest['testOnlyControllers']
print('--- allowed (32) ---')
for n in manifest['allowedControllers']:
    print(n, '=>', find(n))
print('--- deferred (7) ---')
for n in manifest['deferredControllers']:
    print(n, '=>', find(n))
