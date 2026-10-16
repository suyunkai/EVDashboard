#!/usr/bin/env python3
"""Pack the sound-lab sets into the app's built-in presets.

  python3 -I tools/build-presets.py [--lab dist/sound-lab] [--out app/src/main/assets/presets]

For every preset a folder  <out>/<id>/  is (re)written:
  preset.json         id, name, description and one entry per sound: CP path, category, group, variant, file, sha256
  audio/<sha256>.wav  the replacement audio (identical files are stored once)

Only sounds that are confirmed writable in app/src/main/assets/sounds/catalog.json (they have a playback service) are
packed; reference sounds with an unconfirmed mapping can only be previewed or exported, so they are left out.
The lab keeps the sets as  类别/卡片/变体.wav ; sets/00-原始/音频清单.json maps those names to the CP's real file paths.
"""
import hashlib, json, os, shutil, sys, unicodedata, wave

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PRESETS = [
    dict(id='8bit', set='02-8bit游戏音效', name='8bit游戏音效',
         description='FC/NES 声卡风格的原创合成：挡位、警报、雷达、低速循环都有对应的复古游戏音效，柔和不刺耳。'),
    dict(id='botw', set='03-荒野之息', name='荒野之息',
         description='《塞尔达传说：荒野之息》音频逐项匹配并做过柔化：钟声提示、锁车「呀哈哈」、传感器雷达、低速循环曲。'),
]


def nfc(text):
    return unicodedata.normalize('NFC', text)


def main():
    args = sys.argv[1:]
    lab = os.path.join(ROOT, 'dist', 'sound-lab')
    out = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'presets')
    for flag in ('--lab', '--out'):
        if flag in args:
            i = args.index(flag)
            value = os.path.abspath(args[i + 1])
            del args[i:i + 2]
            if flag == '--lab':
                lab = value
            else:
                out = value
    catalog = json.load(open(os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'sounds', 'catalog.json'), encoding='utf-8'))
    by_path = {e['path']: e for e in catalog}
    inventory = json.load(open(os.path.join(lab, 'sets', '00-原始', '音频清单.json'), encoding='utf-8'))
    path_of = {nfc(e['file']): e['original_path'] for e in inventory['entries']}
    for preset in PRESETS:
        folder = os.path.join(lab, 'sets', preset['set'])
        if not os.path.isdir(folder):
            print(f"skip {preset['id']}: {folder} not found")
            continue
        target = os.path.join(out, preset['id'])
        shutil.rmtree(target, ignore_errors=True)
        os.makedirs(os.path.join(target, 'audio'))
        sounds, left_out = [], []
        for rel, cp_path in sorted(path_of.items(), key=lambda kv: [e['path'] for e in catalog].index(kv[1]) if kv[1] in by_path else 999):
            source = os.path.join(folder, rel)
            if not os.path.isfile(source):
                continue
            spec = by_path.get(cp_path)
            if spec is None or not spec['services']:
                left_out.append(rel)
                continue
            data = open(source, 'rb').read()
            sha = hashlib.sha256(data).hexdigest()
            with wave.open(source, 'rb') as w:
                seconds = round(w.getnframes() / w.getframerate(), 3)
                assert w.getframerate() == 48000 and w.getsampwidth() == 2, rel
            dest = os.path.join(target, 'audio', sha + '.wav')
            if not os.path.exists(dest):
                open(dest, 'wb').write(data)
            sounds.append(dict(path=cp_path, category=spec['category'], group=spec['group'], variant=spec['variant'],
                               file='audio/' + sha + '.wav', sha256=sha, bytes=len(data), seconds=seconds))
        meta = dict(format='instrument-preset', version=1, id=preset['id'], name=preset['name'], description=preset['description'], sounds=sounds)
        json.dump(meta, open(os.path.join(target, 'preset.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
        size = sum(os.path.getsize(os.path.join(target, 'audio', f)) for f in os.listdir(os.path.join(target, 'audio')))
        print(f"{preset['id']}: {len(sounds)} sounds, {len(os.listdir(os.path.join(target, 'audio')))} files, {size / 1e6:.1f} MB; left out (not writable): {left_out}")


if __name__ == '__main__':
    main()
