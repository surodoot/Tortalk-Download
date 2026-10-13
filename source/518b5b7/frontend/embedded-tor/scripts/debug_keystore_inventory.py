#!/usr/bin/env python3
"""Record/check existing default debug-keystore paths without modifying any key."""
import argparse
import hashlib
import json
import os
from pathlib import Path


def inventory():
    paths = {Path.home() / '.android' / 'debug.keystore'}
    if os.environ.get('ANDROID_USER_HOME'):
        paths.add(Path(os.environ['ANDROID_USER_HOME']) / 'debug.keystore')
    for variable in ('ANDROID_PREFS_ROOT', 'ANDROID_SDK_HOME'):
        if os.environ.get(variable):
            paths.add(Path(os.environ[variable]) / '.android' / 'debug.keystore')
    result = {}
    for path in sorted(paths):
        entry = {'exists': path.exists(), 'symlink': path.is_symlink()}
        if path.is_file():
            entry.update({'size': path.stat().st_size, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
        result[str(path)] = entry
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compare', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    current = inventory()
    args.output.write_text(json.dumps(current, indent=2) + '\n')
    if args.compare and json.loads(args.compare.read_text()) != current:
        raise SystemExit('FAIL: a default debug-keystore path changed during unsigned packaging')
    print('PASS: default debug-keystore inventory ' + ('unchanged' if args.compare else 'recorded'))


if __name__ == '__main__':
    main()
