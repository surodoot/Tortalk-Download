# Private Unix SOCKS candidate

This implementation is a private pilot candidate. Android runtime acceptance remains
pending, and the earlier frozen runtime proposal does not cover these changes. Host
doubles and public API compilation are separate evidence from executing the packaged
Tor/JNI build.

## Problem and threat model

The previous controller already revoked readiness directly when its exact retained
Binder's death was observed, before main-thread cleanup. The API transport checked
that authority after SOCKS negotiation and before creating HTTP credentials. Those
checks remain useful and are retained.

They did not authenticate the TCP listener. If Tor released its loopback port before
death/error/lease revocation reached a new connection, a network-enabled foreign
Android UID could potentially bind the available port, answer unauthenticated SOCKS,
and receive HTTP credentials. This required a new connection reaching a substitute;
rebinding cannot take over an already established TCP stream. No Android exploit,
scan, live credential interception, or timing measurement was performed.

This candidate addresses ordinary foreign-UID listener replacement. Root, compromised
kernel, malicious code sharing the application UID, malicious code inside the Tor
process, and descriptor transfer by a compromised trusted peer remain outside its
claim. A PID is a numeric identity check, not an independently unforgeable lifetime
handle. It is paired with the app UID, private pathname, immutable Binder attempt,
and fresh readiness epoch. Bytes already queued to a verified peer cannot be recalled
by later cancellation. OS suspension is outside software deadline guarantees.

## Endpoint and control ownership

The SOCKS pathname is derived locally from the canonical application files directory:
`files/tor-socks/s`. Neither control replies nor UI/server input can select it. The
directory must be an app-owned 0700 directory, and the socket must have the same UID,
be a filesystem socket rather than a symlink/regular file, and deny group/world access.
The pathname is ASCII and bounded below Linux's 108-byte sockaddr_un limit.

Only a fresh service incarnation prepares a stale socket before native startup.
There is no pathname unlink during stop or deferred cleanup. The controller does
not start another incarnation until exact retained-Binder death. An unexpected file
type/owner fails closed rather than being deleted. This is not protection against a
malicious same-UID actor replacing files concurrently.

The pinned vendor control connection already uses a hardwired filesystem Unix socket
under the private TorService data directory, with `ControlPort 0` and command-line
`CookieAuthentication 0`. It sends `AUTHENTICATE` with an empty byte array; no cookie
secret crosses localhost TCP. The candidate preserves that one connection and verifies/
tightens its app-owned root/data directories to 0700 before native startup. It does not
add SAFECOOKIE or claim kernel peer verification for the vendor's private control FD.
Its control trust boundary remains the private filesystem and trusted same-UID code.

## Startup and readiness

The exact pinned Tor Android vendor reads its initial SOCKS listener as a numeric port.
Initial `SocksPort unix:...` would break that parser. Therefore startup uses
`SocksPort 0` and `DisableNetwork 1`, waits for the vendor's authenticated zero marker,
then configures one fixed Unix SOCKS listener with `OnionTrafficOnly`.

Before enabling networking, the wrapper requires exactly one `GETCONF SocksPort`
entry matching the locally constructed configuration and revalidates the private
directory. Tor's `DisableNetwork` suppresses non-control listening, so a live socket
cannot be required at that point. After enabling, fresh bootstrap/circuit/listener
samples must identify the sole exact Unix listener, and the actual socket's type,
owner and permissions must pass before READY. Existing command and process watchdogs
remain armed throughout. There is no transient TCP SOCKS configuration or fallback.

The strict version-2 Messenger reply publishes the verified service's own PID and a
bounded listener-ready bit. The main process rejects its own PID, malformed fields,
changed PID within an incarnation, and old epochs/replies. Leases carry the expected
private endpoint and process identity, rather than a numeric TCP port.

Each connection uses Android's public `LocalSocket` FILESYSTEM namespace. Kernel
`SO_PEERCRED` UID/PID must match the lease before Netty receives `channelActive`, so
no SOCKS greeting, onion hostname, HTTP body or authentication header precedes proof.
SOCKS authentication/isolation flags are not treated as proxy authentication.

## Bounded byte I/O and lifecycle

The adapter preserves the existing Netty SOCKS5 and bounded HTTP codecs. It adds no
vendor binary, native dependency, reflective API access, or custom cryptography.

On API26, `LocalSocket.connect(address, timeout)` throws UnsupportedOperationException.
The adapter precreates its FD, sets both receive/send timeouts using `setSoTimeout`,
and makes one filesystem connect with a maximum 1-second send timeout, additionally
bounded by the remaining overall deadline. There is no retry. This is intentionally
stricter than the old 10-second TCP connect bound. Backlog saturation may delay abort
until that finite connect timeout; the lease/deadline is checked again before bytes.

After connect, public `Os.poll`, `Os.recvfrom`, and `Os.sendto` perform bounded waits
and individual nonblocking operations. Android API26 omits the MSG_DONTWAIT and
MSG_NOSIGNAL symbols, so the source documents their stable, ABI-independent Android
bionic UAPI values. It does not call the LocalSocket stream write-all/flush loops.
The latter's API26 TIOCOUTQ polling is not bounded by SO_SNDTIMEO.

After every writable poll, the adapter rechecks readiness/deadline. It serializes
one nonblocking send with local abort under a short lock. The independent request
worker invokes SHUT_RDWR, never closes an FD while its owner is in native I/O.
Only the I/O owner closes after its syscall returns, avoiding descriptor-number reuse.
The overall request/probe deadline remains 30 seconds; slow partial progress cannot
extend it. Scheduler suspension remains outside this bound.

