# Tor process host fault harness

Run `bash scripts/test_tor_process_host.sh` from the embedded app directory.

The harness compiles the production `TorProcessService`, `TorProcessProtocol`,
`TorProcessPolicy`, `TorReadinessGate` and `TorSocketEndpoint`. All `android.*` and vendor Tor classes
in this fixture are explicit host doubles. No Android class, JNI library, Tor
process, socket, signing tool or real process-termination API is executed.

Each scenario runs in a fresh host JVM. The production control executor and
independent watchdog use real daemon threads. The clock is virtual; the fake
`Process.killProcess` records a synthetic PID only. The fake main looper is one
dedicated executor, and tests verify superclass creation/destruction occur on
the same thread. `stopSelf` records a request without fabricating lifecycle
completion. Tests explicitly drive superclass destruction when that is relevant.

Injected failures cover hung readiness reads, network enable/disable control
calls, superclass teardown and outbound replies; owner death and foreground
lease expiration; unavailable/wrong process identity and application UID;
unclaimed startup, cold control availability, caller UID and strict protocol
validation, wrong owners, stop acknowledgements and late starts.

Passing establishes the production wrapper's behavior with these doubles. It
does not establish Android Binder scheduling/parceling, actual PID/process
identity visibility, native class initialization, JNI exit, OS process death,
wire traffic closure or elapsed-time guarantees while Android suspends a process.
Those remain device-runtime checks under separate authorization. In particular,
a hang while the upstream class initializes before `onCreate` cannot be covered
by the watchdog created inside `onCreate`; clients must preserve an unconfirmed
state if no exact remote Binder was ever obtained.

## Unix SOCKS source-only regressions

The filesystem policy is a labeled `TorSocketPaths` host seam. It records preparation and
can reject it; it does not prove real directory ownership, Unix socket modes or Android
peer-credential behavior. The vendor double snapshots the configuration and preparation
state at native-lifecycle entry. The harness requires `SocksPort 0` and `DisableNetwork 1`
there, rejects failed path preparation before native start, and records authenticated
control configuration of the fixed Unix path before networking is enabled. Because offline
Tor has no non-control listeners, the pre-enable check reads back the sole exact `SocksPort`
configuration using `GETCONF` and verifies the private parent directory. After enabling,
the strict listener query and filesystem socket verification must precede READY.

Cases cover hung/failed Unix listener configuration and `GETCONF`, late completion after
the terminal deadline, missing/wrong/extra configuration, failed directory verification,
failed post-enable socket verification, positive TCP authentication markers, TCP/foreign
Unix listener reports, and exact verified private PID in sanitized replies. No Unix socket or TCP socket
is created by these scenarios. Real Android filesystem, peer authentication and native Tor
compatibility remain separate evidence.
