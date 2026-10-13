# Main-process Tor controller host checks

Run `bash frontend/embedded-tor/scripts/test_tor_controller_host.sh` from the repository root.
Each named scenario uses a fresh JVM and compiles the actual application `EmbeddedTorController`,
`TorProcessProtocol`, readiness gate, foreground policy and probe session.

Android Context, Messenger/Binder, main Handler, elapsed clock and remote service are explicit doubles.
`TorSocketPaths` is an explicit fixed-path host seam; the endpoint value class is production code.
This harness does not inspect actual Android directory modes or kernel peer credentials.
The remote class fixture has no JNI or real service implementation. No network, native Tor process,
Android lifecycle, physical process kill, signing or device execution occurs here.

The suite verifies explicit foreground start, zero AUTO_CREATE binds, repeated clicks, foreground
handoff, immediate API revocation, ACK versus process-death distinction, no timer-based termination
claim, exact retained Binder death, manual-only restart, old incarnation replies, wrong UID/schema/
sequence/time, stopped-before-binding behavior, unknown termination, command/bind deadlines,
readiness expiry, listener/cancellation exception isolation and diagnostic-only cancellation.

The separate `TorProcessPolicyTest` checks remote deadline/lease policy. Remote service fault
injection and actual Android execution are separate evidence; these controller doubles do not prove
that a real process dies or that Tor network traffic stops on a physical device.

The Unix endpoint regressions verify the fixed application path, expected app UID/private PID,
main-PID rejection, PID pinning before readiness, terminal PID changes within one Binder epoch,
strict integer PID schema, and rejection of caller-selected paths or legacy TCP port fields.
Existing exact-Binder-death and old-generation cases continue to revoke stale endpoint leases.