Pinned Netty's deprecated per-channel OIO group moves children from active to idle
collections non-atomically. Group-only shutdown can miss a child in that transfer.
Cleanup retains the channel returned by connect even before initializer publication,
explicitly shuts down and awaits its exact event loop, then shuts down the group.
A deterministic paused-transfer regression reproduces the original lifecycle failure.
A second pinned-Netty race can consume the shutdown wakeup while draining tasks
during deregistration, then block again. The owned event-loop subclass checks
`isShuttingDown()` before `takeTask()` can block. A separate deterministic queued-drain
regression covers that condition; neither fix relies on longer waits or leaking
daemon workers.

### Concurrent close submission

A controlled no-socket diagnostic reproduced a final queue(1) warning and identified
the discarded runnable as Netty's queued close task, `AbstractChannelHandlerContext$11`.
That fixture had already closed its synthetic descriptor once, wrote no payload,
emptied the queue, and terminated all threads. The item behind the earlier generic
stress warning was not captured, so that original warning is not classified as
harmless or as a wakeup marker.
A queued close task can retain channel/pipeline references until removed, so this
result does not promise immediate credential erasure.

The follow-up makes `abort()` idempotent. Under the descriptor monitor, it publishes
revocation, interrupts native I/O, marks the close request submitted, and completes
exactly one close submission. A competing shutdown caller cannot pass that monitor
and terminate the owner while the first caller is still enqueueing close. Calls
after an owner-initiated close also submit nothing. Inline owner callbacks may
re-enter the monitor safely; no future wait or blocking socket I/O is added inside
it. The existing peer/deadline fences and owner-only descriptor close remain intact.

The deterministic delayed-enqueue regression checks prompt revocation, concurrent
cleanup ordering, one native close, rejection and release of a queued synthetic
payload, and no new close submission after termination. This excludes the reproduced
duplicate/late-close path; it does not identify every historical discarded task or
replace pending Android runtime evidence.

A separate CI queue-empty assertion failure was reproduced after the same host test
order and identified by object identity as Netty's fixed `WAKEUP_TASK`, offered after
the owner had terminated. The test now uses the pinned framework's `pollTaskFrom`
helper, which skips only that exact sentinel, before requiring no application task
and a physically empty queue. A deterministic delayed-wakeup test and negative
ordinary-runnable/actual-queued-close controls preserve the cleanup assertion.
This is distinct from the earlier non-sentinel queue(1) warning and does not
reclassify that original warning.

## Validation boundary

Host tests exercise the real codecs/channel with explicit synthetic peer and syscall
doubles, plus loopback-only test connectors that do not exist in production. They
cover no pre-proof writes, UID/PID mismatch, post-poll revocation/deadline, partial
sends, close ordering, stale leases, service configuration ordering, and OIO cleanup.

`AndroidUnixSocketRuntimeChecks` is a separately prepared Android-source helper,
registered first in the updated seven-group source runner. It remains unexecuted.
The older frozen six-group controller is unchanged and is not the validation target
for this newer source; a separately reviewed disabled controller must bind the exact
new successful unsigned app/test artifacts before runtime approval. The helper uses
a fresh same-process/same-UID synthetic fixture and checks filesystem modes/types,
actual kernel credentials, public byte I/O, wrong expected PID with zero bytes,
shutdown EOF, bounded backlog connect/read, and owned cleanup without Tor networking.
Compilation is not execution. Packaged vendor Unix configuration, all bundled ABIs,
real :tor PID, two-UID denial, lifecycle behavior and network acceptance still require
separately authorized Android tests before broader claims or public release.

## Primary references

- [Android LocalSocket public API](https://developer.android.com/reference/android/net/LocalSocket)
- [API26 LocalSocket source](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-8.0.0_r1/core/java/android/net/LocalSocket.java)
- [API26 LocalSocketImpl source](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-8.0.0_r1/core/java/android/net/LocalSocketImpl.java)
- [Android SO_PEERCRED implementation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/jni/android_net_LocalSocketImpl.cpp)
- [API26 bionic socket flags](https://android.googlesource.com/platform/bionic/+/android-8.0.0_r1/libc/include/sys/socket.h)
- [Linux Unix socket ownership/peer credentials](https://man7.org/linux/man-pages/man7/unix.7.html)
- [Android Binder liveness limits](https://developer.android.com/reference/android/os/IBinder#isBinderAlive())
- [Vendor TorService source](https://github.com/guardianproject/tor-android/blob/master/tor-android-binary/src/main/java/org/torproject/jni/TorService.java)
- [Tor listener/configuration/control authentication specification](https://spec.torproject.org/control-spec/commands.html)
- [Tor upstream manual distributed by Debian](https://manpages.debian.org/testing/tor/torrc.5.en.html)
- [Netty OIO implementation](https://netty.io/4.1/xref/io/netty/channel/ThreadPerChannelEventLoop.html)

The inspected pinned AAR is `info.guardianproject:tor-android:0.4.9.13.2`, SHA-256
`40be745450a8ecfaac8558950bf046f596d7ef6e88107f69334a8c4b6e7d14be`. Its cached
classes and source archive were matched to the inspected vendor source. Upstream
branch links are context; the pinned artifact remains the implementation boundary.
