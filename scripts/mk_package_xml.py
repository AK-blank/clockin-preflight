#!/usr/bin/env python3
"""Generate an SDK `package.xml` from a downloaded repository index.

Why this exists
---------------
We install Android SDK packages by hand (curl from a fast mirror) instead of via
`sdkmanager`, because dl.google.com is throttled to ~60 B/s on this network while
mirrors.cloud.tencent.com/AndroidSDK runs at ~4.4 MB/s.

cmdline-tools' *legacy* loader understands `source.properties` for platform /
build-tools / system-image packages, but NOT for the `emulator` package (type
`generic:genericDetailsType`). Without a `package.xml`, `avdmanager` aborts with:

    Error: "emulator" package must be installed!

So this script converts the `<remotePackage path="...">` element out of the
repository index into the `<localPackage>` element that sdkmanager would have
written. Remote-only children (`<archives>`, `<channelRef>`, `<uses-license>`)
are dropped.

Usage:
    mk_package_xml.py <repo-index.xml> <package-path> <dest-package.xml>

Example:
    mk_package_xml.py tools/mirror/repository2-3.xml emulator \\
        toolchain/sdk/emulator/package.xml
"""
import os
import re
import sys

REMOTE_ONLY = ("archives", "channelRef", "uses-license")


def main() -> int:
    if len(sys.argv) != 4:
        print(__doc__)
        return 2
    src, pkg_path, dest = sys.argv[1], sys.argv[2], sys.argv[3]
    xml = open(src, encoding="utf-8").read()

    root = re.search(r"<(\w[\w.-]*:sdk-repository)\b[^>]*>", xml)
    if not root:
        print(f"error: no <sdk-repository> root element in {src}")
        return 1
    root_tag, root_attrs = root.group(1), root.group(0)

    block = re.search(
        r'<remotePackage\s+path="%s"\s*>(.*?)</remotePackage>' % re.escape(pkg_path),
        xml,
        re.S,
    )
    if not block:
        print(f"error: path {pkg_path!r} not found in {src}")
        return 1
    body = block.group(1)

    for child in REMOTE_ONLY:
        body = re.sub(r"<%s\b.*?</%s>" % (child, child), "", body, flags=re.S)
        body = re.sub(r"<%s\b[^>]*/>" % child, "", body, flags=re.S)

    # Strip the source indentation so the emitted file reads cleanly.
    indent = "    "
    lines = [ln.strip() for ln in body.strip().splitlines() if ln.strip()]
    pretty = "\n".join(indent * 2 + ln for ln in lines)

    out = (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        f"{root_attrs}\n"
        f'  <localPackage path="{pkg_path}" obsolete="false">\n'
        f"{pretty}\n"
        "  </localPackage>\n"
        f"</{root_tag}>\n"
    )
    os.makedirs(os.path.dirname(dest) or ".", exist_ok=True)
    with open(dest, "w", encoding="utf-8") as fh:
        fh.write(out)
    print(f"wrote {dest} ({len(out)} bytes) for {pkg_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
