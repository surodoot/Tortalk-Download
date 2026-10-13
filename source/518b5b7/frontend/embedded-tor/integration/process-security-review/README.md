# Independent Tor process security review

Reviewed the actual controller, private process service, protocol, deadline policy,
application manifest, pinned Tor AAR manifest/bytecode, and process-boundary notes.
These fixtures compile the production sources with the existing explicitly labeled
Android/vendor host doubles. No Android device, native Tor, real process termination,
signing, network provisioning, or Git publication is exercised.

## Regressions added

Run from the `embedded-tor` directory:

```sh
bash integration/process-security-review/run-controller.sh
bash integration/process-security-review/run-remote.sh
```

The controller suite checks five conditions:

- Death of a replacement/wrong Binder cannot authorize restart; death of the exact
  retained Binder can authorize a subsequent explicit start.
- Denied `stopService` and `unbindService` calls preserve API revocation and cannot
  fabricate termination, even after the confirmation deadline.
- Previous-generation connection callbacks and replies cannot mutate a new session.
- API transport authority is revoked when Binder death is observed, before the main
  callback queue drains.
- Developer-probe transport authority is likewise revoked before main cleanup.

The remote suite checks seven conditions:

- A foreign owner's START cannot stop the legitimate owner's incarnation.
- Malformed traffic from a foreign owner cannot stop the legitimate incarnation.
- Missing owner identity cannot stop an already claimed incarnation.
- Malformed traffic from the actual owner still fails closed.
- A changed synthetic current PID cannot reuse previously verified kill authority.
- A foreign owner cannot renew the legitimate owner's foreground lease.
- A wrong sender UID cannot stop an active session, even with the owner's reply Binder.

## Findings resolved during review

The initial implementation failed three owner-isolation cases because schema failure
and duplicate START processing occurred before owner-Binder comparison. The service
now rejects mismatching or missing owner identity before parsing traffic to a claimed
incarnation. Legitimate-owner protocol errors remain terminal.

The initial death notification only posted main-thread cleanup, leaving API and probe
readiness temporarily valid. The controller now publishes observed death immediately,
invalidates the synchronized readiness gate using the immutable attempt gate epoch,
and checks the exact retained Binder from API transport workers. Main-thread cleanup
still owns cancellation callbacks and restart state. An old attempt cannot invalidate
a newer readiness epoch.

Actual pinned vendor code publishes its control connection before authentication
finishes. The wrapper now checks the vendor's post-authentication marker under that
connection's monitor before enabling networking. The marker is not trusted as the
ready listener; fresh, strict control samples are still required. The original service
suite adds authentication-pending and hung-auth-monitor coverage, including rejecting
late enablement after the command deadline.

## Scope and remaining evidence

No path was found that accepts a caller-supplied PID or invokes a process kill from the
main controller. The only production kill call uses the current process PID after
verifying the app UID and exact private-process identity. Process death is tied to the
retained Binder rather than a cached PID, so PID reuse cannot establish the old
incarnation's restart permission. Stop acknowledgments, successful unbinding, denied
stop calls, and elapsed timers are not process-death proofs.

The watchdog does not acquire the vendor control monitor, perform control I/O, or send
Binder replies. Its policy monitor contains only bounded local state operations. The
authentication monitor and service-main/control-worker stalls remain subject to its
independent deadlines. OS suspension or a frozen runtime is outside software deadline
guarantees.

The actual AAR declares `org.torproject.jni.TorService`, exactly matching the source
manifest removal marker. This review did not produce a merged manifest or APK for the
follow-up. Actual merger output and Android process/Binder/native lifecycle behavior
still require the separately authorized build/device workflow. Host fake kill records
prove the selected target and deadline logic, not physical termination. The shared app
UID remains a failure-containment boundary, not a sandbox against compromised native
code with that UID.

## Unix endpoint migration

These host scripts now compile the production `TorSocketEndpoint`, carry version-2
`socksReady`/`pid` replies, and reuse the labeled fixed-path filesystem seams. The same
exact-Binder death, stale-generation, immediate API/probe lease revocation and owner
isolation scenarios run against Unix endpoint authority. They do not prove real Unix
socket peer credentials, filesystem protection, or native compatibility.
