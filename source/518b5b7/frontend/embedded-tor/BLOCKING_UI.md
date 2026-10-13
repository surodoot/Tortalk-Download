# Native peer blocking and local archive integration

The existing Contact and conversation shield actions open native management dialogs. Main
layout/CSS remains unchanged. Block status always describes the authenticated actor's own
choice; the other participant's choice is neither requested nor inferred.

## Authority and ordering

- Every request uses the current session bound to its canonical onion origin and account.
- Native status parsing requires exactly five public fields, exact peer ID, typed booleans,
  bounded integer revisions and valid mutation/legacy combinations. Pages are bounded,
  strictly ordered and require a progressing exact-tail cursor.
- A confirmed local block intent immediately holds send/retry/poll, even before disk runs.
  Cancelling navigation revokes future HTTP/UI callbacks without dropping that hold.
- The encrypted journal persists the exact peer/action/revision/mutation ID before HTTP.
  Retry reuses the same request. A changed server revision never triggers an automatic
  replacement intent. Unknown results remain explicitly unconfirmed.
- Server-confirmed own state and revision floors are durably recorded before success is
  displayed, atomically with exact pending acknowledgement. Unblocked tombstones remain,
  and lower or equal-conflicting revisions are rejected after recreation too.
- Cancelled disk completions still reconcile committed local state within the same profile.
  Profile changes and destruction suppress stale callbacks. Renderer loss cancels the
  block coordinator as well as message/API work.
- Local forget is an explicit local-record action, never a claim to cancel a server request.
  Corrupt-journal reset warns that pending intent/last-confirmed state can be lost; it leaves
  server blocks and encrypted message history alone and clears active chat selection.

Only the account/origin-scoped blocks namespace has a 256 KiB cap, for 1,024 confirmed
records plus eight pending requests. Session/config/enrollment storage limits are unchanged.
All persistence uses the process-owned AccountWorkQueue and exact-byte CAS.

## User-visible consequences

Block confirmation explains permanent closure of old rooms/invitations and removal of
retained undelivered server ciphertext. Already received messages and local encrypted
history are not erased. Unblock removes only the actor's own decision, does not resurrect
old rooms/invitations/ciphertext, and requires a fresh invitation. Pending ciphertext from
an old closed room is never automatically resent.

Actor-owned list and pending-request management remain reachable through Contact even
when the server no longer lists the old room. Native local-history listing reads bounded
verified associations from the current encrypted profile, without Tor, enrollment, claims,
ACK or key generation. The existing chat layout labels archives as local/read-only; native
and rendered send/retry gates remain closed. Older batches use the local cursor; the Recent
control explicitly returns to the latest batch. Archive mode/peer/cursor survive the native
configuration-retention path, which restores through openLocalHistory rather than live-room
opening. Navigating elsewhere during startup overrides the retained view.

## Verification (source/host; Android runtime pending)

- 489 journal schema assertions and 35 actual journal-wrapper host scenarios, including
  ciphertext-preserving retry, CAS races, atomic confirm/ack, revision floors and faults.
- 14 production coordinator tests with controlled disk/HTTP order, plus 15 strict status
  tests. Together with existing response/history tests: 48 native JSON tests pass.
- 38 standalone UI tests and packaged JS logic assertions pass, including archive send/
  retry suppression, escaped protection text, renderer cancellation and retention wiring.
- Full production and Android-test Java compile against the official API26 surface with
  explicitly compile-only API30/vendor signature fixtures. Actual SDK37/lint, final browser
  CI and Android device/lifecycle execution must be reported against the integrated commit.

The prepared Android retention check exercises the real Activity's snapshot/restoration
method. It is not an executed OS rotation test. Existing archived Signal/coordinator tests
and prepared Android runtime checks are reported separately by their owning suite.
