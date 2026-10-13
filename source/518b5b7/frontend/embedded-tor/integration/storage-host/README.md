# Production storage host regression checks

Run `bash scripts/test_keystore_blob_store_host.sh` from `frontend/embedded-tor`.
The script compiles the **actual current production** `KeystoreBlobStore`,
`EncryptedBlobEnvelope`, and `SessionSchema`. No production hooks or source copies
are used. All fixtures are outside Android main/test source sets and must never be
packaged into an APK.

## Explicit doubles

- `android.content.Context`: a fresh private temporary host directory and synthetic
  application ID.
- `android.util.AtomicFile`: host file fsync followed by atomic replacement; no
  directory fsync. Legacy `.bak` recovery is modeled too. This is deliberately
  **not Android's implementation** and does not establish API26 power-loss behavior.
  For read-file-sync failure injection, a FileInputStream subclass closes at EOF
  after delivering all bytes; the following production `getFD().sync()` then fails.
- `android.system.Os`, `OsConstants`, `StructStat`, `ErrnoException`: fake directory
  descriptors and configurable open/stat/fsync/close failures. Flags are symbolic
  fixture values, not Linux ABI constants. Production must use the public API26
  `O_RDONLY | O_NOFOLLOW`, verify `S_ISDIR(fstat(fd).st_mode)`, fsync, then close.
- `FakeAndroidKeyStoreProvider`: a clearly labeled Java provider registered under
  the name required by unchanged production code. Synthetic random AES-256 keys
  live only in a process-local map. Key import and serialization are forbidden.
  Actual AES-GCM operations use the host JCA implementation. Export prevention,
  hardware protection, Android authentication and key invalidation are not modeled.

## What these checks prove

Faults are injected **after completed ciphertext replacement**, including during
the second commit of an overwrite. Production reports `IO_FAILED` without rolling
back or overwriting the committed bytes. Subsequent reads, new store instances,
write attempts, and read-or-create attempts remain blocked until a successful
directory barrier. Once recovered, exact original staged bytes are released;
pending state is not initialized again. Read barriers occur before key lookup.
An exception thrown by `finishWrite` after it has already replaced the file must
also preserve the replacement, avoid `failWrite`, and defer release to a successful
read barrier.
An injected read-file-sync failure also blocks reads, write attempts and
read-or-create without releasing plaintext, generating keys or changing existing
ciphertext. Directory synchronization and key lookup must not run until the file
barrier succeeds.

Additional scenarios cover directory-open/stat/close errors, a non-directory
descriptor, clear-barrier failure, backup recovery, corrupt ciphertext, missing
keys, namespace isolation, partial first-write residues, input bounds and explicit
clear/key rotation. Tests check fixed error codes and absence of retained provider
exception causes. No secret values are printed.

The script uses no external test dependencies, makes no network requests, never
calls Android, creates no APK/signing keys, and removes its temporary ciphertext
files. No real credentials or persistent cloud keys are generated.

## Limits

This is a regression test of production control flow against doubles, not evidence
that a particular Android filesystem, AtomicFile, AndroidKeyStore, or device
survives a power failure. API compatibility is verified separately against the
official API26 surface. Real device crash/power-loss tests, hardware isolation,
backup behavior and physical secure-erasure guarantees remain unverified.
