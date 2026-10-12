# SnapFind AI — Android Client

An Android app that finds you in a batch of event photos: pick a selfie and a ZIP of event photos, and the app matches faces **entirely on-device** — no upload, no server call.

**Full architecture walkthrough, layer-by-layer explanation, and code snippets:** [DEEP_DIVE.md](DEEP_DIVE.md)

Backend lives in [`../Face_recognition/`](../Face_recognition/) — no longer called by the app's active flow, but the on-device SDK (`:facesdk`) was built and validated against it across three separate phases before this app used it. Full story in DEEP_DIVE.md.

---

## User flow

0. First launch only: a splash screen, then Onboarding downloads the ~16MB face-matching models with a real progress bar (skipped on every launch after)
1. Pick a selfie + a ZIP of event photos
2. Tap "Find My Photos" — reads each photo straight out of the archive and matches on-device, cancellable while it runs. The job runs in a foreground-service worker, so **leaving the app doesn't kill it**: progress continues in a notification, and a job that finishes while the app is closed delivers its result there. If the OS kills the process anyway, the job **resumes from where it stopped** rather than starting the event folder again
3. Results screen shows every matched photo in a grid. One download control per mode: `Download N` in the app bar for everything outstanding, a `Download` action in the selection bar after a long-press, and download/remove in the full-screen viewer for a single photo. Tiles themselves show only status — a tick once a photo is in your gallery
4. Downloads are idempotent — re-downloading never creates a second copy, and the tick is re-checked against your gallery on every return to the screen, so deleting a photo in Photos clears it
5. Results survive closing the app, and every past job stays reachable as a Recent Jobs card on the Upload screen. Long-press a card to select, then download or delete several jobs at once — the same gesture and the same app bar the results grid uses

## Architecture

Clean Architecture + MVVM, three layers, each only talks to the one below it:

```
Presentation (Compose, ViewModel)
      ↓
Domain (use case, repository interface)
      ↓
Data (repository impl, wraps :facesdk's FaceMatchEngine)
```

The use case (`FindFacesInPhotosUseCase`) owns the actual sequence: read the job's checkpoint → prepare the selfie → list the archive's photos in a fixed order → score the ones not yet scored, writing out each match as it is found → persist → clean up. It is safe to call repeatedly for the same job, and every call either advances it or finishes it. It owns its dispatchers too, rather than trusting the caller's: file work on `Dispatchers.IO`, matching on `Dispatchers.Default`. `:facesdk` is a standalone Gradle module (no Compose/Hilt/Retrofit deps) with its own public API — see DEEP_DIVE.md for its facade + primitives shape.

A shared design layer sits under the screens: `ui/theme/` holds spacing, dimension, shape, motion and colour tokens (a dp literal in a screen is treated as a smell), and `presentation/components/` holds one definition each of the pill button, photo tile, status badge, selection indicator and wavy progress ring — so a saved tick and a selected tick can't drift into different shapes.

