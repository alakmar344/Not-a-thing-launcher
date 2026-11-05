#!/usr/bin/env python3
"""Checks that a minified (R8) release build still contains everything Launcher3 loads by name.

R8 only sees code references. Launcher3 instantiates several classes from *resource strings*,
which R8 cannot see, so a keep rule that is slightly too loose produces a release APK that
compiles, passes every (unminified) debug test, and then silently loses features or crashes:

  1. ResourceBasedOverride.Overrides.getObject(Base.class, ctx, R.string.xxx_class)
       - non-empty xxx_class  -> Class.forName(name).getDeclaredConstructor(Context.class)
       - empty / failed       -> Base.class.newInstance()  (uncaught RuntimeException on failure)
  2. CustomWidgetManager / PluginManagerWrapper: every entry of R.array.custom_widget_providers
       -> Class.forName(name).getDeclaredConstructor(Context.class), cast to CustomWidgetPlugin

This script reads the real release dex (from the APK) plus the merged release resources and
fails if any of those targets is missing, lost the needed constructor, or lost its supertype.

Usage:
  tools/verify_release_reflection.py --apk app/build/outputs/apk/release/app-release.apk \
      --values app/build/intermediates/incremental/release/mergeReleaseResources/merged.dir/values/values.xml \
      --sources third_party/aosp/launcher3/src launcher3/src app/src/main/java
  (or --dex classes.dex [classes2.dex ...] instead of --apk)
"""
import argparse
import os
import re
import struct
import sys
import xml.etree.ElementTree as ET
import zipfile

RBO = 'com.android.launcher3.util.ResourceBasedOverride'
CWP = 'com.android.systemui.plugins.CustomWidgetPlugin'
CTX_CTOR = '(Landroid/content/Context;)V'
NOARG_CTOR = '()V'


def _uleb(d, o):
    r = s = 0
    while True:
        b = d[o]
        o += 1
        r |= (b & 0x7F) << s
        if b < 0x80:
            return r, o
        s += 7


def parse_dex(d):
    """Returns {class_name: {'super': name, 'ifaces': [names], 'ctors': {proto}, 'abstract': bool}}."""
    u4 = lambda o: struct.unpack_from('<I', d, o)[0]
    u2 = lambda o: struct.unpack_from('<H', d, o)[0]
    n_str, o_str = u4(0x38), u4(0x3C)
    n_typ, o_typ = u4(0x40), u4(0x44)
    o_pro = u4(0x4C)
    o_met = u4(0x5C)
    n_cls, o_cls = u4(0x60), u4(0x64)

    def string(i):
        off = u4(o_str + 4 * i)
        _, off = _uleb(d, off)
        end = d.index(b'\0', off)
        return d[off:end].decode('utf-8', 'replace')

    def typ(i):
        return string(u4(o_typ + 4 * i))

    def type_list(off):
        if off == 0:
            return []
        return [typ(u2(off + 4 + 2 * k)) for k in range(u4(off))]

    def desc_to_name(t):
        return t[1:-1].replace('/', '.') if t.startswith('L') else t

    def proto_sig(pi):
        base = o_pro + 12 * pi
        params = ''.join(type_list(u4(base + 8)))
        return '(' + params + ')' + typ(u4(base + 4))

    out = {}
    for c in range(n_cls):
        base = o_cls + 32 * c
        name = desc_to_name(typ(u4(base)))
        flags = u4(base + 4)
        sup_idx = u4(base + 8)
        sup = desc_to_name(typ(sup_idx)) if sup_idx != 0xFFFFFFFF else None
        ifaces = [desc_to_name(t) for t in type_list(u4(base + 12))]
        ctors = set()
        data = u4(base + 24)
        if data:
            o = data
            sf, o = _uleb(d, o)
            inf, o = _uleb(d, o)
            dm, o = _uleb(d, o)
            _vm, o = _uleb(d, o)
            for _ in range(sf + inf):
                _, o = _uleb(d, o)
                _, o = _uleb(d, o)
            midx = 0
            for _ in range(dm):
                diff, o = _uleb(d, o)
                _acc, o = _uleb(d, o)
                _code, o = _uleb(d, o)
                midx += diff
                mbase = o_met + 8 * midx
                if string(u4(mbase + 4)) == '<init>':
                    ctors.add(proto_sig(u2(mbase + 2)))
        out[name] = {'super': sup, 'ifaces': ifaces, 'ctors': ctors,
                     'abstract': bool(flags & 0x400) or bool(flags & 0x200)}
    return out


