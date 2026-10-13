# Offline public contact QR codec

`ContactCard` and `ContactQrCodec` are pure Java boundaries. They do not access
files, a network, camera, Android framework, clipboard or browser. The contact
asserts an identity; it does not prove who owns the identity. A native caller
must show the imported account/device and fingerprint and receive explicit
confirmation before changing a Signal peer pin.

## Contact format and API

`ContactCard.create(serverOnion, accountId, deviceId, protocolDeviceId,
identityKeyBase64)` validates and creates an immutable card with public final
fields of those names plus `keyDigest`. `card.encode()` returns six exact
newline-separated fields, with no trailing newline:

1. `tortalk-contact-v1`
2. Canonical `http://<56 lowercase base32 characters>.onion` origin
3. Public account ID
4. Public device ID, distinct from a device authentication token
5. Canonical decimal Signal protocol device ID in `1..127`
6. Canonical standard Base64 of the 33-byte serialized public identity key,
   whose first byte is `05`

`ContactCard.decode(payload, expectedOrigin)` accepts at most 1024 ASCII
characters. It rejects unknown versions, extra/missing fields, CRLF and control
characters, noncanonical Base64, token/URL/markup syntax in IDs and origin
aliases. IDs follow `UiCommandPolicy.validOpaqueId`. Both origins must be
canonical and match exactly. Cards cannot change server configuration. The
transport's existing origin syntax policy is reused; Tor performs onion-address
checksum validation when used, rather than this codec implementing custom crypto.

`keyDigest` is lowercase SHA-256 of the raw 32 identity bytes, **excluding** the
leading serialized `05`, matching `SignalJson.keyDigest` and the relay. The
serialized 33-byte key remains the payload field. Fixed input failures are
`CONTACT_INVALID` or `CONTACT_ORIGIN_MISMATCH`, with no source input or cause in
their messages. `toString()` redacts contact data.

Admission, session, device-authentication, recovery and account secrets have no
field in this format. Native code must build a card from the listed public
values only; the parser cannot detect a secret deliberately disguised as a
syntactically valid public account or device ID.

## Pixel API and native import boundary

- `ContactQrCodec.encode(card, side)` returns a new `int[]` of opaque black/white
  ARGB pixels in row-major order, exactly `side * side`. Supported square sizes
  are 256 through 1000. It uses QR error-correction level H and a four-module
  quiet zone.
- `ContactQrCodec.decode(width, height, pixels, expectedOrigin)` returns a
  validated `ContactCard`. It calls ZXing `QRCodeReader` directly, accepts only
  QR results, rejects structured-append fragments, and runs the same strict
  contact parser on the decoded text. It does not interpret URLs or other
  actionable barcode payloads. If an image contains several independent QR
  symbols, ZXing may select one; the user must review the selected contact.
- `ContactQrCodec.validateDimensions(width, height)` rejects nonpositive sizes,
  either side over 2048, or more than 1,000,000 pixels, using long multiplication
  before allocation. Decode also requires the exact pixel-array length.
- The native importer must cap compressed input bytes, inspect image headers,
  call `validateDimensions` **before decompression/allocation**, and verify actual
  decoded dimensions again. The pure pixel codec cannot bound a decompression
  that already occurred. Do the image/QR work off the UI thread and preserve
  existing lifecycle/account guards when returning a result.
- Fixed image failures are `CONTACT_QR_BOUNDS` or `CONTACT_QR_INVALID`. Contact
  validation failures keep the contact error codes above. No exception includes
  decoded input or an upstream cause.

## Dependency review, 2026-10-08

Only `com.google.zxing:core:3.5.4` is needed; there is no Android wrapper,
`javase`, camera permission, JNI library or runtime transitive dependency.
The official core POM declares only a JUnit test dependency. The QR writer,
reader and RGB luminance classes in the retrieved jar use Java 8 classfiles
(major 52). This inspection is not Android runtime verification.

- Official release/tag: <https://github.com/zxing/zxing/releases/tag/zxing-3.5.4>
- Official project and maintenance policy:
  <https://github.com/zxing/zxing/blob/zxing-3.5.4/README.md>
- Artifact:
  <https://repo.maven.apache.org/maven2/com/google/zxing/core/3.5.4/core-3.5.4.jar>
- POM:
  <https://repo.maven.apache.org/maven2/com/google/zxing/core/3.5.4/core-3.5.4.pom>
