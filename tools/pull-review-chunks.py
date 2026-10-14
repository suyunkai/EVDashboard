#!/usr/bin/env python3
"""Resume a read-only full image download; verify chunks, compressed and raw SHA-256."""
import argparse
import concurrent.futures
import gzip
import hashlib
from pathlib import Path
import re
import subprocess
import time


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(data)
    return h.hexdigest()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb', required=True)
    p.add_argument('--serial', required=True)
    p.add_argument('--remote', required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--gzip-sha', required=True)
    p.add_argument('--image-sha', required=True)
    p.add_argument('--workers', type=int, default=4)
    args = p.parse_args()
    if not re.fullmatch(r'/data/local/tmp/workshop-review-[a-zA-Z0-9-]+', args.remote):
        p.error('Unsupported staging directory')
    adb = [args.adb, '-s', args.serial]
    manifest = subprocess.check_output(adb + ['shell', 'cd ' + args.remote + ' && sha256sum chunk-*'], timeout=60, text=True)
    entries = re.findall(r'(?m)^([0-9a-f]{64})\s+(chunk-[a-z]{2})\s*$', manifest)
    if not entries or len({name for _, name in entries}) != len(entries):
        raise RuntimeError('Invalid chunk manifest')
    entries.sort(key=lambda e: e[1])
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / 'manifest.sha256').write_text(''.join(f'{sha}  {name}\n' for sha, name in entries))

    def pull(entry):
        sha, name = entry
        dest = args.output / name
        if dest.is_file() and digest(dest) == sha:
            return name + ' cached'
        partial = dest.with_suffix('.part')
        for attempt in range(4):
            try:
                subprocess.run(adb + ['pull', args.remote + '/' + name, str(partial)], check=True, timeout=300, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
                if digest(partial) != sha:
                    raise RuntimeError('Chunk SHA mismatch')
                partial.replace(dest)
                return name + ' verified'
            except (subprocess.SubprocessError, RuntimeError) as failure:
                print(f'{name} attempt {attempt + 1}: {type(failure).__name__}', flush=True)
                if attempt == 3:
                    raise
                time.sleep(2)

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        for result in pool.map(pull, entries):
            print(result, flush=True)
    packed = args.output / 'complete.img.gz'
    with packed.open('wb') as out:
        for _, name in entries:
            with (args.output / name).open('rb') as source:
                for data in iter(lambda: source.read(1024 * 1024), b''):
                    out.write(data)
    if digest(packed) != args.gzip_sha:
        raise RuntimeError('Complete compressed SHA mismatch')
    raw = args.output / 'complete.img'
    count = 0
    with gzip.open(packed, 'rb') as source, raw.open('wb') as out:
        for data in iter(lambda: source.read(1024 * 1024), b''):
            count += len(data)
            if count > 1073741824:
                raise RuntimeError('Decompressed image exceeds partition size')
            out.write(data)
    if count != 1073741824 or digest(raw) != args.image_sha:
        raise RuntimeError('Complete raw SHA/size mismatch')
    print(f'PASS full image: {raw} ({count} bytes)', flush=True)


if __name__ == '__main__':
    main()
