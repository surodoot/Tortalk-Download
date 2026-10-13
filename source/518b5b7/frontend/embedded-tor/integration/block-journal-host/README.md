# Block decisions and pending mutation journal host checks

Run `bash scripts/test_peer_block_journal_host.sh` from `frontend/embedded-tor`.
This compiles the current production `PeerBlockJournal`, `PeerBlockJournalSchema`,
`KeystoreBlobStore`, `EncryptedBlobEnvelope`, and `SessionSchema`. The fixture
reuses the explicitly synthetic Android storage doubles documented in
`../storage-host/README.md`. Fixtures are outside Android main/test source sets
and must never be packaged into an APK.

`PeerBlockJournalSchemaReview` performs pure validation with no disk or keys.
It covers canonical round trips, eight maximum-length pending requests plus
1,024 maximum-length confirmed statuses below 256 KiB, strict `demo-` IDs,
onion/account binding, self-peer rejection, duplicate and unsorted peer entries,
every truncation, oversized lengths, invalid revisions and boolean encodings,
legacy holds, rejected stored absence rows, trailing bytes, and the exact
namespace digest contract. Only `blocks-<64 lowercase hex>` namespaces receive
the 256 KiB cap; session, config, and enrollment remain limited to 4 KiB.

`PeerBlockJournalHostReview` exercises the real production wrapper and encrypted
storage against host doubles. It checks no-key read-only loading, exact retries
without ciphertext changes, conflicting requests, bounded capacity, exact/stale
acknowledgements, preserving unrelated peers, and an encrypted empty journal
without key deletion. A Context-only hook injects a competing operation between
snapshot read and commit-lock acquisition to check exact-byte CAS, including
absent-versus-empty state and a newer request for the same peer. No hooks or
alternate storage implementation are added to production.

Fault cases cover corrupt/decryption/schema/binding failures, missing keys,
file/directory durability barriers, uncertain commits and resumption, and
account/origin namespace isolation. Explicit `resetAfterConfirmation()` is
checked to remove only the selected block journal while preserving independent
session/history/account namespaces.

Confirmed statuses survive wrapper recreation, including unblocked tombstones
that reject stale block responses. Status and pending acknowledgement commit
atomically in one replacement. Exact confirmed replays do not rewrite ciphertext,
and old acknowledgements cannot clear a newer pending request. Status pages
apply at most 64 unique peers in one commit; any regression or capacity failure
rejects the entire page. Tests also cover unchanged-page no-ops, all 1,024 status
slots plus eight pending requests, confirmed-status CAS races, and uncertain
status-plus-acknowledgement commits whose exact result is recovered on read.

## Native caller contract

- Construct `PeerBlockJournal(context, canonicalOnion, accountId)` for the
  authenticated account. The namespace is `blocks-` plus lowercase hexadecimal
  SHA-256 of the ASCII bytes `origin + "\n" + accountId`; the namespace is also
  part of `KeystoreBlobStore`'s AES-GCM associated data. The schema includes the
  full binding and validates it on every operation.
- Use `AccountWorkQueue.INSTANCE` for all journal disk operations. Generate a
  mutation ID in the caller, build the immutable
  `Pending(peerId, mutationId, expectedRevision, blocked)`, and send only after
  `prepare()` succeeds. Retry the exact loaded request after an unknown outcome.
  The journal neither chooses network retries nor changes operation IDs.
- `load()` returns an immutable, peer-sorted list. A missing file is an empty
  list. `prepare()` is a no-op for an already identical request and rejects a
  different pending request for that peer. `acknowledge(peerId, mutationId)`
  removes only that exact request; an absent peer is a no-op, and a mismatched
  mutation ID fails. Every mutating commit compares the exact snapshot bytes
  under the production storage lock. `STATE_CHANGED` requires a fresh load and
  reconciliation; it is never permission to send an unpersisted request.
- `loadStatuses()` returns immutable, peer-sorted confirmed actor-owned
  `Status(peerId, blocked, revision, mutationId, legacyHold)` records. It does not
  generate a key. Revision-zero absence (`false`, empty mutation ID, no legacy
  hold) is never stored. Migrated legacy holds (`true`, revision zero, empty
  mutation ID, legacy hold) are stored; all positive revisions require a valid
  mutation ID and no legacy flag. Confirmed unblocked rows remain as tombstones.
- `confirm(status, acknowledgeMutationId)` rejects lower revisions or changed
  content at an existing revision with `STATE_CHANGED`. Empty acknowledgement ID
  updates status only. A nonempty ID must equal both the status and pending
  mutation IDs, and the pending peer, desired state, and expected revision plus
  one must exactly match. It records status and removes the pending entry in one
  commit. A completed identical replay with no pending request is a no-op;
  another unmatched acknowledgement is `STALE_ACK`. `acknowledge()` is only
  an explicit local forget and always retains confirmed statuses and floors.
- `confirmAll(page)` validates at most 64 unique peer statuses and applies all
  monotonic updates atomically in one CAS write, preserving pending requests.
  Exact unchanged pages do not write. Confirmed status capacity is 1,024 and
  never evicts tombstones. `prepare()` rejects a new request below its peer's
  persisted revision floor, while still allowing an exact existing pending retry.
- `JournalException.code` is one of `INVALID_INPUT`, `CORRUPT_STATE`,
  `KEY_UNAVAILABLE`, `IO_FAILED`, `ORIGIN_MISMATCH`, `IDENTITY_MISMATCH`,
  `PENDING_CONFLICT`, `CAPACITY_REACHED`, `STALE_ACK`, or `STATE_CHANGED`.
  Invalid constructor arguments throw `IllegalArgumentException` with a fixed
  code. No underlying provider cause or request identifiers are included.
- Call `resetAfterConfirmation()` only after separate explicit user approval
  explains that unconfirmed local requests and confirmed local revision floors
  will be discarded, the server may already have applied pending requests,
  and chat history is untouched. Refresh server state after reset before using
  the account's block decisions. Ordinary operations never reset corrupt state.
  Reset does not change server blocks.

## Verification limits

All values and keys are synthetic; keys live only in the host JVM. Temporary
ciphertext is removed when the script ends. There are no downloads, network
requests, production key generation, Android runs, emulators, signing or installs.
These checks do not establish actual AndroidKeyStore/AtomicFile behavior,
power-loss durability, hardware key isolation, physical erasure, API26 runtime
behavior, HTTP replay handling, Activity queue use, or process-death UI recovery.
Those remain separate integration/runtime checks.