- Upstream license:
  <https://github.com/zxing/zxing/blob/zxing-3.5.4/LICENSE>
- Writer/reader implementation:
  <https://github.com/zxing/zxing/blob/zxing-3.5.4/core/src/main/java/com/google/zxing/qrcode/QRCodeWriter.java>
  and
  <https://github.com/zxing/zxing/blob/zxing-3.5.4/core/src/main/java/com/google/zxing/qrcode/QRCodeReader.java>

The official project is in maintenance mode: contributed bug fixes and minor
enhancements, with no active development roadmap. Version 3.5.4 was the latest
official release displayed during this review; it includes pixel-array input
validation and luminance-source rotation updates. The discontinued Barcode
Scanner Android application is not used.

Retrieved jar size: 610,364 bytes. SHA-256:
`71de5d89341b5fcf5dd89da7f44e84d825d0e084cdf3ec77c9abe26b0f0ceb13`.
The official Maven SHA-1 sidecar matched
`955fcd6bcd0723ddfb8ee6ed502d5fdf0e9676a9`; no SHA-256 sidecar was available.
POM SHA-256:
`502c7368ae869f15428c020a2432eec9dde7436454f3edd1816e0d4b3d0609bf`.
The SHA-256 pin is recorded in `scripts/contact-qr-artifacts.sha256`. These
checks pin the fetched official bytes; they are not independent signature
authentication, a reproducible-build audit or proof of absence of vulnerabilities.

Core is Apache-2.0 licensed. Preserve the complete upstream LICENSE in Android
assets when integrating the dependency, because the retrieved core jar contains
no LICENSE/NOTICE entry. The complete source LICENSE has SHA-256
`3f62881f0566227a24b12e5a754cc79f39aaa94883038e95c94812e1f50af42f` and includes a
`jai-imageio` appendix for the wider upstream project. Core's POM adds no
`jai-imageio` runtime dependency. Keep the dependency byte check in the actual
Gradle resolved-runtime verification path before packaging.

## Verification

After separately provisioning the pinned jar into
`.tooling/contact-qr/core-3.5.4.jar`, run:

```sh
bash frontend/embedded-tor/scripts/test_contact_qr_standalone.sh
```

`ZXING_CORE_JAR` can point at the same pinned jar elsewhere. The script verifies
checksums, compiles only the required pure-Java sources plus production
`SignalJson` for the digest comparison, and runs 23 JUnit tests. It does not
download/install dependencies or invoke Android/Signal JNI operations.

Passing coverage includes public-card roundtrips, the production raw32 digest
convention, canonical origin/ID/decimal/Base64 parsing, secret-like extra fields,
ASCII and size boundaries, quarter-turn rotation, H error correction under small
synthetic damage, bordered rectangular images, rejection of Data Matrix and
noncontact QR payloads, pixel-array mismatch and multiplication-overflow bounds.
These host tests do not establish Android picker/Bitmap behavior, native review
dialog behavior, screenshot/export availability or two-device messaging.

## Native Android integration

The existing native contact action displays the QR after device registration. A system
OPEN_DOCUMENT picker requests local-only openable images, without camera/storage permission
or a persisted URI grant. Android content providers can perform their own IO; only the QR
codec is guaranteed offline, and provider IO is not claimed to traverse Tor. Images stay
native and are never sent to JavaScript or an API. Import accepts content URIs, PNG/JPEG,
at most 4 MiB encoded input and an 8192-per-side header; power-of-two sampling is validated
before decompression and decoded pixels are checked again against the codec's 1M/2048 limits.
Cancellation closes the acquired input stream; a 15-second UI deadline cancels the result
and a read-loop deadline bounds progressing reads. Platform/provider cancellation execution
remains an Android runtime test requirement.

Results are bound to the captured account, configured origin and operation generation. A
changed session or stopped/destroyed activity invalidates processing; only an outstanding
user-visible picker is retained across its expected foreground transition. Imported contacts
can populate an invitation ID field. A peer comparison additionally requires exact current
room/Peer identity, account/device/protocol/public-key match, then the existing unchecked
explicit verification/replacement consent. Scanning alone never pins a key, creates a room,
changes server configuration or sends a message.

AndroidQrRuntimeChecks prepares in-memory bitmap PNG round-trip, wrong-origin, compressed
and header size limits, malformed image and cancellation-before-provider checks. These
sources compile against API26 but have not been executed on Android.
