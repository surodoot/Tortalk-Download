# Tor API facade host integration

Run from the repository root:

```sh
bash frontend/embedded-tor/integration/api-facade-host/test_api_facade_host.sh
```

For a repeated cancellation/race check (100 iterations of four scenarios, 400 scenario executions):

```sh
bash frontend/embedded-tor/integration/api-facade-host/test_api_facade_host.sh --stress 100
```

The runner requires an existing Java 17+ compiler/runtime, JUnit 4.13.2, Hamcrest 1.3, the pinned Android 8 `android-all` JAR, and the ten pinned Netty JARs. It downloads nothing. The existing `scripts/provision_host_test_artifacts.sh` provisions these dependencies separately when authorized. The runner verifies their SHA-256 pins, creates an isolated temporary build directory, and deletes that build output on exit. It returns a nonzero exit status for any failure; regressions are not marked as expected failures.

## Actual production code under test

The runner compiles these files directly without copies, rewriting, conditional patches, or production-state injection:

- `TorApiClient.java`
- `OnionApiTransport.java`
- `OnionApiRequest.java`
- `TorReadinessGate.java`
- `JsonBounds.java`
- `TorSocketEndpoint.java`, `TorSocketIo.java`, `TorSocketChannel.java`

`AndroidTorSocket` and `TorSocketPaths` compile against the pinned host Android API but are not executed by the injected connector.

The facade creates its real fixed two-worker executor. The transport creates its real Netty event loops and Unix-channel adapter. A test-only injected `HostTorSockets` byte-I/O seam connects numeric-loopback synthetic peers; production has no TCP connector. Kernel identity/filesystem behavior remains an Android test boundary. JSON parsing uses the actual pinned Android 8 `JSONObject` and `JSONTokener` implementation; the strict production guard runs before that lenient parser.

## Explicit host seams

These files live outside Android `src/main` and `src/test`. They must never be included in an APK, Android test source set, or Android instrumentation classpath.

- `src/android/os/Looper.java`: binds the actual JUnit main thread and holds posted work in a thread-safe queue. Tests can deliberately delay main-thread delivery. It does not execute worker callbacks inline.
- `src/android/os/Handler.java`: posts to that queue.
- `src/dev/gyeopdam/embeddedtor/EmbeddedTorController.java`: supplies the real gate's expiring-generation leases with a synthetic fixed clock; explicitly drives readiness loss/recovery, listener replacement, and registered cancellation hooks. It never loads Guardian, opens a control socket, or starts Tor.
- `src/dev/gyeopdam/embeddedtor/ApiFacadeHostStress.java`: repeats four selected JUnit methods with their original assertions, stopping on the first failure.
- `src/dev/gyeopdam/embeddedtor/ApiFacadeHostTest.java`: JUnit scenarios, controlled SOCKS peers, and main-queue dispatch. All peer addresses are constructed from numeric IPv4 loopback bytes. Hostnames and credential values are fixed synthetic test inputs. No public host is contacted.

Reflection only obtains the production executor to wait for its termination during cleanup. It does not replace the executor, edit its queue, change production state, or manufacture responses. Every server and accepted socket is closed during cleanup.

## Coverage: 27 scenarios

- Actual authenticated POST, exact UTF-8 body, returned object parsing, and main-thread-only callback delivery.
- Origin change while two calls run and two are queued: every old call cancels once; old credentials cannot be sent to the new origin; queued calls must not initiate additional connections.
- New public request uses the new SOCKS DOMAIN without inheriting credentials.
- Canonically equivalent configuration preserves an active call; invalid configuration preserves the prior origin.
- Readiness loss cancels active and queued requests without additional connections.
- Controller cancellation notifications suppress delayed worker callbacks.
- Completed responses queued before origin change, readiness loss/recovery, or listener replacement cannot deliver stale data.
- Reentrant cancellation callbacks can start fresh work, invoke another cancellation, or close the client without duplicating callbacks.
- A throwing cancellation callback cannot prevent other transports/callbacks from being canceled. This initially exposed a production bug and remains a required passing regression.
- Close with a throwing callback still unregisters its controller hook, terminates workers, marks the client closed, and notifies every other pending call exactly once.
- Close cancels two running and two queued calls exactly once; repeated close is harmless.
- The four-call admission limit includes responses waiting for main-thread delivery and releases capacity after delivery.
- Unconfigured, unready, invalid-path, unbound-credential, malformed serialization, oversized serialization, and serialization-exception requests open no sockets.
- Invalid UTF-8, scalar/array roots, trailing data, comments, single quotes, unquoted keys, invalid escapes, and deeply nested parser-bypass payloads return `INVALID_JSON` without exposing response data.
- Valid number/escape payloads, exactly 32 nested containers, and empty 204 responses work.
- HTTP redirects return `REDIRECT_REFUSED`; a separate loopback canary receives no connection.
- Off-main request/configuration/cancellation/close are rejected without configuration, registration, or socket side effects.

## Why the queued-cancellation connection bound is meaningful

The relevant scenarios submit four requests to the facade's actual two-worker executor. Both active requests are held at SOCKS greeting; a two-count latch proves those workers have connected. The peers withhold all replies and EOF, and the synthetic readiness clock cannot expire. The other two requests therefore cannot already be in flight when the main thread cancels. More than two accepted connections would mean cancellation itself released a worker before the queued request was revoked. This initially exposed a production generation-check race and remains a strict regression, rather than a timing tolerance.

## Verification boundaries

These tests prove host behavior at explicit seams. They do not instantiate Android Activities, Binder, Android's real Looper, Guardian Tor, JNI, Signal sessions, Android Keystore, device networking, or a real onion service. They do not prove Android callback timing, network anonymity, DNS leak absence on a device, onion ownership, or end-to-end delivery. Existing transport tests cover byte limits and deadline races separately.

The queued-close test verifies cancellation delivery and socket behavior, not heap erasure. Work removed by `shutdownNow()` may never enter the transport's `finally` block; it does not prove that every abandoned request-body buffer was overwritten before garbage collection. No user data is written to disk; source fixtures contain only synthetic payloads and credentials. No persistent key is generated.
