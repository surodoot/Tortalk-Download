#!/usr/bin/env python3
"""Mutation tests for the bounded source policy, with no app/JNI execution."""
from pathlib import Path
import tempfile
import unittest
import check_signal_logging_boundary as guard


class SignalLoggingBoundaryTest(unittest.TestCase):
    def test_known_initialization_mutations_rejected(self):
        mutations = [
            'SignalProtocolLoggerProvider.initializeLogging(4);',
            'SignalProtocolLoggerProvider.setProvider(provider);',
            'new AndroidSignalProtocolLogger();',
            'org.signal.libsignal.protocol.logging.SignalProtocolLoggerProvider.initializeLogging(4);',
            'import org.signal.libsignal.protocol.logging.SignalProtocolLoggerProvider;',
            'import static org.signal.libsignal.protocol.logging.SignalProtocolLoggerProvider.initializeLogging;',
            'import org.signal.libsignal.protocol.logging.*;',
            'import org.signal.libsignal.protocol.logging.SignalProtocolLoggerProvider as Hidden\nHidden.initializeLogging(4)',
            'import org.signal.libsignal.internal.Native as Hidden\nHidden.Logger_Initialize(4, Sink::class.java)',
            'import static org.signal.libsignal.internal.Native.Logger_Initialize;',
            'import org.signal.libsignal.internal.*;',
            'Native.Logger_Initialize(4, Log.class);',
            'Native.Logger_SetMaxLevel(4);',
            'Java_org_signal_libsignal_internal_Native_Logger_1Initialize(env, klass, 4, sink);',
            'Java_org_signal_libsignal_internal_Native_Logger_1SetMaxLevel(env, klass, 4);',
            'org /* comment */ . signal . libsignal . protocol . logging . Log.w("x", "x");',
            r'\u0053ignalProtocolLoggerProvider.initializeLogging(4);',
            '`SignalProtocolLoggerProvider`.initializeLogging(4)',
        ]
        for text in mutations:
            with self.subTest(source=text):
                self.assertTrue(guard.violations(text, kotlin=not text.startswith(r"\u")))

    def test_executable_kotlin_interpolation_is_not_literal_documentation(self):
        mutations = [
            'val x = "${SignalProtocolLoggerProvider.initializeLogging(4)}"',
            'val x = """${SignalProtocolLoggerProvider.initializeLogging(4)}"""',
            'val x = "${SignalProtocolLoggerProvider.setProvider(make("nested literal"))}"',
            'val x = "${run { "nested quote"; SignalProtocolLoggerProvider.initializeLogging(4) }}"',
            'val x = """${run { "nested quote"; SignalProtocolLoggerProvider.initializeLogging(4) }}"""',
            'val x = "${"inner ${SignalProtocolLoggerProvider.initializeLogging(4)}"}"',
            'val x = """${"inner ${SignalProtocolLoggerProvider.initializeLogging(4)}"}"""',
            'val x = "${/* comment */ Native.Logger_Initialize(4, sink)}"',
            'val x = "$SignalProtocolLoggerProvider"',
            r'val x = """\${SignalProtocolLoggerProvider.initializeLogging(4)}"""',
        ]
        for text in mutations:
            with self.subTest(source=text):
                self.assertTrue(guard.violations(text, kotlin=True))
        # An escaped dollar in a regular Kotlin string is literal text.
        self.assertEqual([], guard.violations(
            r'val x = "\${SignalProtocolLoggerProvider.initializeLogging(4)}"', kotlin=True))
        # The same characters in Java are literal documentation, not interpolation.
        self.assertEqual([], guard.violations(
            'String x = "${SignalProtocolLoggerProvider.initializeLogging(4)}";', kotlin=False))
        # Comments and nested ordinary literals inside interpolation stay inert.
        self.assertEqual([], guard.violations(
            'val x = "${run { /* SignalProtocolLoggerProvider.initializeLogging(4) */ "Native.Logger_Initialize" }}"', kotlin=True))

    def test_unknown_test_like_source_sets_are_scanned_conservatively(self):
        for source_set in ('testingRelease', 'testFixtures', 'androidTestDebug', 'testProduction'):
            with self.subTest(source_set=source_set), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                source = root / f'frontend/embedded-tor/src/{source_set}/kotlin/Example.kt'
                source.parent.mkdir(parents=True)
                source.write_text('val x = "${SignalProtocolLoggerProvider.initializeLogging(4)}"')
                result = guard.check(root)
                self.assertFalse(result['pass'])
                self.assertEqual(1, result['files_checked'])

    def test_sanitized_diagnostics_and_non_executable_text_allowed(self):
        samples = [
            'diagnostics.recordAction(DiagnosticLog.Action.SCREEN_OPENED);',
            'callback.done("CRYPTO_OR_STORAGE_FAILED");',
            '// SignalProtocolLoggerProvider.initializeLogging(4);\nrun();',
            '/* Native.Logger_Initialize(4, Log.class); */\nrun();',
            'String description = "SignalProtocolLoggerProvider.initializeLogging";',
            'val example = """SignalProtocolLoggerProvider.initializeLogging(4)"""',
            'String escaped = "x\\\" SignalProtocolLoggerProvider x";',
            'import org.signal.libsignal.protocol.SessionCipher;',
        ]
        for text in samples:
            with self.subTest(source=text):
                self.assertEqual([], guard.violations(text))

    def test_production_sources_scanned_and_test_fixtures_excluded(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            main = root / 'frontend/embedded-tor/src/main/java/Example.java'
            main.parent.mkdir(parents=True)
            main.write_text('diagnostics.recordAction(DiagnosticLog.Action.SCREEN_OPENED);')
            for source_set in ('test', 'androidTest'):
                test = root / f'frontend/embedded-tor/src/{source_set}/java/Fixture.java'
                test.parent.mkdir(parents=True)
                test.write_text('SignalProtocolLoggerProvider.initializeLogging(4);')
            result = guard.check(root)
            self.assertTrue(result['pass'])
            self.assertEqual(1, result['files_checked'])
            # Adding the exact same call to production must fail.
            main.write_text('SignalProtocolLoggerProvider.initializeLogging(4);')
            self.assertFalse(guard.check(root)['pass'])

    def test_new_production_flavor_and_native_source_are_scanned(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            native = root / 'frontend/app/src/release/cpp/logging.cpp'
            native.parent.mkdir(parents=True)
            native.write_text('Java_org_signal_libsignal_internal_Native_Logger_1Initialize(env, klass, 4, sink);')
            self.assertFalse(guard.check(root)['pass'])
            native.write_text('// unchanged native library\n')
            custom = root / 'frontend/app/src/customFlavor/kotlin/Logging.kt'
            custom.parent.mkdir(parents=True)
            custom.write_text('import org.signal.libsignal.protocol.logging.SignalProtocolLoggerProvider as Sink')
            self.assertFalse(guard.check(root)['pass'])

    def test_missing_source_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            result = guard.check(Path(directory))
            self.assertFalse(result['pass'])
            self.assertEqual('PRODUCTION_SOURCE_MISSING', result['violations'][0]['code'])

    def test_reports_codes_and_line_numbers_without_source_values(self):
        text = '// permitted example\nSignalProtocolLoggerProvider.setProvider("synthetic-secret-value");'
        result = guard.violations(text)
        self.assertEqual(2, result[0]['line'])
        self.assertNotIn('synthetic-secret-value', str(result))


if __name__ == '__main__':
    unittest.main()
