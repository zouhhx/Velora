#!/usr/bin/env python3
"""Report 16 KB page-size readiness of the native libraries in a built APK.

Android 15/16 devices can use 16 KB memory pages. Every PT_LOAD segment must have
p_align >= 16 KB; a 4 KB-aligned prebuilt library forces the app into page-size
compatibility mode and shows the system warning dialog.

Usage:
    python tools/check_16kb_alignment.py app/build/outputs/apk/debug/app-debug.apk

Exit code 1 if any arm64-v8a library is not 16 KB aligned. The current Baidu Navi
library (libapp_BaiduNaviApplib.so) is known to be 4 KB; this check exists so a
future SDK update can be verified immediately.
"""
import struct
import sys
import zipfile

MIN_ALIGN = 16 * 1024


def load_aligns(data: bytes):
    if data[:4] != b"\x7fELF" or data[4] != 2:
        return []
    endian = "<" if data[5] == 1 else ">"
    e_phoff = struct.unpack_from(endian + "Q", data, 32)[0]
    e_phentsize = struct.unpack_from(endian + "H", data, 54)[0]
    e_phnum = struct.unpack_from(endian + "H", data, 56)[0]
    aligns = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type = struct.unpack_from(endian + "I", data, off)[0]
        if p_type == 1:  # PT_LOAD
            aligns.append(struct.unpack_from(endian + "Q", data, off + 48)[0])
    return aligns


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    bad = []
    with zipfile.ZipFile(sys.argv[1]) as apk:
        names = sorted(
            n for n in apk.namelist()
            if n.startswith("lib/arm64-v8a/") and n.endswith(".so")
        )
        if not names:
            print("no arm64-v8a libraries found")
            return 1
        for name in names:
            aligns = load_aligns(apk.read(name))
            if not aligns:
                print(f"SKIP  {name} (not a 64-bit ELF)")
                continue
            ok = all(a >= MIN_ALIGN for a in aligns)
            print(f"{'OK  ' if ok else '4KB '} {name}  PT_LOAD={[hex(a) for a in aligns]}")
            if not ok:
                bad.append(name)
    if bad:
        print(f"\n{len(bad)} library(ies) below 16 KB: {', '.join(bad)}")
        return 1
    print("\nall arm64-v8a libraries are 16 KB aligned")
    return 0


if __name__ == "__main__":
    sys.exit(main())
