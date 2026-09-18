# Lingo

Translates WhatsApp messages into your language, in place, on Android.

Open a chat, tap the floating globe, and every message in a language that isn't
yours is repainted with its translation inside the original bubble. Tap again to
stop. Nothing is sent anywhere: the language detection and the translation both
run on the phone.

## How it works

Two things have to happen per message, and both are chosen for speed.

**Getting the text.** An accessibility service reads WhatsApp's view hierarchy
and pulls out each message string together with the exact screen rectangle it
was drawn in. The overlay then paints over those rectangles.

**Translating it.** Google's ML Kit runs language identification and translation
locally, using the same models Google Translate downloads for offline use.

## Why not a screenshot, and why not the cloud

Two obvious designs were rejected for the same reason: latency.

*Capture the screen and OCR it* would be slower than reading the tree, would
guess at text that is already sitting in the view hierarchy exactly right, and
would be stale the instant the list moved — a captured frame is a still image,
so a pinned overlay drifts the moment you scroll. Reading the tree gives the
true string *and* a rectangle that moves with the scroll.

*Call the Cloud Translation API* would add 100–300 ms of network per message, on
a surface that has to keep up with a finger flinging a chat list. The overlay
would visibly trail the text it is covering, and it would stop working on the
underground. On-device costs one model call the first time a message is seen and
a hash lookup every time after.

### A note on "Android's built-in translation"

Android does have a platform translation API (`android.view.translation`,
API 31+), but it is gated behind a system `TranslationService` that in practice
is only wired up for Google's own Live Translate flows. A third-party app
calling it gets back empty capabilities on almost every device, so it is not a
usable path. ML Kit is the closest thing that actually works, and it is the same
underlying technology.

## Install

Every push builds an APK and publishes it to [Releases](../../releases).
Download the `.apk` on your phone and open it.

Then:

1. **Enable the accessibility service.** Settings → Accessibility → Installed
   apps → Lingo. This is normally the only permission needed.
2. **Open WhatsApp.** A round globe button appears at the edge of the screen.
3. **Tap it** to translate the conversation; tap again to stop. Drag it anywhere.

An accessibility service can post its own overlay windows, so the "draw over
other apps" permission is usually unnecessary. The app falls back to it
automatically if an OEM build rejects the accessibility overlay — that is what
the second button in the setup screen is for.

### Language packs

Translation is offline, so each language needs a one-off download of roughly
30 MB. That happens automatically the first time a language turns up, and the
button's ring turns amber while it runs; until it finishes, messages in that
language stay exactly as WhatsApp drew them. Settings has a picker to pull packs
down in advance, and a Wi-Fi-only toggle (off by default, so a language you run
into on mobile data still works).

### Updates install over the top

Builds are signed with a fixed key checked in at `keystore/lingo.jks`, and every
build gets its own `versionCode` from the CI run number, so Android treats each
new release as a normal upgrade.

This is deliberate. AGP generates a debug keystore *per machine*, so each CI
runner was signing with a different key, and Android refuses to install an update
whose signature does not match the installed app.

The trade-off: the signing key is public, so anyone could build an APK that
Android would accept as an update to this one. For a personal sideloaded app
that is a fair price for being able to update from a phone. To close it, move the
keystore into a GitHub Actions secret and drop it from the repo.

## How it stays smooth

This is the part the app is actually built around.

- **Scans run on a background thread.** Fetching and walking a window's node tree
  is hundreds of IPC round trips; doing that on the main thread during a fling is
  what would cause jank.
- **Translation never blocks a scan.** The scan loop only ever reads the
  translation cache. Anything missing is queued and picked up on a later pass, so
  a slow model call can never stall a repaint.
- **A message is left alone until its translation is genuinely ready.** That is
  why nothing ever flashes as an empty coloured box.
- **Translations are cached by text, not by position.** Scrolling changes every
  rectangle's `y` sixty times a second but never a message's content, so
  scrolling back over a conversation costs nothing.
- **Arriving translations rebuild from cache, not from the tree.** Results land
  in bursts, out of order. They are coalesced into one repaint, and that repaint
  is a few hash lookups rather than another tree walk.
- **Text layout is cached against text and box size, never position.** A scroll
  costs one canvas translate rather than a re-layout.
- **Identical frames are never submitted.** The patch list is hashed, and a
  repaint that would change nothing is dropped before it reaches the UI thread.
