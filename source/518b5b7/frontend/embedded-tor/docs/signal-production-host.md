# Production-only Signal JNI host check

The Linux x86_64 check runs the unmodified application `SignalClient` against the
exact official `libsignal-client-0.105.0.jar`, with no initialized Signal logger or
Java logger provider. It exercises eight synthetic scenario groups plus all 32
existing `SignalClientTest` tests. Any runtime stdout or stderr byte fails the
check. All identities, keys, capabilities, plaintext and ciphertext are synthetic;
state and JUnit failure objects stay in process memory.

This is Linux/JVM evidence only. It does not execute Android JNI, an APK, logcat,
a device/emulator, Tor, a relay, real credentials, or a network service. A passing
host result does not prove Android silence, crash privacy, anonymity, security,
or absence of every possible libsignal output path. A null provider and the
separate source guard support the default logging configuration; they do not
observe the Rust logger's internal global state or prove no temporary logger
initialization could occur in arbitrary modified code.

## Run and CI placement

The existing `provision_host_test_artifacts.sh` CI step already fetches and verifies
all four dependencies. This checker never provisions or downloads anything. Use
an existing cache, with Python 3.11+ and an installed JDK 21 or later:

    python3 frontend/embedded-tor/scripts/check_signal_production_host.py
    python3 frontend/embedded-tor/scripts/test_signal_production_host.py

Both accept `--tooling-dir /path/to/frontend/.tooling`. An optional `--report`
writes the same sanitized JSON printed to stdout. The `pilot-integration` CI job
replaces its ordinary standalone Signal test command with these two commands,
after the unchanged pinned dependency provisioning step. The 32 existing tests
are included in the new check; other JNI test scripts remain separately scoped.
There is no new runner, setup/install action, permission, secret, credential,
signing operation, Android execution route, server or network operation.

The local stripped JDK has no `ct.sym`, so compilation uses the established
`-source 17 -target 17` host-test convention. Compiler diagnostics are discarded
and counted (the local compiler emits an 88-byte options warning). This is not an
Android API/minSdk compatibility check. A nonzero compiler exit, timeout or output
limit still fails before execution.

## Exact input and library evidence

The runner verifies all four complete dependency files before compilation and
requires their hashes to agree with the existing provisioning manifests:

| Dependency | SHA-256 |
| --- | --- |
| libsignal-client-0.105.0.jar | `520a16906c45e1629ed05c98b12556322ccc489a82016bba921954e312c882c4` |
| kotlin-stdlib-2.2.20.jar | `8836ccffd3585fadda9901244b20d42901d2f3cd581058d8434e2ffabcf3a3e7` |
| junit-4.13.2.jar | `8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3` |
| hamcrest-core-1.3.jar | `66fdef91e9739348df7a096aa384a5685f4e875584cce89386a7a47251c4d8e9` |

The production resource and the actual file mapped by the JVM must both have
SHA-256 `1e690028660a8c29e07d02837aa7cd495fd201f476a140b645d293d2abaf9072`
(`libsignal_jni_amd64.so`). The original JAR is neither stripped nor rewritten.
Its real testing JNI resource remains present, which lets the guard's negative
controls test the actual published packaging. Hashes bind exact reviewed bytes;
they are not an independent publisher-signature or reproducible-build claim.
The result also records SHA-256 for the two app/test sources and two harness
sources. A dependency update requires reviewing the pins, native resource name,
loader behavior and expected test count together.

## Production selection and observed mappings

Upstream `Native.kt` first tries testing JNI as a resource and via
`System.loadLibrary`, then tries production JNI. Merely putting the published JAR
on a classpath therefore does not establish production JNI execution.

The test bootstrap creates a fresh `URLClassLoader` with only the four verified
JARs and freshly compiled classes, with the JDK platform classloader as parent.
It verifies Native's defining classloader. It rejects testing resources through
`getResource`, `getResourceAsStream`, `getResources`, `resources`, `findResource`
and `findResources`, including `Class.getResourceAsStream`. Other Signal-native
resource names are denied except the pinned Linux amd64 production name. Its
`findLibrary` denies Signal system-library fallback, and `java.library.path`
points to an empty, owned location. Java option/classpath and native loader
environment overrides are removed from child processes.

Before initializing Native, assertions exercise each resource API and the testing
fallback. After initialization, the runner requires actual testing-resource
denials from Native's attempt. Before and after the synthetic scenarios, the
bootstrap reads `/proc/self/maps` in memory: testing JNI or an unexpected Signal
mapping fails, exactly one production JNI file must be mapped beneath this run's
owned temporary directory, and that file must match the native hash. Raw maps,
addresses and extracted-path strings are never retained in reports. This is an
accidental-regression guard, not a security sandbox for malicious code or a
replacement for review of classloader/JVM/native behavior.

## Scenarios, output bounds and positive failure controls

The eight scenario groups are initialization/capability generation, tamper
rejection, first prekey setup/decrypt, replay detection, reverse-direction reply,
out-of-order messages, storage failure and pinned-identity replacement rejection.
The fixture checks the Java provider remains null before, between and after the
scenarios and existing tests, and calls no logging initialization API.

Both compiler and host run as owned process groups with drained stdout/stderr
pipes. Content is never decoded, echoed, hashed or saved; only byte counts are
retained. The compiler has 60 seconds and the runtime 120 seconds, each with a
64 KiB combined output ceiling. Counts saturate at 65,537 per stream. Timeout or
output overflow kills the process group, and pipe shutdown has a further bounded
two-second drain. Runtime output of any size fails, including native writes
straight to file descriptors. JVM error-file output, core dumps and heap dumps
are disabled. Temporary compiled classes, extracted public native binaries and
the bounded fixed-schema result are removed afterward. This does not control
external OS crash-reporting facilities or arbitrary writes by malicious code.

The control suite includes eight real source mutations, compiled and run only
from fresh temporary fixture copies:

- Bypass resource filtering and its preflight to deliberately select real testing
  JNI; the independent mapping check must report `TESTING_JNI_MAPPED`.
- Bypass direct lookup, enumeration or stream filtering; preflight must reject
  each resource exposure.
- Permit native fallback; its preflight must reject that change.
- Change the expected mapped-native hash; the loaded-byte check must reject it.
- Emit synthetic runtime output; zero-output gating must fail without retaining it.
- Break compilation; diagnostics must remain discarded and counted.

Six further controls reject a corrupt dependency and a changed provision pin
before compilation, prove direct stdout/stderr collection, enforce output and
time bounds, and check environment sanitization. Controls retain only test names,
counts, fixed failure codes and mutation byte counts. They never initialize a
logger or retain a raw log dump. The separately enabled-logger research fixture
is deliberately excluded from this default CI check.

See [the Signal logging boundary](signal-logging-boundary.md) for the app source
invariant and why enabled upstream INFO/WARN/ERROR logs can expose public peer/key
metadata. Exact-build Android logging and lifecycle validation remains separate.
