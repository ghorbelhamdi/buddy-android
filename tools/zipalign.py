#!/usr/bin/env python3
"""Minimal zipalign: rewrites a zip so every STORED entry's data starts on a 4-byte
boundary (16 KiB for .so files), as Android requires for resources.arsc. Usage:
zipalign.py in.apk out.apk"""
import sys
import zipfile


def align(src, dst):
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w") as zout:
        for info in zin.infolist():
            data = zin.read(info.filename)
            out = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            out.compress_type = info.compress_type
            out.external_attr = info.external_attr
            out.create_system = info.create_system
            out.extra = b""
            if info.compress_type == zipfile.ZIP_STORED:
                boundary = 16384 if info.filename.endswith(".so") else 4
                header_len = 30 + len(info.filename.encode("utf-8"))
                start = zout.fp.tell() + header_len
                pad = (-start) % boundary
                out.extra = b"\0" * pad
            zout.writestr(out, data)


if __name__ == "__main__":
    align(sys.argv[1], sys.argv[2])