The **Clay Wave** palette is contrast-verified: every `on*`/container pairing in both light and dark meets WCAG 2.1 (4.5:1 text, 3:1 icons), with the measured ratio written beside each value in `Color.kt`. All ~29 Material 3 colour roles are mapped — an unmapped role silently falls back to Material's lavender baseline rather than to anything in this palette, which is what used to make the delete-confirmation dialog render lavender. Every control is then verified against the roles it actually consumes, so a legible palette can't be undone by a component asking for the wrong token: the tonal button and the progress track sit on `secondaryContainer` rather than the near-invisible `surfaceVariant`, accent *text* and borders use the accent's ink form rather than its fill, and disabled states are stated explicitly instead of inheriting Material's cold-grey composite. See [DEEP_DIVE.md](DEEP_DIVE.md#the-colour-pass--status-light-theme-now-meets-wcag-aa-all-material-roles-mapped) for both passes.

Three rules govern state in the presentation layer, each written after a bug that came from breaking it. **Navigation is an event, never a state** — a `Channel` the screen consumes once, because a state saying "we are done" stays true after it has been acted on and re-fires for the next observer. **Every suspending call in a ViewModel states what it does when it fails** — these all reach Room, the filesystem or MediaStore, and an unhandled throw in `viewModelScope` is a process death rather than an error message; "keep what's on screen", "report and change nothing" and "close the screen" are each chosen per call site, not defaulted. **An exception's own text never reaches the user** — only a `UserFacingException`'s message does, and everything else is logged behind a sentence written for a person. See [DEEP_DIVE.md](DEEP_DIVE.md#the-state-audit--status-ten-findings-fixed-ui-layer-still-untested).

Selection and status are carried by shape and glyph, not colour alone: a saved photo shows a sage `DownloadDone` disc, a selected one a coral tick, and a selected tile is scaled *into* the page rather than fenced off with a border — sage and coral are nearly identical in luminance, so neither state depends on telling two hues apart.

## Tech stack

Kotlin · Jetpack Compose (Material 3, expressive progress indicators) · Hilt (DI, incl. `hilt-work`) · WorkManager + foreground service (long-running jobs that outlive the screen) · ONNX Runtime (on-device inference) · Coroutines + StateFlow · Coil (image loading) · Room (job history, with committed schema exports and no destructive fallback) · MediaStore (gallery save + saved-state reconciliation) · core-splashscreen (first-run splash) · Clean Architecture · Retrofit (dormant server path, kept not deleted)

## Run it

1. Open in Android Studio, let Gradle sync (pulls in `:app` and `:facesdk`)
2. Run the app — no backend required for face matching. Release builds show a one-time Onboarding screen that downloads the ~16MB of models with a real progress bar, keeping the shipped APK small — see DEEP_DIVE.md.

> **Known gap:** debug builds *ship* the model assets (`facesdk/src/debug/assets/models/`), but the active repository always loads via `ModelDownloader`, so debug downloads them too and needs network on first run. The asset-loading factories exist in `:facesdk`; nothing wires them up. Either wire them or drop the claim — not yet decided.

## Project structure

```
app/src/main/java/com/example/snapfindai/
├── di/            # Hilt modules
├── data/          # repository implementations (active: on-device, Room, MediaStore; dormant: Retrofit)
├── domain/        # repository interfaces, domain models, use cases
├── presentation/
│   ├── components/  # shared UI primitives (button, photo card, status badge, progress ring)
│   ├── screens/     # onboarding, upload, results, full-screen viewer
│   └── util/        # Compose-level helpers (storage-permission gate)
├── ui/theme/      # design tokens: color, type, spacing, dimens, shapes
└── utils/

facesdk/           # standalone on-device face-matching SDK, sibling module
```

### Storage at a glance

| Where | What | Lifetime |
|---|---|---|
| `filesDir/facesdk_models/` | the two ONNX models (~16MB) | downloaded once, kept |
| `filesDir/saved_matches/<jobId>/` | matched photos, one folder per job | until the job's matches are removed, or the job is deleted |
| `filesDir/job_work/<requestId>/` | scratch for one run: its selfie, its ZIP, and the photos out of it that matched | the whole directory is deleted when that run ends; kept while the run can still resume; orphans swept at process start |
| Room `snapfind.db` | job + match metadata only, never image bytes, plus how far a running job got | until app data is cleared |
| `Pictures/SnapFindAI/` | photos the user downloaded | survives uninstall — these are theirs |

The archive is never unpacked: photos are read out of it as they are scored, and only the ones that match are written at all. A 500MB folder therefore costs one copy of the archive rather than that plus a second full copy on disk.

Recent Jobs shows the running total, and long-pressing a job deletes it. Deleting a job only ever touches the two rows above it in this table — its `saved_matches` folder and its database rows. `Pictures/SnapFindAI/` is reached by no path in that code, so photos the user already downloaded stay put; that's structural, not a precaution.

Deliberately **not** `cacheDir`: the OS empties it under storage pressure, which once deleted a job's selfie mid-run. One directory per work request, not one shared one — cancelling a request doesn't wait for its worker to stop, so a shared directory let one run delete another's files. See DEEP_DIVE.md.

**Nothing here is backed up.** `allowBackup` is off and every path above is named in the backup rules. The data is selfies, photos of people's faces, and a database of who matched whom — so "the photos never leave your phone" has to include Google's cloud backup, which the default behaviour was quietly uploading to. Nothing is lost that can't be rebuilt: models re-download, and a job can be re-run.

Full layer-by-layer explanation, why each pattern was chosen, and code walkthroughs: **[DEEP_DIVE.md](DEEP_DIVE.md)**.
