# Atomic lost-device recovery

The native device menu replaces capability-authenticated `/revoke` with the atomic
`POST /v1/devices/{device_id}/recover` flow. The user confirms that the old device,
other sessions and the old recovery capability will be revoked, and that lost
Signal keys/history are not recovered. Already-revoked historical devices may be
selected; the current local identity is not a recovery target in this UI.

`PendingDeviceRecoveryStore` persists an origin/account/target-bound replacement
capability and stable recovery ID before any HTTP request. The dedicated 4 KiB
AES-GCM namespace uses exact prior-byte compare-and-swap. A timeout, cancellation,
process recreation or uncertain fsync never generates a replacement request.
The stored phase becomes confirmed only after the exact three-field server result
matches the target and recovery ID and that confirmation is durable.

`DeviceRecoveryCoordinator` runs disk operations on the process-owned account
FIFO and accepts HTTP/callback work only for the active context generation. With
an expired session it first logs in using the persisted replacement capability.
Login success requires the backend contract’s HTTP 201; recovery success requires HTTP 200.
Only an exact transport-successful HTTP 401 `{code: UNAUTHORIZED}` permits trying
the old capability. Timeout, 503, malformed response, stale receipt and conflict
keep the checkpoint. Resume first reloads and compares the full persisted request,
so a stale Activity cannot replay a locally replaced checkpoint. A process-wide
mutation epoch is reserved under a short lock before each HTTP dispatch; writes
invalidate it before disk IO. This also closes the disk-result/main-callback gap.
No disk IO occurs under that dispatch lock. Successful login is bound to the requested account and
origin. The Activity checks the configured origin before even unauthenticated
login bodies can be transmitted. Capabilities never enter the packaged page,
public JSON state, URLs or logs.

After success, a FLAG_SECURE native, scrollable form shows the new key. Explicit,
default-off acknowledgement is required before a secret-free tombstone replaces
the checkpoint. Copy is a separate user action and warns about the clipboard.
Enrollment and maintenance wait while this account has an unresolved checkpoint.
The Settings server setup's stored-data menu can resume a pending recovery even
without a session. A different signed-in account or origin cannot resume it.
Explicit abandonment warns that it does not cancel a server request and may lose
account access; readable old and new capabilities are shown only in the native
form before the user confirms. Readable abandonment uses the displayed recovery ID and prior-byte CAS. A corrupt-state
reset rechecks that the record is still unreadable; a now-readable replacement is
preserved. Corrupt storage is never silently overwritten.

## Verification

- `scripts/test_device_recovery_store_host.sh`: 334 schema/assertion checks and
  21 actual checkpoint-wrapper scenarios with synthetic Android/Keystore doubles.
- `scripts/test_device_recovery_coordinator.sh`: 22 controlled disk/HTTP scenarios,
  including lost replies, durable acknowledgement, cancellation, wrong profile,
  malformed responses, session expiry and bounded capability fallback.
- `scripts/test_device_recovery_pilot.sh /absolute/path/to/tortalk-pilot`: unchanged
  production coordinator, response rules and encrypted checkpoint store against
  a real ephemeral Go pilot. Checks real auth HTTP 201, replacement-first/old-only
  clean-401 fallback, lost callback after actual server commit, server restart,
  byte-exact retry, revoked old session/capability refusal, preservation of newer
  sessions/devices, and persisted new-key acknowledgement. Direct loopback HTTP
  and callback fault injection exist only in the test adapter. Reuses explicitly
  labeled Android storage/Keystore doubles; coordinator/store objects are recreated,
  while the host JVM and its synthetic memory-only Keystore remain alive. This does
  not establish Android process death, hardware isolation or power-loss durability.
- `AndroidBridgeRuntimeChecks`: prepared native recovery form checks for explicit
  acknowledgement, FLAG_SECURE, scrolling, disabled autofill/state save, and no
  key in public state or DOM. These have been compiled, not executed on Android.

Actual SDK build/lint and an authorized Android runtime run remain separate
release checks for this overlay. Host fixtures do not establish real Keystore,
WebView, lifecycle or device execution behavior.
