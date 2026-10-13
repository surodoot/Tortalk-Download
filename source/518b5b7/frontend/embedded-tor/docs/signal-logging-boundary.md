# Signal logging boundary

Production app-owned code must keep libsignal logging uninitialized and must not install a Java Signal logger/provider or change its native log level. Developer-visible diagnostics must continue through the existing fixed-enum/numeric `DiagnosticLog` schema and fixed coordinator error codes. Do not pass peer IDs, public/private key material, capabilities, plaintext, ciphertext, or raw exception/cause strings to diagnostic output.

This is a configuration invariant for our application, not a promise that libsignal, Android or a crashing process can never produce output. In official libsignal 0.105.0, Java static initialization loads JNI and initializes internal state; it does not install the logger. The Java provider defaults to null. Separate explicit provider/native logger APIs opt into forwarding. Public peer identities and public ratchet keys appear in upstream INFO/WARN/ERROR records when logging is enabled. The release INFO ceiling and target filter do not redact fields. Rust panic hooks and unhandled exceptions are separate sinks.

## Source guard

Run from the repository root:

    python3 frontend/embedded-tor/scripts/check_signal_logging_boundary.py
    python3 frontend/embedded-tor/scripts/test_signal_logging_boundary.py

The guard checks app-owned Java, Kotlin and native source files under `frontend/{app,embedded-tor,core,tor-transport}/src/<production-source-set>`. It includes new production flavors and excludes only the currently known exact `test` and `androidTest` source sets. Unknown names, including `testingRelease`, `testFixtures`, and `androidTestDebug`, are scanned conservatively until their build configuration is explicitly reviewed. Comments and non-executable string content are ignored. Kotlin `${...}` expressions and `$identifier` references inside regular/triple-quoted strings remain executable source for the check; nested string literals and braces are handled by a small interpolation-aware scanner. Known static references/imports to Signal logger/provider classes, the logging namespace, the native wrapper, and native `Logger_Initialize` / `Logger_SetMaxLevel` entrypoints fail. This deliberately also rejects unused imports and wildcard imports of the guarded namespaces, so alias/static-import changes cannot silently enable logging. Use the public protocol APIs already used by `SignalClient`; do not access the native wrapper directly from app code.

Tests mutate direct calls, fully qualified calls, imports, wildcard/static imports, Kotlin aliases/quoted identifiers, whitespace/comments between qualifiers, Java Unicode escapes and C/JNI entrypoints, plus regular/triple-quoted Kotlin interpolation with nested quotes/expressions. They also check that the current sanitized diagnostics and non-executable examples remain allowed.

This is a bounded guard against accidental or obvious known initialization, not a compiler or security sandbox. The lightweight scanner covers the Java/Kotlin forms exercised by its tests; it is not a complete language grammar and does not claim support for every future interpolation syntax, macro/preprocessor expansion, unusual native raw-string form, or deliberate obfuscation. Ordinary code review and default-null host/runtime evidence remain primary. It does not analyze dependency bytecode/native constructors, generated code outside these source sets, reflection/dynamic class names, JNI registration, arbitrary wrappers, or future upstream APIs with new names. Any dependency/source-set/API upgrade must revisit this boundary and the sink audit. If a new module carries production Signal code, add it to the module list.

## Verification claims

A source-guard pass means no forbidden known references were found within that boundary. It does not prove runtime absence of logs, secret redaction, Android logcat capture, crash privacy or production security. Tests that intentionally initialize a logger must be isolated fixtures and labeled as explicitly initialized behavior; they must not install a logger in an Android production/runtime fixture by default.

Android privacy validation must separately bind to exact APK/native hashes, use synthetic canaries, cover handled errors and process lifecycle, and verify that the log collector actually observed its positive controls. Retain category counts and fixed failure codes; do not retain raw secret-bearing log output. Adding such validation to a frozen controller or test APK requires a separately reviewed change and new hashes.

## Production-only Linux check

The [production JNI host check](signal-production-host.md) runs the real pinned
Linux production library with synthetic in-memory state, verifies its loaded
bytes, and fails on any host stdout/stderr. It also tests resource-selection and
collector failure controls. This evidence is separate from Android execution and
does not initialize a logger.
