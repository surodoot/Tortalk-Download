# Native message coordinator host integration checks

Run from a checkout with the existing pinned libsignal/JSON test jars already provisioned:

```
frontend/embedded-tor/scripts/test_message_coordinator_host.sh
```

The script verifies the existing libsignal/Kotlin/Android JSON artifact checksums and performs no downloads or public-network requests. All generated keys and state are synthetic and memory-only. Compiled classes go under `embedded-tor/.tooling/coordinator-host/`.

## Real components

- Production `MessageCoordinator`, `SignalClient` and `SignalJson`
- Real pinned libsignal JVM JNI operations and ciphertext
- Production account session schema, profile namespace, request-origin validation and UI ID validation

## Controlled test doubles

- Main-thread identity and callback queue (`android.os.Looper`, `Handler`)
- Application context
- Atomic compare-and-save storage backed by an in-memory namespace map, with write-failure injection
- Recorded HTTP requests with explicitly supplied responses

The Android doubles live under `integration/coordinator-host/`, outside the Android `src/main` and `src/test` source sets. They must never be included in an APK or Android instrumentation classpath.

## Passing scenario groups

1. Device credential is committed before HTTP; timeout retry sends the exact same registration values.
2. Unverified peers cannot send; ciphertext is durably staged before HTTP and retry keeps its exact message ID and JSON envelope.
3. A failed received-state commit does not ACK or expose plaintext; success commits ratchet/plaintext before ACK. Expired send-window metadata does not suppress receiving.
4. A previously pinned third account is rejected when the selected room expects another peer.
5. A canceled enrollment response cannot bind a new profile or invoke the obsolete callback.
6. After relay acceptance, a failed local acceptance write returns a queued error while the staged pending message remains visible. Retrying the same native draft ID uses identical ciphertext and keeps exactly one outbox/history record.
7. Cancellation at the durable staging checkpoint suppresses HTTP and the obsolete completion. Retrying the preserved native draft ID resumes the exact ciphertext with a single outbox record.

8. Prekey maintenance durably stages a twelve-pair refill at remaining=8. A lost response, a fresh coordinator/profile owner, malformed success, and a failed confirmation write all retain and replay the exact batch, including when the server reports a full pool. Valid durable confirmation clears the batch; remaining=9 with no pending batch does not refill.
9. Own-device history is fetched before enrollment. A scoped synthetic random provider deterministically selects the first available protocol ID: historical IDs 1–4 force new ID 5 rather than a collision. The provider is removed immediately afterward.
10. Peer rotation requires the distinct replacement approval and carries both old/new fingerprints. A failed rotation commit keeps the old pin. Successful rotation preserves sent/received history and exact old ciphertext, marks the old-device outbox `retired_identity`, avoids sending it, and establishes new outbound traffic with the new identity.

11. A changed identity retaining the same device ID still gets a durable retirement flag, `retired_identity` view status, and exclusion from pending retransmission. This is a defensive client scenario; the normal backend replacement flow issues a different device ID.

12. Previously accepted outgoing history keeps `server_accepted` after identity retirement, while its ciphertext remains ineligible for retransmission.
13. A bound profile can list and open its encrypted local history with no device enrollment or HTTP request. Reads succeed with writes disabled and preserve the exact state bytes.
14. Wrong origin/account/peer, missing local rooms, malformed cursors and invalid bounds fail closed. An empty profile stays empty; listing does not generate an identity.
15. A 203-message history is navigable in bounded pages with no skipped or duplicated entries; local room listing supports bounded peer filtering or all verified rooms in the profile.
16. Archive selection immediately disables readiness, including while its read is queued. Enrollment, live-room opening, pin/replacement, maintenance, sending, polling and retries all return `LOCAL_ARCHIVE_READ_ONLY` without HTTP or state changes.
17. Identity replacement retains discovery of old rooms whose current ratchet/pin binding was removed, including accepted, retired-pending and received messages. Reading never reconstructs a session or revives ciphertext.
18. Selecting an archive supersedes pending inbox/send HTTP. Delayed responses cannot ACK, commit, invoke obsolete callbacks or restore readiness.
19. Twelve deterministic worker races change cancellation, account or origin before a read and before main-thread delivery, for both listing and opening. Stale results never expose old history or invoke obsolete completions.

All nineteen groups run by default; no special flag is required or accepted. The old staging-gap reproduction is now covered by mandatory positive regressions 6 and 7.

## Restart and randomness boundaries

The refill restart test closes the old coordinator, removes that profile's cached owner, and opens a fresh coordinator/SignalClient over the retained in-memory storage snapshot. This exercises durable state reload without writing private keys to disk; it is not an Android process-kill test.

The historical-protocol test temporarily inserts a deterministic, synthetic-only Java random provider while first generating its fixture profile. It is removed in a `finally` block. All resulting credentials are test-only and memory-only. The real libsignal JNI code is still used. This fixture must never be packaged or called by production code.

## Verification limits

These are orchestration checks, not Android instrumentation. They do not establish Android Keystore encryption/fsync durability, actual Android JNI loading, WebView behavior, Tor lifecycle/service behavior, or real HTTP transport correctness. Other suites cover those primitives separately; physical/emulated Android integration still requires its own run.
