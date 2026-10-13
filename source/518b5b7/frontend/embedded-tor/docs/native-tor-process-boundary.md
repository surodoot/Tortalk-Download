# Native Tor process lifetime

This follow-up replaces the in-process Guardian TorService binding with one app-private `:tor` service incarnation per explicit foreground start. It does not change the onion-only HTTP transport or the encrypted account/Signal stores.

## Why the boundary is needed

The pinned official Tor service initializes native Tor in its service lifecycle. Its `onDestroy` performs a synchronous control shutdown, while jtorctl exposes no bounded command timeout. Interrupting a waiting command leaves a response waiter in that connection. Therefore neither unbinding nor interrupting and reusing the old connection proves that native networking ended.

Only `TorProcessService` references the vendor service. The pinned vendor exposes its connection before authentication finishes; the wrapper waits for its public initialized SOCKS marker under the same control monitor before enabling networking, then performs the original fresh strict readiness queries. The marker is not itself readiness or a trusted port. Its fixed configuration is written with `DisableNetwork 1` before the superclass starts native Tor. The source manifest instructs the merger to remove the original AAR service; actual merged-manifest and APK verification remain required for this follow-up. The replacement is non-exported, uses the `:tor` process and returns `START_NOT_STICKY`. The main controller uses an explicit foreground `startService` followed by binding with flags zero; it never uses `BIND_AUTO_CREATE` or automatically enables a replacement service.

Before native startup or self-termination, the service verifies its PID, application UID, and exact process name through Android's own running-process information. The termination path can target only that verified process's current PID. It never accepts a caller-supplied PID and never kills the main UI process.

## IPC and deadlines

Messenger requests and replies require the real sender UID, exact versioned primitive fields and bounded values. One owner Binder claims the incarnation. Epoch and increasing sequence bind each command to that owner; delayed heartbeats expire based on their sender elapsed time, not their arrival time. Once claimed, the exact owner Binder is checked before malformed-message handling, so a different same-UID binding cannot terminate that owner. No account identifiers, keys, recovery/session tokens, arbitrary network destinations or raw control replies cross this interface.

- Control operations: 2-second aggregate deadline, checked by an independent watchdog
- Foreground lease: 4 seconds from the latest valid sender timestamp
- Unclaimed startup: 5 seconds
- Stop/shutdown: 2 seconds, without extending an already earlier command deadline
- Main-process sample cadence: 500 ms; readiness freshness: 2 seconds

These are monotonic software deadlines, not hard real-time guarantees while Android suspends the process or the OS/runtime is frozen. The watchdog never waits for control I/O, outbound Binder replies, or the service main thread. A stop irreversibly closes that incarnation. It attempts `DisableNetwork 1`, requests service shutdown, and terminates its own process after superclass teardown returns or the independent deadline expires. It never interrupts and reuses the old control connection.

## What each status proves

Exact Binder death also fails the immutable attempt-specific readiness epoch on the death-recipient thread, before queued UI cleanup. Both transport and developer-probe workers therefore lose authority without waiting for the main thread; stale incarnation death cannot fail a new epoch.

API readiness and active request leases are revoked immediately when the user stops, the last Activity leaves foreground, or control/readiness fails. This immediately prevents new application requests and cancels existing transports.

`STOP_REQUESTED` proves only that teardown was requested. `OFFLINE_CONFIRMED` requires successful `DisableNetwork 1` acknowledgment through the official control API; it does not mean the process exited. `TERMINATED_CONFIRMED` requires death of the exact retained remote Binder. Unbind success, `stopService` success, elapsed time or superclass teardown return cannot produce that state.

An explicit new start is allowed only before any incarnation exists or after the previous Binder's death is observed. If startup fails before any Binder is obtained, termination remains unconfirmed and retry stays blocked. The UI explains this state without claiming native silence. A constructor/native-loader hang before the remote watchdog starts is part of this unconfirmed boundary.

The process shares the app UID. It is a lifetime/failure-containment boundary, not a sandbox against compromised native code with that UID.

## Evidence and remaining validation

Host tests compile the actual controller/protocol, remote service and pure deadline policy against explicitly labeled Android/vendor doubles. They exercise stale replies, wrong UIDs and owner Binders, delayed leases, owner death, immediate API/probe revocation before UI cleanup, hung control enable/read/disable/authentication monitor, hung superclass teardown, blocked outgoing IPC, and identity checks that forbid main-process termination. A recorded fake kill request is not a physical process kill.

Prepared Android instrumentation additionally observes actual Binder death for offline unclaimed startup and explicit stop while the main process survives. It never sends network-enabling START IPC. These sources must be compiled and run only under the separately approved disposable-device/signing workflow; source compilation is not runtime verification.

Live Tor bootstrap, on-device DNS/traffic observations, actual hung native behavior, Android lifecycle/ABI behavior, battery use and physical process termination remain device-test requirements. No global VPN, routing, firewall, OS security or network settings are changed.
