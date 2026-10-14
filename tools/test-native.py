#!/usr/bin/env python3
"""Synthetic regular-file tests only. Never opens a device or contacts a vehicle."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import struct
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[1]
UUID = "12345678-1234-1234-1234-123456789abc"
EPOCH = 1700000000


def run(*args, good=True):
    result = subprocess.run([str(a) for a in args], text=True, capture_output=True)
    if (result.returncode == 0) != good:
        raise AssertionError(f"Unexpected status {result.returncode}: {args}\n{result.stdout}\n{result.stderr}")
    return result.stdout


def fixture(path, password="fixture_hash", extra="untouched", enabled=False, overrides=None):
    directories = [".", "etc", "etc/default", "etc/systemd", "etc/systemd/system", "etc/systemd/system/sockets.target.wants", "lib", "lib/systemd", "lib/systemd/system", "lib/systemd/system-preset", "usr", "usr/bin"]
    files = {
        "etc/shadow": f"root:{password}:19000:0:99999:7:::\nuser:!:19000:0:99999:7:::\n",
        "etc/passwd": "root:x:0:0:root:/root:/bin/sh\n",
        "etc/build.json": '{"machine":"structure-fixture-not-a-car","version":"test"}\n',
        "etc/default/dropbear": 'DROPBEAR_EXTRA_ARGS=" -B"\n',
        "lib/systemd/system/dropbear.socket": "[Socket]\nListenStream=22\nAccept=yes\n[Install]\nWantedBy=sockets.target\n",
        "lib/systemd/system/dropbear@.service": "[Service]\nEnvironmentFile=-/etc/default/dropbear\nExecStart=-/usr/sbin/dropbear -i -r ${DROPBEAR_RSAKEY_DIR}/dropbear_rsa_host_key $DROPBEAR_EXTRA_ARGS\nStandardInput=socket\n",
        "lib/systemd/system-preset/98-dropbear.preset": ("enable" if enabled else "disable")+" dropbear.socket\n",
        "usr/bin/sentinel": extra,
    }
    files.update(overrides or {})
    with tarfile.open(path, "w", format=tarfile.PAX_FORMAT) as tar:
        for directory in directories:
            info=tarfile.TarInfo(directory);info.type=tarfile.DIRTYPE;info.mode=0o755;info.mtime=EPOCH;tar.addfile(info)
        for name, value in files.items():
            payload=value.encode();info=tarfile.TarInfo(name);info.size=len(payload);info.mode=0o600 if name=="etc/shadow" else 0o644;info.uid=0;info.gid=0;info.mtime=EPOCH
            info.pax_headers={"SCHILY.xattr.user.workshop-test":"preserve-me"};tar.addfile(info,io.BytesIO(payload))
        links={"etc/systemd/system/sockets.target.wants/tftpd-hpa.socket":"/lib/systemd/system/tftpd-hpa.socket"}
        if enabled:links["etc/systemd/system/sockets.target.wants/dropbear.socket"]="/lib/systemd/system/dropbear.socket"
        for name,target in links.items():
            info=tarfile.TarInfo(name);info.type=tarfile.SYMTYPE;info.linkname=target;info.mode=0o777;info.mtime=EPOCH;tar.addfile(info)


def build(mkfs, tar, image):
    run(mkfs,"--quiet","--tar=f","-b4096","-Elegacy-compress,^xattr-name-filter","--preserve-mtime",f"-T{EPOCH}","--mkfs-time",f"-U{UUID}",image,tar)


def main():
    parser=argparse.ArgumentParser();parser.add_argument("--real-original",type=Path);parser.add_argument("--real-candidate",type=Path);parser.add_argument("--keep-fixtures",type=Path);args=parser.parse_args()
    image_tool=ROOT/"build/host/image_tool";mkfs=ROOT/"build/host/mkfs_erofs";fsck=ROOT/"build/host/fsck_erofs"
    holder=tempfile.TemporaryDirectory(prefix="workshop-test-") if not args.keep_fixtures else None
    work=args.keep_fixtures or Path(holder.name)
    if args.keep_fixtures and work.exists() and any(work.iterdir()):raise ValueError("Refusing to overwrite a non-empty fixture directory")
    work.mkdir(parents=True,exist_ok=True)
    source_tar=work/"source.tar";fixture(source_tar);source=work/"source.erofs";build(mkfs,source_tar,source)
    initial=hashlib.sha256(source.read_bytes()).hexdigest();assert json.loads(run(image_tool,"inspect",source,0))["rootPassword"]=="SET"
    for ssh,clear in [(1,0),(0,1),(1,1)]:
        tar=work/f"change-{ssh}-{clear}.tar";candidate=work/f"change-{ssh}-{clear}.erofs"
        run(image_tool,"export",source,0,ssh,clear,tar);build(mkfs,tar,candidate);run(fsck,"--extract",candidate)
        run(image_tool,"verify",source,0,ssh,clear,candidate,0)
        state=json.loads(run(image_tool,"inspect",candidate,0));assert state["socket"]==("ENABLED" if ssh else "DISABLED");assert state["rootPassword"]==("EMPTY" if clear else "SET")
        # An already-applied transform remains verifiable without further edits.
        run(image_tool,"verify",candidate,0,ssh,clear,candidate,0)
    assert hashlib.sha256(source.read_bytes()).hexdigest()==initial
    fixture(work/"wrong.tar",extra="unauthorized-content",enabled=True);build(mkfs,work/"wrong.tar",work/"wrong.erofs");run(image_tool,"verify",source,0,1,0,work/"wrong.erofs",0,good=False)
    fixture(work/"locked.tar",password="!locked");build(mkfs,work/"locked.tar",work/"locked.erofs");run(image_tool,"export",work/"locked.erofs",0,0,1,work/"reject.tar",good=False)
    malformed=[
        {"lib/systemd/system/dropbear.socket":"[Socket]\n#ListenStream=22\nListenStream=2222\nAccept=yes\n"},
        {"lib/systemd/system/dropbear.socket":"[Socket]\nListenStream=22\nListenStream=2222\nAccept=yes\n"},
        {"etc/default/dropbear":'#DROPBEAR_EXTRA_ARGS=" -B"\nDROPBEAR_EXTRA_ARGS=" -w"\n'},
        {"etc/default/dropbear":'DROPBEAR_EXTRA_ARGS=" -B"\nDROPBEAR_EXTRA_ARGS=" -w"\n'},
        {"etc/passwd":"root:x:1000:0:root:/root:/bin/sh\n"},
        {"etc/passwd":"root::0:0:root:/root:/bin/sh\n"},
        {"etc/systemd/system/dropbear.socket":"[Socket]\nListenStream=2222\n"},
        {"lib/systemd/system/dropbear@.service":"[Service]\nExecStart=/usr/sbin/dropbear -w\n"},
    ]
    for index,override in enumerate(malformed):
        tar=work/f"policy-{index}.tar";image=work/f"policy-{index}.erofs"
        fixture(tar,overrides=override);build(mkfs,tar,image)
        run(image_tool,"export",image,0,1,1,work/f"policy-{index}-rejected.tar",good=False)
    truncated=work/"truncated.erofs";truncated.write_bytes(source.read_bytes()[:1024]);run(image_tool,"inspect",truncated,0,good=False)
    # Regular sparse backing file: FIP header and opaque tail, not Android sparse format.
    payload=source.read_bytes();header=bytearray(4096);struct.pack_into("<IIQ",header,0,0xaa640001,0x12345678,0);header[16:32]=bytes.fromhex("8a50467f899d4ab39e978fe0ed6d1dcb");struct.pack_into("<QQQ",header,32,4096,len(payload),0);struct.pack_into("<Q",header,72,4096+len(payload))
    original=work/"full-source.img"
    with original.open("wb") as output:
        output.write(header);output.write(payload);output.seek(1024*1024);output.write(b"opaque-tail-must-survive");output.truncate(1024**3)
    result=work/"full-candidate.img";run(ROOT/"build/host/fip_pack",original,work/"change-1-1.erofs",result);run(ROOT/"build/host/fip_verify",original,work/"change-1-1.erofs",result)
    run(image_tool,"verify",original,4096,1,1,result,4096)
    run(ROOT/"build/host/fip_pack",original,work/"change-1-1.erofs",result,good=False)
    print("PASS: synthetic SSH-only / password-only / combined / idempotence / metadata / rejection / FIP packing tests")
    if args.real_original and args.real_candidate:
        run(image_tool,"verify",args.real_original,4096,1,0,args.real_candidate,4096)
        print("PASS: read-only comparison of supplied original and previously verified SSH candidate")
    if holder:holder.cleanup()


if __name__=="__main__":main()
