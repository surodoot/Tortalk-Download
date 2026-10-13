# Packaged TorTalk design and bridge verification

`src/main/assets/ui/styles.css` is copied byte-for-byte from the approved `web/styles.css`.
The original welcome title, illustration DOM, invitation card, privacy cards, settings rows,
and three-tab navigation are retained. Browser-only desktop story, design comparison toolbar,
and simulated device hardware are removed from the native container. Demo fixtures are removed;
authentication, connection status, empty inbox, and native account dialogs replace demo actions.

## Run

`node frontend/embedded-tor/scripts/test_packaged_ui.cjs` uses Playwright and Chromium.
It captures original and packaged 390×844 screens, checks a pixel-identical welcome heading,
compares illustration width/colors/corners, checks unchanged CSS bytes, and exercises packaged
JavaScript using a synthetic native MessagePort with explicitly synthetic test data. It checks
empty inbox, Settings-only diagnostics, native-command dispatch, busy state, and escaped HTML.
This is not a test of Android WebView, Android Keystore, real Tor, or a live backend.

## Current results

- Canonical CSS equality: passed locally.
- JavaScript syntax: passed locally.
- Pure Java UI state, command allowlist, origin-bound invitations and draft ledger: 38 tests passed locally.
- Native response and bounded-history validation using pinned AOSP JSON: 19 tests passed locally.
- Packaged JavaScript logic: safe command dispatch, exact CSS/artwork preservation, escaped text, stable draft IDs and stale-clear protection passed locally.
- API26-compatible production sources and instrumentation sources compile against the API26 public surface, using compile-only TorService and API30 inset-adapter signature fixtures. This local check does not validate the API30 adapter body or JNI. The adapter subsequently passed real SDK37 compilation in the verified baseline below.
- Local Chromium rendering: blocked before browser launch, OS `socket()` EPERM, including an
  approved execution retry. A separate supported cloud-browser capture of the public approved
  site is saved locally under .tooling/qa/approved-live-cloud.jpg (not a source artifact); it is reference-only. Packaged desktop comparison
  is now verified separately in CI below; actual Android parity remains unverified.
- Android Activity/whole-app compilation, instrumentation, and device screenshot parity must be
  reported separately against the final integrated revision.

## Security boundary

The native WebView exposes no `addJavascriptInterface`, file/content access, remote navigation,
new windows, DOM storage, cookies, geolocation, or application resource networking. Only four exact packaged
asset paths on the pinned synthetic HTTPS origin can be served. CSP disallows connections,
frames, plugins, form submissions, and external resources. A native MessagePort is transferred only to the pinned main document. Each intercepted main-frame HTML response receives a fresh native nonce; the channel callback is bound to that immutable document generation and current port. JavaScript checks the exact document URL, top frame, nonce, one port, trusted event, null source and Android’s verified empty/opaque native sender origin. Rebinding closes old handlers; saved stale handlers cannot update state. Commands are checked for foreground state, length, nesting, exact keys, typed arguments and action allowlist.
There is no generic HTTP, URL, eval, key-export, or storage command.

Credentials and private crypto state stay in native code. Signup/login/recovery use native
FLAG_SECURE dialogs. Account creation credentials are encrypted and durably stored before the idempotent registration request. Pending registration can be resumed after process death without creating another account. Recovery acknowledgement survives foreground/background transitions; only explicit acknowledgement removes the pending recovery record.
Sessions are persisted only after an explicit, initially unchecked choice, with recovery token
excluded by default. Public room state is projected to approved fields before crossing the port.
All untrusted display strings are HTML-escaped. Actual security review is not a formal audit.

## Follow-up behavior

- A composer revision is bound to one native message ID, room and text. Background cancellation
  or a lost HTTP acknowledgement cannot silently mint a second message on the same draft retry.
  Draft clearing is triggered only after durable staging and checks document, revision and text.
- Inbox polling runs only in an open, foreground, connected chat, at 10 seconds with failure
  backoff to 60 seconds. Native prekey maintenance runs only while foreground/connected, at
  60 seconds with failure backoff to five minutes. All timers stop in the background.
- Local history is paged in contiguous groups of at most 40, with a measured JSON-escape budget; the full encrypted history is not sent to the WebView.
  Existing keyboard focus, selection and scroll position are preserved during state updates.
- Lost OTHER devices require owner-only metadata, known local device identity, recovery-key
  reentry and an explicit consequence acknowledgement. Current-device permanent revocation
  remains unsupported in this UI; logout is available. No identity/history is silently wiped.
- A changed peer identity shows old and new fingerprints and requires out-of-band confirmation.
  History remains; queued old-identity ciphertext is retired rather than automatically re-encrypted.
- The public/manual invite package binds the onion origin. Consuming the same invite reuses a
  stable account/origin/invitation-bound idempotency key. Camera scanning is not implemented.

## Android bootstrap origin correction