- **Scans are paced against their own cost.** A walk that took 90 ms schedules
  the next one 135 ms out, so roughly half the time is left idle for WhatsApp's
  own rendering. Floor 60 ms, ceiling 1 s.
- **Strings that are not messages are dropped before anything expensive.**
  Timestamps, tick marks and unread counters fail a two-letter test that costs a
  handful of character comparisons.
- **Messages already in your language are skipped entirely.** Detected once, then
  remembered.
- **During a hard fling the overlay hides itself.** A patch positioned from a
  scan taken 20 ms ago is ~40 px out of place at fling speed, which reads as
  smeared double text. Below roughly 1200 px/s — any deliberate scroll — patches
  track normally.
- **The button never animates.** A downloading language pack shows as an amber
  ring rather than a spinner; a spinner would invalidate itself every frame, on
  top of the app whose smoothness is the whole point.
- **Only phone ABIs are packaged.** ML Kit ships native engines for four
  architectures; dropping the two emulator-only ones halves the APK.

## Fitting a translation into someone else's bubble

A translation is almost never the same length as its source, which is the one
genuinely awkward part of replacing text in place. The replacement's size is
binary-searched down until the wrapped layout fits the original rectangle, which
keeps line breaks landing close to where the covered text had them. Below a
readability floor that stops, and the patch grows downward into the bubble's
padding instead — capped, so it can never swallow the message below it.

Incoming and outgoing bubbles are different colours and a patch has to be opaque,
so each one is painted to match. Which side a bubble is on is decided by which
screen edge it is pinned to, not by its centre: a long incoming message wraps
across most of the width, so its centre sits right of centre and a midpoint test
would call it outgoing.

## If nothing gets translated

1. Turn on **Diagnostic mode** in the app. Instead of translating, it outlines
   every text node it can see — red for incoming, blue for outgoing.
   - **Boxes around the messages** → the text is being found, and the problem is
     in the region filter. Turn on **Scan the whole screen**.
   - **No boxes at all** → WhatsApp is not exposing that text, and the
     accessibility approach cannot work on this build.
2. Use **Share diagnostic dump** to export exactly what the service saw: every
   text node with its class name, view id, bounds, and content. That file is what
   the node-matching heuristics get tuned against.

Also worth checking: the button's ring is amber while a language pack downloads,
and a message stays untranslated until its pack has landed.

## Settings

| Setting | What it does |
| --- | --- |
| Translate into | Target language. Defaults to your phone's. Messages already in it are left untouched. |
| Language packs | Shows what is on device; downloads or deletes a pack ahead of time. |
| Download packs on Wi-Fi only | Off by default, so a language met on mobile data still works. |
| Bubble colours | Patches must be opaque, so these have to match WhatsApp's own bubbles. Defaults to following the system theme. |
| Text size nudge | 75–125% multiplier, for when the fitted size lands slightly off against WhatsApp's. |
| Scan the whole screen | Bypasses conversation-list detection and translates every string on screen. |
| Diagnostic mode | Outlines text nodes instead of translating, and records a dump. |

## Privacy

Message text never leaves the phone. The only network traffic the app makes is
downloading ML Kit language packs from Google, and that carries no message
content — just which language you need.

The accessibility service is not restricted to WhatsApp in its config, and that
is deliberate: restricting it would stop it from ever learning that WhatsApp went
to the *background*, which is when the button and every patch have to disappear.
In code, the very first check in `onAccessibilityEvent` rejects any package that
is not WhatsApp, before any node tree is fetched. No text from any other app is
read, stored, or transmitted. The only file written is the diagnostic dump, and
only when you explicitly turn diagnostics on.

The compose box is excluded from scanning, so what you are typing is never read
or covered.

## Build locally

```bash
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 and the Android SDK with platform 35.

## Layout

| File | Role |
| --- | --- |
| `LingoService.kt` | Accessibility service; owns the windows, pacing and scan loop |
| `TranslationEngine.kt` | On-device language detection and translation, and the caches that keep it off the scan path |
| `NodeScanner.kt` | Walks the view hierarchy, finds message text and its bounds |
| `OverlayRenderView.kt` | Draws the patches; holds the layout cache and the fitting logic |
| `FloatingToggleView.kt` | The draggable globe button |
| `LanguagePacks.kt` | ML Kit model store, for the settings screen |
| `MainActivity.kt` | Setup steps, settings, diagnostic export |
| `Prefs.kt` | Settings storage and bubble palette resolution |
