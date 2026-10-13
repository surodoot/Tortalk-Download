#!/usr/bin/env python3
"""Synthetic guard controls; failure output is reduced to counts and test names."""
import argparse
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest import mock

import check_signal_production_host as check

TOOLING = check.FRONTEND / ".tooling"
MUTATION_RESULTS = {}


class ProductionHostControls(unittest.TestCase):
    def mutate(self, name, replacements, expected, *, fixture=False):
        with tempfile.TemporaryDirectory(prefix="tortalk-signal-mutation-") as directory:
            harness = Path(directory)
            for filename in ("ProductionOnlyLoader.java", "ProductionPrivacyHost.java"):
                shutil.copyfile(check.HARNESS / filename, harness / filename)
            target = harness / ("ProductionPrivacyHost.java" if fixture else "ProductionOnlyLoader.java")
            source = target.read_text()
            for old, new in replacements:
                self.assertEqual(source.count(old), 1)
                source = source.replace(old, new)
            target.write_text(source)
            result = check.run_check(TOOLING, harness=harness)
            stage = "host" if "host" in result else "compile" if "compile" in result else "preflight"
            MUTATION_RESULTS[name] = {
                "status": result["status"], "failure_code": result.get("failure_code", "MISSING"),
                "process_stage": stage,
                "stdout_bytes": result.get(stage, {}).get("stdout_bytes", 0),
                "stderr_bytes": result.get(stage, {}).get("stderr_bytes", 0),
            }
            self.assertEqual(result["status"], "failed")
            self.assertEqual(result["failure_code"], expected)

    def test_testing_jni_maps_rejected_after_resource_guard_bypass(self):
        self.mutate("testing_jni_selection", [
            ('return testing || (name.contains("signal_jni") && !name.equals(PRODUCTION));', 'return false;'),
            ('                verifyResourcePolicy(loader);', '                // Positive control: bypass resource preflight too.'),
        ], "TESTING_JNI_MAPPED")

    def test_direct_find_resource_bypass_rejected(self):
        self.mutate("find_resource_bypass", [
            ('return denied(name) ? null : super.findResource(name);', 'return super.findResource(name);'),
        ], "TESTING_RESOURCE_VISIBLE")

    def test_resource_enumeration_bypass_rejected(self):
        self.mutate("resource_enumeration_bypass", [
            ('return denied(name) ? Collections.emptyEnumeration() : super.getResources(name);',
             'return super.findResources(name);'),
        ], "TESTING_RESOURCE_VISIBLE")

    def test_resource_stream_bypass_rejected(self):
        self.mutate("resource_stream_bypass", [
            ('return denied(name) ? null : super.getResourceAsStream(name);',
             'try { URL url = super.findResource(name); return url == null ? null : url.openStream(); } '
             'catch (IOException failure) { throw new AssertionError(); }'),
        ], "TESTING_RESOURCE_VISIBLE")

    def test_native_fallback_bypass_rejected(self):
        self.mutate("native_fallback_bypass", [
            ('if (name.startsWith("signal_jni")) throw new UnsatisfiedLinkError("HOST_NATIVE_FALLBACK_DENIED");',
             'if (name.startsWith("signal_jni")) return null;'),
        ], "NATIVE_FALLBACK_ALLOWED")

    def test_mapped_native_bytes_verified(self):
        self.mutate("mapped_native_hash", [(check.NATIVE_SHA256, "0" * 64)], "MAPPED_JNI_HASH_MISMATCH")

    def test_runtime_output_is_failure_without_retention(self):
        self.mutate("runtime_stdout", [
            ('        int groups = 0;', '        System.out.print("synthetic-output-control");\n        int groups = 0;'),
        ], "HOST_OUTPUT_OBSERVED", fixture=True)

    def test_compile_failure_is_sanitized(self):
        self.mutate("compile_failure", [
            ('public final class ProductionOnlyLoader {', 'public final class ProductionOnlyLoader INVALID {'),
        ], "COMPILATION_FAILED")

    def test_corrupt_dependency_rejected_before_compilation(self):
        with mock.patch.object(check, "sha256", return_value="0" * 64), mock.patch.object(check, "run_bounded") as run:
            result = check.run_check(TOOLING)
            self.assertEqual(result.get("failure_code"), "DEPENDENCY_HASH_MISMATCH")
            run.assert_not_called()

    def test_changed_pin_rejected_before_compilation(self):
        changed = ((check.DEPENDENCIES[0][0], "0" * 64), *check.DEPENDENCIES[1:])
        with mock.patch.object(check, "DEPENDENCIES", changed), mock.patch.object(check, "run_bounded") as run:
            result = check.run_check(TOOLING)
            self.assertEqual(result.get("failure_code"), "PROVISION_PIN_MISMATCH")
            run.assert_not_called()

    def test_collector_observes_both_file_descriptors(self):
        with tempfile.TemporaryDirectory() as directory:
            result = check.run_bounded([sys.executable, "-c", "import os; os.write(1,b'out-control'); os.write(2,b'err-control')"], directory)
            self.assertEqual(result["stdout_bytes"], 11)
            self.assertEqual(result["stderr_bytes"], 11)
            self.assertEqual(set(result), {"exit_code", "stdout_bytes", "stderr_bytes", "timed_out", "output_limit_exceeded"})
            self.assertEqual(list(Path(directory).iterdir()), [])

    def test_collector_limits_output(self):
        with tempfile.TemporaryDirectory() as directory:
            result = check.run_bounded([sys.executable, "-c", "import os;\nwhile True: os.write(1,b'x'*1024)"], directory, timeout=3, output_limit=64)
            self.assertTrue(result["output_limit_exceeded"])
            self.assertEqual(result["stdout_bytes"], 65)
            self.assertEqual(list(Path(directory).iterdir()), [])

    def test_collector_deadline(self):
        with tempfile.TemporaryDirectory() as directory:
            result = check.run_bounded([sys.executable, "-c", "import time; time.sleep(10)"], directory, timeout=0.1)
            self.assertTrue(result["timed_out"])
            self.assertNotEqual(result["exit_code"], 0)

    def test_java_environment_injection_removed(self):
        names = ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH", "LD_PRELOAD", "LD_LIBRARY_PATH", "LD_AUDIT")
        with mock.patch.dict(os.environ, {name: "synthetic-injection-control" for name in names}):
            env = check.isolated_environment()
            self.assertTrue(all(name not in env for name in names))


def main():
    global TOOLING
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tooling-dir", type=Path, default=TOOLING)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    TOOLING = args.tooling_dir
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(ProductionHostControls)
    result = unittest.TestResult()
    suite.run(result)
    # unittest keeps traceback text internally; do not print or save it.
    report = {"status": "passed" if result.wasSuccessful() else "failed",
              "tests_run": result.testsRun, "failures": len(result.failures), "errors": len(result.errors),
              "failed_tests": [test._testMethodName for test, _ in result.failures + result.errors],
              "mutation_results": MUTATION_RESULTS, "raw_process_output_retained": False}
    rendered = json.dumps(report, indent=2, sort_keys=True) + "\n"
    if args.report:
        args.report.write_text(rendered)
    sys.stdout.write(rendered)
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    sys.exit(main())
