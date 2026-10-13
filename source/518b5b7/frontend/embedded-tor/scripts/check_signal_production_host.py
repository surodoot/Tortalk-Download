#!/usr/bin/env python3
"""Offline, Linux x86_64, synthetic-only production Signal JNI host check.

Uses the existing host dependency provisioning step. Never downloads, initializes
Signal logging, retains raw process output, or claims Android runtime evidence.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import resource
import selectors
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import zipfile

EMBEDDED = Path(__file__).resolve().parents[1]
FRONTEND = EMBEDDED.parent
HARNESS = EMBEDDED / "integration/signal-production-host"
SIGNAL_SHA256 = "520a16906c45e1629ed05c98b12556322ccc489a82016bba921954e312c882c4"
NATIVE_NAME = "libsignal_jni_amd64.so"
NATIVE_SHA256 = "1e690028660a8c29e07d02837aa7cd495fd201f476a140b645d293d2abaf9072"
DEPENDENCIES = (
    ("crypto-spike/libsignal-client-0.105.0.jar", SIGNAL_SHA256),
    ("crypto-spike/kotlin-stdlib-2.2.20.jar", "8836ccffd3585fadda9901244b20d42901d2f3cd581058d8434e2ffabcf3a3e7"),
    ("kotlin/junit-4.13.2.jar", "8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3"),
    ("kotlin/hamcrest-core-1.3.jar", "66fdef91e9739348df7a096aa384a5685f4e875584cce89386a7a47251c4d8e9"),
)
JAVA_FAILURE_CODES = {
    "HOST_SETUP_FAILED", "ARGUMENTS_INVALID", "TESTING_RESOURCE_VISIBLE",
    "CLASSLOADER_MISMATCH", "PRODUCTION_RESOURCE_MISSING", "UNPINNED_RESOURCE_VISIBLE",
    "NATIVE_FALLBACK_ALLOWED", "TESTING_JNI_MAPPED", "UNEXPECTED_JNI_MAPPING",
    "UNOWNED_JNI_MAPPING", "PRODUCTION_JNI_NOT_MAPPED", "MAPPED_JNI_HASH_MISMATCH",
    "TESTING_DENIAL_NOT_EXERCISED", "SCENARIO_COUNT_MISMATCH", "HOST_SCENARIO_FAILED",
}
SUCCESS_DETAILS = {
    "status": "passed", "synthetic_scenario_groups": 8,
    "existing_signal_client_tests": 32, "production_jni_mapped": True,
    "testing_jni_mapped": False, "provider_null_before_and_after": True,
    "testing_resource_denial_exercised": True, "resource_policy_verified": True,
}


class CheckFailure(Exception):
    """Fixed, public failure code; never an exception message from tested code."""


def sha256(path):
    with Path(path).open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def isolated_environment():
    env = os.environ.copy()
    for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH",
                 "LD_PRELOAD", "LD_LIBRARY_PATH", "LD_AUDIT"):
        env.pop(name, None)
    return env


def no_core_files():
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))


def run_bounded(command, cwd, *, timeout=120, output_limit=65536):
    """Drain both descriptors without keeping their content, even on failures.

    Counters saturate at limit+1. Kill the whole owned process group on a deadline
    or output overflow. Descriptor closure has a separate two-second deadline.
    """
    counts = {"stdout_bytes": 0, "stderr_bytes": 0}
    timed_out = overflow = False
    process = subprocess.Popen(command, cwd=cwd, env=isolated_environment(),
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               start_new_session=True, preexec_fn=no_core_files)
    deadline = time.monotonic() + timeout
    stopped_at = None
    with selectors.DefaultSelector() as selector:
        for stream, key in ((process.stdout, "stdout_bytes"), (process.stderr, "stderr_bytes")):
            os.set_blocking(stream.fileno(), False)
            selector.register(stream, selectors.EVENT_READ, key)
        while selector.get_map():
            now = time.monotonic()
            if stopped_at is None and (now >= deadline or overflow):
                timed_out = now >= deadline
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                stopped_at = now
            if stopped_at is not None and now - stopped_at >= 2:
                break
            for key, _ in selector.select(0.05):
                chunk = os.read(key.fileobj.fileno(), 16384)
                if chunk:
                    counts[key.data] = min(output_limit + 1, counts[key.data] + len(chunk))
                    overflow |= sum(counts.values()) > output_limit
                    # Do not decode, print, hash, or persist process-output content.
                else:
                    selector.unregister(key.fileobj)
    process.stdout.close()
    process.stderr.close()
    try:
        returncode = process.wait(timeout=max(0.01, deadline - time.monotonic()) if stopped_at is None else 2)
    except subprocess.TimeoutExpired:
        timed_out = True
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        returncode = process.wait(timeout=2)
    return {"exit_code": returncode, **counts, "timed_out": timed_out, "output_limit_exceeded": overflow}


def verify_dependencies(tooling):
    # Require agreement with both existing provision manifests before compiling.
    manifests = (FRONTEND / "crypto-spike/artifacts.sha256", FRONTEND / "scripts/kotlin-test-artifacts.sha256")
    pins = {}
    for manifest in manifests:
        for line in manifest.read_text().splitlines():
            digest, location = line.split()
            pins[location.rsplit("/", 1)[-1]] = digest
    libraries = []
    for relative, digest in DEPENDENCIES:
        path = tooling / relative
        if pins.get(path.name) != digest:
            raise CheckFailure("PROVISION_PIN_MISMATCH")
        if not path.is_file() or sha256(path) != digest:
            raise CheckFailure("DEPENDENCY_HASH_MISMATCH")
        libraries.append(path.resolve())
    with zipfile.ZipFile(libraries[0]) as jar:
        if jar.namelist().count(NATIVE_NAME) != 1 or "libsignal_jni_testing_amd64.so" not in jar.namelist():
            raise CheckFailure("JNI_RESOURCE_SET_MISMATCH")
        with jar.open(NATIVE_NAME) as native:
            if hashlib.file_digest(native, "sha256").hexdigest() != NATIVE_SHA256:
                raise CheckFailure("JNI_RESOURCE_HASH_MISMATCH")
    return libraries


def run_check(tooling, *, harness=HARNESS):
    result = {"schema_version": 1, "status": "failed", "scope": "linux-x86_64-synthetic-host",
              "android_runtime_executed": False, "logger_initialization_requested": False,
              "raw_process_output_retained": False}
    try:
        if platform.system() != "Linux" or platform.machine() not in ("x86_64", "amd64"):
            raise CheckFailure("UNSUPPORTED_HOST")
        java = shutil.which("java")
        if not java:
            raise CheckFailure("JDK_UNAVAILABLE")
        libraries = verify_dependencies(Path(tooling))
        result["dependency_sha256"] = {Path(relative).name: digest for relative, digest in DEPENDENCIES}
        result["production_jni_sha256"] = NATIVE_SHA256
        sources = [EMBEDDED / "src/main/java/dev/gyeopdam/embeddedtor/SignalClient.java",
                   EMBEDDED / "src/test/java/dev/gyeopdam/embeddedtor/SignalClientTest.java",
                   Path(harness) / "ProductionOnlyLoader.java", Path(harness) / "ProductionPrivacyHost.java"]
        result["source_sha256"] = {path.name: sha256(path) for path in sources}
        with tempfile.TemporaryDirectory(prefix="tortalk-signal-production-") as directory:
            work = Path(directory)
            classes, native = work / "classes", work / "native"
            classes.mkdir()
            native.mkdir()
            base = [java, "-XX:ErrorFile=/dev/null", "-XX:-CreateCoredumpOnCrash",
                    "-XX:-HeapDumpOnOutOfMemoryError", "-Xmx512m",
                    "-Djava.library.path=" + str(work / "no-libraries"), "-Djava.io.tmpdir=" + str(native)]
            result["compile"] = run_bounded(base + ["com.sun.tools.javac.Main", "-source", "17", "-target", "17",
                "-cp", os.pathsep.join(map(str, libraries)), "-d", str(classes), *map(str, sources)], work, timeout=60)
            compile_result = result["compile"]
            if compile_result["exit_code"] != 0 or compile_result["timed_out"] or compile_result["output_limit_exceeded"]:
                raise CheckFailure("COMPILATION_FAILED")
            details = work / "result.json"
            result["host"] = run_bounded(base + ["-cp", str(classes), "ProductionOnlyLoader", str(classes),
                                                  *map(str, libraries), str(details)], work)
            host = result["host"]
            if host["timed_out"]:
                raise CheckFailure("HOST_TIMEOUT")
            if host["output_limit_exceeded"]:
                raise CheckFailure("HOST_OUTPUT_LIMIT_EXCEEDED")
            if host["stdout_bytes"] or host["stderr_bytes"]:
                raise CheckFailure("HOST_OUTPUT_OBSERVED")
            if not details.is_file() or details.stat().st_size > 2048:
                raise CheckFailure("HOST_RESULT_MISSING_OR_OVERSIZED")
            detail = json.loads(details.read_text())
            if host["exit_code"] != 0:
                if set(detail) == {"status", "failure_code"} and detail["status"] == "failed" and detail["failure_code"] in JAVA_FAILURE_CODES:
                    raise CheckFailure(detail["failure_code"])
                raise CheckFailure("HOST_FAILED")
            if detail != SUCCESS_DETAILS:
                raise CheckFailure("HOST_RESULT_SCHEMA_MISMATCH")
            result.update(detail)
    except CheckFailure as failure:
        result["failure_code"] = str(failure)
    except Exception:
        result["failure_code"] = "HOST_CHECK_FAILED"
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tooling-dir", type=Path, default=FRONTEND / ".tooling",
                        help="Existing provisioned host-test cache; no download is performed")
    parser.add_argument("--report", type=Path, help="Optional sanitized JSON report only")
    args = parser.parse_args()
    result = run_check(args.tooling_dir)
    rendered = json.dumps(result, indent=2, sort_keys=True) + "\n"
    if args.report:
        args.report.write_text(rendered)
    sys.stdout.write(rendered)
    return 0 if result["status"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