def is_subtype(classes, name, target, seen=None):
    seen = seen or set()
    if name is None or name in seen:
        return False
    if name == target:
        return True
    seen.add(name)
    c = classes.get(name)
    if c is None:
        return False
    return any(is_subtype(classes, n, target, seen) for n in [c['super']] + c['ifaces'])


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--apk')
    ap.add_argument('--dex', nargs='*', default=[])
    ap.add_argument('--values', required=True, help='merged release values.xml')
    ap.add_argument('--sources', nargs='+', required=True,
                    help='source roots scanned for Overrides.getObject(Base.class, ...) call sites')
    a = ap.parse_args()

    blobs = []
    if a.apk:
        with zipfile.ZipFile(a.apk) as z:
            blobs += [z.read(n) for n in z.namelist() if re.fullmatch(r'classes\d*\.dex', n)]
    for p in a.dex:
        blobs.append(open(p, 'rb').read())
    if not blobs:
        sys.exit('no dex input')
    classes = {}
    for b in blobs:
        classes.update(parse_dex(b))

    res = ET.parse(a.values).getroot()
    errors, checked = [], 0

    def need(name, ctor, supertype, why):
        nonlocal checked
        checked += 1
        c = classes.get(name)
        if c is None:
            errors.append(f'{name}: class missing from release dex ({why})')
            return
        if ctor not in c['ctors']:
            errors.append(f'{name}: constructor {ctor} stripped ({why}); has {sorted(c["ctors"]) or "none"}')
        if supertype and not is_subtype(classes, name, supertype):
            errors.append(f'{name}: no longer a {supertype} ({why})')

    # 1a. Non-empty ResourceBasedOverride class-name strings.
    for s in res.iter('string'):
        n, v = s.get('name', ''), (s.text or '').strip()
        if n.endswith('_class') and v:
            need(v, CTX_CTOR, RBO, f'R.string.{n}')
    # 2. Custom widget providers.
    for arr in res.iter('string-array'):
        if arr.get('name') == 'custom_widget_providers':
            for item in arr.iter('item'):
                need((item.text or '').strip(), CTX_CTOR, CWP, 'R.array.custom_widget_providers')
    # 1b. Fallback base classes: Overrides.getObject(Base.class, ...) -> Base.class.newInstance().
    simple = {}
    for name in classes:
        simple.setdefault(name.rsplit('.', 1)[-1].split('$')[-1], []).append(name)
    bases = set()
    for root in a.sources:
        for dp, _, fs in os.walk(root):
            for f in fs:
                if f.endswith(('.java', '.kt')):
                    txt = open(os.path.join(dp, f), errors='ignore').read()
                    bases.update(re.findall(r'Overrides\.getObject\(\s*([A-Za-z_][\w.]*)\.class', txt))
                    bases.update(re.findall(r'Overrides\.getObject\(\s*([A-Za-z_][\w.]*)::class\.java', txt))
    for b in sorted(bases):
        cands = [n for n in simple.get(b.split('.')[-1], []) if is_subtype(classes, n, RBO)]
        if not cands:
            # A base that was never referenced at all is fine to drop; one that IS used will be
            # in the dex. Only report when something with that name implements RBO but lost its
            # no-arg constructor below.
            continue
        for n in cands:
            if not classes[n]['abstract']:
                need(n, NOARG_CTOR, RBO, f'Overrides.getObject({b}.class) fallback')

    for e in errors:
        print('ERROR:', e)
    print(f'{checked} reflective targets checked, {len(errors)} problem(s).')
    sys.exit(1 if errors else 0)


if __name__ == '__main__':
    main()
