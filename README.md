# CyrFix

Redraws TikTok's Russian comments in Noto Sans, in place, on Android.

TikTok's typeface has poor Cyrillic coverage, so letters like `д`, `л`, `я` and
`з` fall back to mismatched glyphs and the text becomes unpleasant to read. The
text itself is fine — copy a broken-looking comment into any other app and it
pastes perfectly. Only the rendering is wrong.

CyrFix exploits exactly that. It reads the real comment strings out of TikTok's
view hierarchy through an accessibility service, then paints them back over the
originals in a font with proper Cyrillic.

## Why there is no screenshot or OCR here

The obvious design — capture the frame, OCR it, overlay the result — is the
wrong one, for four reasons:

1. **Google's ML Kit does not support Cyrillic.** Its on-device text
   recognition ships Latin, Chinese, Devanagari, Japanese and Korean models
   only. Russian OCR would mean bundling Tesseract or calling a cloud API.
2. **OCR would be fighting the exact thing being fixed.** The glyphs are
   malformed; that is the complaint. Feeding malformed glyphs to a recognizer
   produces malformed output.
3. **It would be slow.** Capture plus recognition per frame is the classic
   source of the stutter this app is supposed to avoid.
4. **It cannot follow a scroll.** A captured frame is a still image. The moment
   the list moves, a pinned overlay is stale.

Reading the view hierarchy avoids all four. The text is exact rather than
guessed, each string arrives with the precise screen rectangle TikTok drew it
in, and those rectangles update as the list scrolls.

## Install

Every push builds an APK and publishes it to
[Releases](../../releases). Download the `.apk` on your phone and open it —
it is signed with the standard Android debug key, so it installs directly.

Then:

1. **Enable the accessibility service.** Settings → Accessibility → Installed
   apps → CyrFix. This is normally the only permission needed.
2. **Open TikTok.** A round `Аа` button appears at the edge of the screen.
3. **Tap it** to correct the comments; tap again to stop. Drag it anywhere.

An accessibility service can post its own overlay windows, so the
"draw over other apps" permission is usually unnecessary. The app falls back to
it automatically if an OEM build rejects the accessibility overlay — that is
what the second button in the setup screen is for.

## How it stays smooth

- **Scans run on a background thread.** Fetching and walking a window's node
  tree is an IPC round trip; doing it on the main thread during a fling is what
  would cause jank.
- **Text layout is cached against text and box size, never position.**
  Scrolling changes only the `y` coordinate, so cached layouts are reused and a
  scroll costs one canvas translate rather than a re-layout.
- **Rescans are debounced to one per 60 ms**, coalescing the burst of events
  a scroll produces.
- **Strings with no Cyrillic are skipped entirely.** Nothing to fix, so nothing
  is painted.
- **During a hard fling the overlay hides itself.** A patch positioned from a
  scan taken 20 ms ago is ~40 px out of place at fling speed, which reads as
  smeared double text. Below roughly 1200 px/s — any deliberate scroll — patches
  track normally.

## If nothing gets corrected

This is the one part that could not be verified without the device, because it
depends on whether TikTok exposes its comments as accessibility nodes. Most
apps do, since screen readers depend on it.

If comments are not being corrected:

1. Turn on **Diagnostic mode** in the app. Instead of repainting, it outlines
   every text node it can see in red.
   - **Red boxes around the comments** → the text is being found, and the
     problem is in the region filter. Turn on **Scan the whole screen**.
   - **No red boxes at all** → TikTok is not exposing that text, and the
     accessibility approach cannot work on this build.
2. Use **Share diagnostic dump** to export exactly what the service saw:
   every text node with its class name, view id, bounds, and content. That file
   is what the node-matching heuristics get tuned against.

## Settings

| Setting | What it does |
| --- | --- |
| Comment sheet background | Patches must be opaque to hide the original glyphs, so this has to match TikTok's sheet. Defaults to following the system theme. |
| Text size nudge | 75–125% multiplier, for when Noto's metrics land slightly off against TikTok's. |
| Scan the whole screen | Bypasses comment-sheet detection and corrects every Cyrillic string on screen. |
| Diagnostic mode | Outlines text nodes instead of repainting, and records a dump. |

## Privacy

The service is not restricted to TikTok in its config, and that is deliberate:
restricting it would stop it from ever learning that TikTok went to the
*background*, which is when the button and every patch have to disappear. In
code, the very first check in `onAccessibilityEvent` rejects any package that is
not TikTok, before any node tree is fetched. No text from any other app is read,
stored, or transmitted. Nothing leaves the device — the only file written is the
diagnostic dump, and only when you explicitly turn diagnostics on.

## Build locally

```bash
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 and the Android SDK with platform 35.

## Layout

| File | Role |
| --- | --- |
| `CyrFixService.kt` | Accessibility service; owns the windows, throttling and scan loop |
| `NodeScanner.kt` | Walks the view hierarchy, finds comment text and its bounds |
| `OverlayRenderView.kt` | Draws the patches; holds the layout cache |
| `FloatingToggleView.kt` | The draggable Аа button |
| `MainActivity.kt` | Setup steps, settings, diagnostic export |
| `Prefs.kt` | Settings storage and backdrop colour resolution |
