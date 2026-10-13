#!/usr/bin/env python3
"""Verify unsigned instrumentation packaging, never install or execute it."""
import argparse
import hashlib
import json
import re
import struct
import xml.etree.ElementTree as ET
from pathlib import Path
from zipfile import ZipFile

APP = 'dev.gyeopdam.embeddedtor.experiment'
RUNNER = 'dev.gyeopdam.embeddedtor.TorTalkRuntimeInstrumentation'
ANDROID = '{http://schemas.android.com/apk/res/android}'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def unsigned_zip(path):
    data = path.read_bytes()
    require(22 <= len(data) <= 160 * 1024 * 1024, 'invalid APK size')
    tail_start = max(0, len(data) - 65557)
    eocd = data.rfind(b'PK\x05\x06', tail_start)
    require(eocd >= 0 and eocd + 22 <= len(data), 'missing ZIP end record')
    disk, central_disk, count_disk, count, central_size, central_offset, comment_size = struct.unpack_from('<HHHHIIH', data, eocd + 4)
    require(disk == central_disk == 0 and count_disk == count and count != 0xffff, 'multipart/ZIP64 APK unsupported')
    require(eocd + 22 + comment_size == len(data), 'invalid ZIP end record')
    require(central_offset + central_size == eocd and central_offset >= 16, 'invalid central directory bounds')
    require(data[central_offset - 16:central_offset] != b'APK Sig Block 42', 'APK signing block found')
    with ZipFile(path) as archive:
        names = archive.namelist()
        require(len(names) == count and len(names) == len(set(names)), 'duplicate/mismatched ZIP entries')
        require(not any(re.fullmatch(r'META-INF/[^/]+\.(SF|RSA|DSA|EC)', n, re.I) for n in names), 'v1 signature found')
        require(not any(n.startswith('META-INF/SIG-') for n in names), 'v1 signature artifact found')
        require('AndroidManifest.xml' in names and 'classes.dex' in names, 'missing manifest/DEX')
        require(archive.testzip() is None, 'corrupt ZIP entry')
    require(not path.with_suffix(path.suffix + '.idsig').exists(), 'v4 signature sidecar found')
    return {'file': path.name, 'sha256': hashlib.sha256(data).hexdigest(), 'size_bytes': len(data), 'signing': 'unsigned'}


def verify_test(apk, manifest):
    result = unsigned_zip(apk)
    with ZipFile(apk) as archive:
        names = archive.namelist()
        require(not any(n.startswith('lib/') or n.endswith(('.so', '.dll', '.dylib')) for n in names), 'test APK must not duplicate native libraries')
        dex = b''.join(archive.read(n) for n in names if re.fullmatch(r'classes\d*\.dex', n))
        require(b'Ldev/gyeopdam/embeddedtor/TorTalkRuntimeInstrumentation;' in dex, 'instrumentation runner missing from DEX')
    tree = ET.parse(manifest).getroot()
    require(tree.tag == 'manifest' and tree.get('package') == APP + '.test', 'wrong instrumentation package')
    runners = tree.findall('instrumentation')
    require(len(runners) == 1, 'expected exactly one instrumentation runner')
    require(runners[0].get(ANDROID + 'name') == RUNNER, 'wrong instrumentation runner')
    require(runners[0].get(ANDROID + 'targetPackage') == APP, 'wrong instrumentation target')
    result.update({'package': APP + '.test', 'target_package': APP, 'runner': RUNNER, 'runtime_executed': False})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app', type=Path, required=True)
    parser.add_argument('--test', type=Path, required=True)
    parser.add_argument('--test-manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = {'app': unsigned_zip(args.app), 'instrumentation': verify_test(args.test, args.test_manifest)}
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print('PASS: both APKs unsigned; instrumentation package/target/runner and DEX verified; no runtime execution')


if __name__ == '__main__':
    main()