Android `WebView.postWebMessage` does not set the page origin as the sender origin. Chromium’s
`AwContents` passes a null source origin; Blink delivers a trusted event with null source and
an empty origin. The initial desktop self-post simulation therefore did not establish real
Android bootstrap compatibility and was replaced. The browser harness now invokes the unchanged
production handler with the verified native event shape through CDP; it still does NOT execute
Android WebView. Negative logic tests cover wrong origin, source, nonce, exact URL, non-top frame,
zero/two ports, untrusted events, duplicate authenticated rebind and saved stale callbacks.

Primary implementation evidence:
- https://chromium.googlesource.com/chromium/src/+/refs/heads/main/android_webview/java/src/org/chromium/android_webview/AwContents.java
- https://chromium.googlesource.com/chromium/src/+/0e358e86a57e35309ec254024d75f1a42c761b78%5E%21/

Real Android provider delivery and rendering remain a release gate. Optional chaining and
object-spread syntax were removed from the packaged runtime script to reduce old-provider
parse failures; this does not prove modern CSS support on every Android 26 WebView.

## Platform inset verification pending

The unchanged packaged design sits inside a native safe-viewport container. On API30+,
visible system bars, display cutout and IME use max-union padding; handled inset dimensions
are zeroed before forwarding rather than returning CONSUMED. API26–29 retain normal decor
fitting and adjustResize. Renderer recovery keeps the same outer container. Prepared Android
instrumentation checks measured WebView bounds plus synthetic bar/cutout/IME show/hide
updates and non-consumed, zeroed child insets. These sources have not been executed on Android.
The API30 implementation must pass real SDK37 compilation/lint, then actual navigation,
cutout, keyboard and gesture-navigation geometry checks on supported device/provider versions.

## Provider networking limits

Account, chat and application API requests use the native fail-closed Tor transport. The
packaged page cannot request external resources through this WebView configuration. This
does not establish device-wide or WebView-provider network silence: Android documents that
intercepted URLs are still eligible for Safe Browsing checks, and provider/GMS behavior must
be measured separately. No Safe Browsing or system security setting is disabled by this app.
Source: https://developer.android.com/reference/android/webkit/WebViewClient#shouldInterceptRequest(android.webkit.WebView,%20android.webkit.WebResourceRequest)

Native credential, recovery and verification forms now scroll within a bounded dialog custom
view, and dialogs request adjustResize while preserving FLAG_SECURE and no-save/autofill
controls. Prepared instrumentation adds synthetic rows to the actual server-setup form and
checks its height bound, scrolling, last-row reachability and secure/resize flags. This is
source preparation only; actual small-screen/landscape IME interaction remains unexecuted.

## First integrated candidate and account lifecycle follow-up

Candidate 0be94580 completed real SDK37/JDK25 Java compilation, D8, unsigned assembly
and AndroidTest Java compilation. Lint blocked that candidate; scoped compatibility annotations,
formatted native dialog resources and removal of an unused icon are prepared for the next run.
The first desktop job completed heading pixel equality and illustration metric comparison,
then failed because CDP listener references require a named object group. The harness now
uses that group, bounded waits and browser cleanup on errors. Full desktop bridge execution
and Android runtime remain unverified.

Account disk work now shares a process-owned FIFO. Explicit local logout/deletion cancels
session authority before asynchronous cleanup, and a generation-bound restoration fence
prevents another Activity from restoring after a failed deletion. Four deterministic host tests
cover prior-save/clear ordering, recreation-style loading and stale cleanup/failure fences.
A process-only fence does not survive process death if durable deletion itself fails; the
user receives that limitation and a retry action. Config origin/admission are one encrypted
authoritative record. Origin switch cleanup does not depend on a surviving Activity callback.
Interrupted signup first attempts same-origin authentication using its durable pending pair;
only an error-free HTTP401 permits immutable registration retry. Unreadable pending records
require a separate explicit warning/confirmation before deletion. No recovery credentials
are sent to the packaged page or logged. Prepared Android assertions cover held initial
loading, navigation preservation, pending origin/account guards and local logout before disk
completion across Stop. Those assertions have compiled but have not run on Android.

## Verified desktop result (2026-10-08)

Foundation #38, head `6423fdcb3fe82d0d0a16d85d5fd06feab0e5b00f`, passed the complete
packaged-ui job: https://github.com/surodoot/gyeopdam-chat/actions/runs/37833488609
Artifact: https://github.com/surodoot/gyeopdam-chat/actions/runs/37833488609/artifacts/11574083831

At 390×844, canonical and packaged primary buttons have the exact rectangle
(x29, y718.40625, width332, height52). The login target is (x29, y782.40625,
width332, height44), without overlap. Both heading images are332×114 with no pixel
difference. Illustration metrics, empty inbox, native-command dispatch, Settings-only
diagnostics, HTML escaping, send/busy behavior and the simulated native-origin MessagePort
bootstrap all passed. DEMO labels and demo-only copy are replaced with actual connection,
signup and login information. The entire screen is not claimed pixel-identical where that
functional copy differs. Native Android provider delivery, dialogs, system insets and device
screenshots remain separate unexecuted runtime gates.

The subsequent native QR and private Tor-process status changes are not part of that
verified baseline. QR has23 pure JVM tests and prepared Android bitmap/provider/lifecycle
checks; see CONTACT_QR.md for exact limits.
