#!/usr/bin/env python3
"""Source policy: app-owned production code must not opt into Signal logging.

This is a deliberately bounded static guard, not a proof of runtime silence.
See docs/signal-logging-boundary.md for the supported source boundary and gaps.
"""
import argparse
import json
from pathlib import Path
import re
import sys

MODULES = ('app', 'embedded-tor', 'core', 'tor-transport')
EXTENSIONS = {'.java', '.kt', '.c', '.cc', '.cpp', '.h', '.hpp', '.rs', '.m', '.mm'}
TEST_SOURCE_SETS = frozenset({'test', 'androidTest'})
UNICODE_ESCAPE = re.compile(r'\\u+([0-9a-fA-F]{4})')
PATTERNS = (
    ('SIGNAL_LOGGER_PROVIDER_REFERENCE', re.compile(r'\b(?:SignalProtocolLoggerProvider|AndroidSignalProtocolLogger)\b')),
    ('SIGNAL_LOGGING_NAMESPACE_REFERENCE', re.compile(r'\borg\s*\.\s*signal\s*\.\s*libsignal\s*\.\s*protocol\s*\.\s*logging\s*\.')),
    ('SIGNAL_NATIVE_ENTRYPOINT_REFERENCE', re.compile(r'\borg\s*\.\s*signal\s*\.\s*libsignal\s*\.\s*internal\s*\.\s*(?:Native\b|\*)')),
    ('SIGNAL_NATIVE_LOGGER_METHOD_REFERENCE', re.compile(r'\b(?:Logger_Initialize|Logger_SetMaxLevel|Java_org_signal_libsignal_internal_Native_Logger_1Initialize|Java_org_signal_libsignal_internal_Native_Logger_1SetMaxLevel)\b')),
)


def source_code(text, kotlin=True):
    # Java processes Unicode escapes before lexing. Kotlin does not, so leave
    # its escaped dollar signs/quotes to the string scanner below.
    if not kotlin:
        text = UNICODE_ESCAPE.sub(lambda m: chr(int(m.group(1), 16)), text)
    output = ['\n' if char == '\n' else ' ' for char in text]
    length = len(text)

    def copy(start, end):
        output[start:end] = text[start:end]

    def skip_comment(index):
        if text.startswith('//', index):
            end = text.find('\n', index + 2)
            return length if end < 0 else end
        index += 2
        depth = 1
        while index < length and depth:
            if kotlin and text.startswith('/*', index):
                depth += 1
                index += 2
            elif text.startswith('*/', index):
                depth -= 1
                index += 2
            else:
                index += 1
        return index

    def string(index, triple=False):
        delimiter = '"' * (3 if triple else 1)
        index += len(delimiter)
        while index < length:
            if text.startswith(delimiter, index):
                return index + len(delimiter)
            if not triple and text[index] == '\\':
                index += 2  # Includes an escaped Kotlin dollar sign.
            elif kotlin and text.startswith('${', index):
                index = code(index + 2, stop_at_closing_brace=True)
            elif kotlin and text[index] == '$':
                match = re.match(r'[A-Za-z_][A-Za-z_0-9]*', text[index + 1:])
                if match:
                    end = index + 1 + len(match.group())
                    copy(index + 1, end)
                    index = end
                else:
                    index += 1
            else:
                index += 1
        return index

    def code(index, stop_at_closing_brace=False):
        depth = 0
        while index < length:
            if text.startswith(('//', '/*'), index):
                index = skip_comment(index)
            elif text.startswith('"' * 3, index):
                index = string(index, triple=True)
            elif text[index] == '"':
                index = string(index)
            elif text[index] == "'":
                # Java/Kotlin character literals; do not swallow Rust lifetimes.
                match = re.match(r"'(?:\\.|[^'\\\n])'", text[index:])
                if match:
                    index += len(match.group())
                else:
                    copy(index, index + 1)
                    index += 1
            elif text[index] == '}' and stop_at_closing_brace and depth == 0:
                return index + 1
            else:
                if text[index] == '{':
                    depth += 1
                elif text[index] == '}':
                    depth -= 1
                copy(index, index + 1)
                index += 1
        return index

    code(0)
    # Kotlin quoted identifiers are executable identifiers, not literal data.
    return ''.join(output).replace('`', ' ')


def violations(text, kotlin=True):
    code = source_code(text, kotlin=kotlin)
    return [{'code': label, 'line': code.count('\n', 0, match.start()) + 1}
            for label, pattern in PATTERNS for match in pattern.finditer(code)]


def production_files(root):
    for module in MODULES:
        src = root / 'frontend' / module / 'src'
        if not src.exists():
            continue
        for source_set in sorted(src.iterdir()):
            if not source_set.is_dir():
                continue
            if source_set.name in TEST_SOURCE_SETS:
                continue
            for path in sorted(source_set.rglob('*')):
                if path.is_file() and path.suffix in EXTENSIONS:
                    yield path


def check(root):
    files = list(production_files(root))
    if not files:
        return {'pass': False, 'files_checked': 0,
                'violations': [{'path': '', 'line': 0, 'code': 'PRODUCTION_SOURCE_MISSING'}]}
    found = []
    for path in files:
        for violation in violations(path.read_text(encoding='utf-8'), kotlin=path.suffix == '.kt'):
            found.append({'path': str(path.relative_to(root)), **violation})
    return {'pass': not found, 'files_checked': len(files), 'violations': found}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[3])
    args = parser.parse_args()
    result = check(args.root.resolve())
    # Never echo source text, string literals, values or log records.
    print(json.dumps(result, indent=2))
    return 0 if result['pass'] else 1


if __name__ == '__main__':
    sys.exit(main())
