#!/usr/bin/env python3
"""Compile the probe against AOSP's API26 surface. No SDK install, device, Tor, or signing.

Use --fetch only to download the two pinned public AOSP compile fixtures. Neither fixture
is packaged in the app. The lambda stub supplies javac's bootstrap declaration; AGP/D8
performs actual lambda desugaring separately. This does not audit Netty's runtime internals.
"""
import argparse
import base64
import hashlib
from pathlib import Path
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
AOSP = 'https://android.googlesource.com/platform/prebuilts/sdk/+/refs/heads/main/'
FIXTURES = {
    'android-api26.jar': ('26/public/android.jar', 'cdc1846376a14b0370cc63454a129606b4a52cc50ada75ef0d4cf956b1ad2daa'),
    'core-lambda-stubs.jar': ('tools/core-lambda-stubs.jar', '48ebd3b6614a8e4367d1dbda1222aed5ffb6b874214922412af5c9fbcd5e877a'),
}
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--fetch', action='store_true', help='Fetch only missing pinned public compile fixtures')
parser.add_argument('--netty-dir', type=Path, default=ROOT.parent / '.tooling/netty')
args = parser.parse_args()
cache = ROOT / '.tooling'
cache.mkdir(exist_ok=True)
for name, (source, digest) in FIXTURES.items():
    path = cache / name
    if not path.exists() and args.fetch:
        with urllib.request.urlopen(AOSP + source + '?format=TEXT', timeout=60) as response:
            data = base64.b64decode(response.read(), validate=True)
        assert hashlib.sha256(data).hexdigest() == digest, 'AOSP fixture changed; review provenance'
        path.write_bytes(data)
    if not path.is_file():
        raise SystemExit(f'Missing pinned compile fixture {path}; rerun with --fetch to retrieve it')
    assert hashlib.sha256(path.read_bytes()).hexdigest() == digest, name
netty = []
for line in (ROOT / 'scripts/netty-artifacts.sha256').read_text().splitlines():
    digest, artifact = line.split()
    path = args.netty_dir / artifact.rsplit('/', 1)[1]
    assert hashlib.sha256(path.read_bytes()).hexdigest() == digest, path
    netty.append(str(path))
sources = [ROOT / 'src/main/java/dev/gyeopdam/embeddedtor' / name for name in
           ['TorReadinessGate.java', 'OnionProbeSession.java', 'FixedOnionProbe.java', 'DiagnosticLog.java',
            'TorSocketEndpoint.java', 'TorSocketPaths.java', 'TorSocketIo.java', 'AndroidTorSocket.java',
            'TorSocketChannel.java', 'TorSocketEventLoopGroup.java', 'OnionApiRequest.java', 'OnionApiTransport.java']]
sources.append(ROOT / 'src/androidTest/java/dev/gyeopdam/embeddedtor/AndroidUnixSocketRuntimeChecks.java')
subprocess.run(['java', 'com.sun.tools.javac.Main', '-source', '8', '-target', '8',
                '-bootclasspath', ':'.join(str(cache / name) for name in reversed(FIXTURES)),
                '-cp', ':'.join(netty), '-d', str(cache / 'probe-api26'), *map(str, sources)], check=True)
print('PASS: Unix adapter, API/probe classes and prepared socket checks compile against the official AOSP API26 public surface')
print('NOT TESTED HERE: AGP/D8, Netty Android runtime internals, device lifecycle, real Tor/onion exchange')
