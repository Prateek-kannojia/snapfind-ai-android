# SnapFind AI — Android Client

An Android app that lets you find yourself in a batch of event photos. You pick a selfie and a ZIP archive of event photos, and the app matches faces **entirely on-device** — no upload, no network call, no server in the loop.

That wasn't the original design. The app was first built against a backend (`../Face_recognition/`, its own README covers the ML pipeline and API) that uploads photos, matches server-side, and polls for results. As of 2026-09-27, matching has moved fully on-device, backed by a validated on-device SDK (`:facesdk`, its own Gradle module in this repo) built and proven against that same backend across three separate phases — detection, alignment, then end-to-end match decisions — each checked against the server's real output before moving to the next, not assumed correct. The server-calling code is still in the repo, intact, unused by the app today; see [How on-device matching actually works](#how-on-device-matching-actually-works) and [The dormant server path](#the-dormant-server-path-kept-not-deleted).

---

## The problem this solves

After large events (weddings, parties, conferences), photographers distribute massive folders of unorganized photos. Finding photos of a specific person in an archive of thousands of images is a tedious, manual process. This app automates that: pick a selfie and the event archive, get back only the photos containing your face — matched right there on the phone.

---

## What this app does (user flow)

0. **First launch only:** a brief system splash, then an Onboarding screen explaining that the ~16MB face-matching models need a one-time download, with a real progress bar. Every launch after that skips straight past it.
1. User opens the app and lands on the **Upload Screen**
2. User picks a selfie from their gallery (the face to search for)
3. User picks a ZIP file containing event photos
4. User taps **Find My Photos**
5. App reads each photo straight out of the archive and runs on-device face detection + matching against it
6. App navigates to the **Results Screen** showing matched photos in a grid
7. User can scroll through, tap any photo to open it full-screen and swipe between matches, long-press to enter selection mode (batch remove or batch download), or download all/a single photo straight to the system gallery
8. Closing and reopening the app still shows the last job's results — nothing is lost on restart

No upload, no polling loop — steps 5-6 happen on the phone's CPU, typically in a few seconds for a handful of photos.

---

## How on-device matching actually works

`FindFacesInPhotosUseCase` (domain layer) owns the sequence:

```
1. ZipFile(zipFile) → entries, filtered to photos, sorted by entry name
   → read from the archive's central directory, so the list is known at
     once and any entry can be read directly. Sorted because the scored
     cursor is a count into this order (see Resuming, below)

2. FaceMatchRepository.matchPhotos(selfie, eventPhotos, threshold)
   → the actual matching, delegated to OnDeviceFaceMatchRepositoryImpl
     (data layer), which wraps :facesdk's FaceMatchEngine. Each photo's
     bytes are read when its turn comes and dropped straight after

3. A photo that matches is written out, and nothing else ever is
   → only what the Results screen will display touches the disk at all
```

Inside `OnDeviceFaceMatchRepositoryImpl`, photos are **not** batch-decoded into memory as a list of Bitmaps — the SDK's `embedSelfie()`/`scoreEventPhoto()` primitives are called one photo at a time, each Bitmap recycled immediately after scoring. A batch of dozens of full-resolution phone photos held in memory simultaneously risks OOM; a job's worth of on-device history has never needed more than one or two decoded photos alive at once with this approach.

`FaceMatchRepository` is a domain-layer interface (see `domain/repository/FaceMatchRepository.kt`) — deliberately named and shaped like `JobRepository` rather than "OnDeviceRepository", so a future server-backed implementation could satisfy the exact same contract without the use case or ViewModel changing. That's a real possibility left open, not a decision made — see [Cross-project status](../Face_recognition/DEEP_DIVE.md#cross-project-status) for the still-open server-vs-on-device routing question.

## The `:facesdk` module

A standalone Gradle module (`facesdk/`, sibling to `app/`) — no Compose, Hilt, or Retrofit dependency, so it could be published or reused independently of this app. Public API, two layers:

- **The facade**, `FaceMatchEngine` — `create(context)` loads the bundled models and returns one object with `embedSelfie()`, `scoreEventPhoto()`, and the batch convenience `matchJob()`. Mirrors the backend's `face_matcher.py` job-level rules exactly (largest face wins for the selfie, closest face decides an event photo's distance) so the same job scores the same way on-device and on the server.
- **The primitives**, independently usable: `FaceDetector` (SCRFD/`det_500m.onnx`), `FaceAligner` (insightface's `norm_crop`, ported), `FaceEmbedder` (`w600k_mbf.onnx`), `FaceMatcher` (cosine distance + threshold). Each interface lives in `facesdk/api/`; the one shipped implementation of each lives in its own package (`facesdk/detector/`, `facesdk/embedder/`) — not mixed into the interface file, so the algorithm-specific code isn't sitting next to the public contract it implements.

**Swapping models:** each interface has four constructors — `create(context)` (the bundled, validated models), `createFromAsset(context, path)` (same validated pipeline code, a different file you bundle), `createFromBytes(bytes)` (same pipeline, a model from anywhere), `createFromFile(file)` (same pipeline, loaded straight from a local file path — no buffering the whole model into memory first, which matters at ~14MB; this is what a downloaded-and-cached file uses). Swapping to a genuinely different detector/embedder architecture means implementing `FaceDetector`/`FaceEmbedder` yourself and passing it to `FaceMatchEngine.create(detector, embedder)` instead. `FaceDetectorConfig` (detection threshold, NMS threshold, input size) is a constructor parameter throughout, not a hardcoded constant.

**Getting the model bytes onto the device is a separate concern from turning them into a working detector/embedder** — see [Model distribution](#model-distribution-why-release-builds-download-instead-of-bundling) below. `ModelDownloader` (also in `facesdk`) is generic: it knows how to fetch-and-cache-and-verify a file from *any* URL, with zero knowledge of what's inside it — a third-party app built on this SDK can point it at their own model, their own URL, their own checksum, and it composes with `createFromFile()` exactly the same way ours does.

**Defensive by construction, not just by convention:**
- A wrong-shaped bring-your-own model (e.g. an embedder loaded into the detector slot) is rejected at construction with a clear `InvalidModelException`, not a cryptic crash the first time `detect()`/`embed()` runs.
- `FaceMatchEngine` doesn't expose its detector/embedder as independently closeable — `detectFaces()`/`embedFace()` pass-through methods instead — and using the engine after `close()` throws immediately rather than misbehaving quietly.
- Real ProGuard consumer rules (`consumer-rules.pro`, protecting ONNX Runtime's JNI-reflected classes) ship in the AAR automatically for any app that depends on this module and enables R8.

**How it was validated**, not assumed correct — three phases, each checked against the real backend before combining with the next:
- **Detection** — SCRFD's anchor-decode/NMS math ported line-for-line from insightface's `scrfd.py`; boxes compared photo-for-photo against the real Python detector on the same images. Root-caused and fixed a genuine bug in the process: `BitmapFactory.decodeFile()` doesn't apply EXIF orientation the way the server's `cv2.imread()` does, silently handing the detector a rotated image for ~30% of real phone photos.
- **Alignment** — the similarity-transform math (closed-form complex-number least-squares, equivalent to skimage's Umeyama solver for 5 points) verified against the reference to ~7e-5 float32 precision before any Kotlin was written.
- **End-to-end matching** — `runJobMatching()` (the validation harness, `spike/TimingSpikeRunner.kt`) reproduced the server's real-job recall exactly on 3 hand-labeled jobs, then stress-tested against 22 additional mixed selfie/event-photo combinations built from a real 227-photo trip archive: 184 event photos, **99.1% match/no-match agreement** with the server, mean distance difference 0.013.
- 38 unit tests (`facesdk/src/test/`) cover the actual math and behavior — `FaceMatcher`'s cosine distance, `FaceAligner`'s transform on planted known-answer point sets, `ScrfdPostprocess`'s anchor decode/NMS on synthetic model outputs, model-shape validation, the engine's closed-state guard, `ModelDownloader`'s cache/verify/re-download logic against local `file://` URLs — not just "it doesn't crash."

## Model distribution — why release builds download instead of bundling

The two ONNX models are ~16MB combined. Bundling them in `facesdk/src/main/assets/` (what earlier builds did) meant every install paid that cost even though most of a typical app's users never touch the feature on day one. They now live in `facesdk/src/debug/assets/models/` instead — Android only includes debug-variant assets in debug builds, so:
- **Debug builds** (what local development and `spike/TimingSpikeRunner` use) — unchanged, models present, works offline immediately, zero friction.
- **Release builds** — genuinely ship with neither file. Confirmed by unzipping a built release APK and checking for `.onnx` files directly, not just trusting the Gradle config; the APK shrank by ~15MB after the move.

`OnDeviceFaceMatchRepositoryImpl` downloads both models on first use via `ModelDownloader`, then reuses the cached copy on every job after that — never re-downloading once a valid copy exists. Each download is verified against a known SHA-256 checksum before being trusted; a mismatch (corrupted transfer, interrupted download) is discarded, never cached, and reported as an error rather than silently handing a broken model to the detector. Models are hosted as a GitHub Release asset on this repo (tag `models-v1`) — free, versioned, no backend dependency for the on-device flow to work.

This download is no longer silent — see [First-run onboarding and the splash screen](#first-run-onboarding-and-the-splash-screen) below for the dedicated screen that now handles it, before the user ever reaches Upload.

## First-run onboarding and the splash screen

Downloading ~16MB used to happen silently inside the existing "Processing" step the first time a user submitted a job — no explanation, no progress bar, easy to mistake for the app hanging. Fixed by moving the download earlier, into a dedicated first-run flow, and by adding a real system splash screen so the app never shows a blank frame while deciding which screen to open first.

**Deciding Onboarding vs. Upload happens before any UI is drawn.** `AppStartupViewModel` (`presentation/`) checks, once, whether both models are already cached via a new `ModelProvisioningRepository.areModelsReady()`. `MainActivity` installs the system splash screen (`androidx.core.splashscreen`, via `installSplashScreen()`) and keeps it on screen (`setKeepOnScreenCondition`) until that check finishes — so the very first frame the user sees is already the right one: straight to Upload if models are cached, Onboarding if not. No flash of the wrong screen, no blank frame while checking.

**`ModelProvisioningRepository`** is a small, separate domain contract — `areModelsReady(): Boolean` and `downloadModels(onProgress: (Float) -> Unit)` — deliberately not folded into `FaceMatchRepository`, since checking/fetching model files and actually running a match are different concerns. Its impl (`ModelProvisioningRepositoryImpl`) is a thin wrapper around `ModelDownloader`, the same SDK utility `OnDeviceFaceMatchRepositoryImpl` already used — `ModelDownloader` gained one new capability for this, `isCached()`, which answers "is a valid, checksum-verified copy already here?" without downloading anything (mirrors the existing `getOrDownload()`'s `Context` / `destDir` overload split). `downloadModels()` reports combined progress across both model files as a single 0f..1f value, weighting each file's own byte-progress by its position (file 1 of 2, file 2 of 2) rather than needing exact combined byte totals up front.

**Both repository impls now read the same URL/checksum config**, `data/ModelConfig.kt` — pulled out of `OnDeviceFaceMatchRepositoryImpl` (where these constants originally lived) into its own file so the two repositories can never drift to different URLs or checksums for the same models.

**`OnboardingScreen`** (`presentation/screens/onboarding/`) shows the explanation + a "Get Started" button, then a `LinearProgressIndicator` bound to `OnboardingViewModel`'s download progress, then navigates to Upload on completion via a one-shot state (`OnboardingUiState.Complete`) rather than a `StateFlow` value that could re-fire navigation on recomposition. A download failure shows the error with a "Retry" button rather than leaving the user stuck.

**Pause and resume are real, which took a change one layer down.** The screen had a Pause button whose handler was `onPause = {}` — its own content description said "Pause (not yet available)". The reason it was never wired is that pausing is meaningless if the bytes don't survive it: `ModelDownloader` deleted its partial file on any failure or cancellation, so "resume" would have re-downloaded 16MB from zero and the control would have been a lie in a second way.

So the downloader learned to continue. A partial `<name>.download` file is now **kept** rather than deleted, and the next attempt sends `Range: bytes=<what's on disk>-` and appends to it. Three details carry the weight:

- **Only a `206 Partial Content` response is treated as a resume.** A server is free to ignore `Range` and send the whole body with `200 OK`; appending to a prefix that may not line up would build a file that fails its checksum with nothing to point at. A `200` truncates and starts over — slower, still correct.
- **The bytes are `fsync`'d at the end of the transfer**, because they're what a later resume will trust. Buffered-but-unwritten bytes that a process death took with it would leave the file's length disagreeing with its contents, and `Range` is computed from that length.
- **Pause keeps the partial; Cancel discards it.** `pauseDownload()` stops the coroutine and parks the progress in `OnboardingUiState.Paused`. `cancelDownload()` is the user saying they don't want it, so it `cancelAndJoin()`s first — `cancel()` only *asks*, and deleting the file out from under a write that hasn't stopped yet lets the writer recreate it — then calls `discardPartialDownloads()`.

Resume is the *same* call as start (`startDownload()`), because what makes it a resume lives in the file on disk, not in anything the ViewModel remembers. The one piece of state it does keep is where the bar was: a resumed download starts the ring at the paused percentage rather than 0%, since the first progress callback can be an entire file away (an already-finished model reports nothing at all) and a bar that jumps backwards to 0% reads as "it threw my download away".

Tested against a stub HTTP server rather than a mock: the existing tests download from `file://` URLs, which have no concept of a byte range, so the resume path needed something that could actually honour one and then say how many bytes it served. The test stops a download part-way, asserts the partial survives, resumes, and asserts the server sent **exactly** the missing bytes — a resume that quietly re-fetched everything would still produce a correct file, so the byte count is the only thing that can tell the difference.

**Why `OnDeviceFaceMatchRepositoryImpl` still calls `ModelDownloader.getOrDownload()` itself, not just relying on Onboarding having already run it:** it's a safety net, not redundant work in the common case — `getOrDownload()` is a no-op cache hit once Onboarding has already fetched both files, but this keeps the matching path correct even if app storage were cleared without a fresh install (Onboarding wouldn't re-run, since that only happens once per install in the current flow).

## Results persistence, gallery save, and the full-screen viewer

Four related gaps existed after on-device matching first shipped: results didn't survive an app restart, matched photos lived only in `cacheDir` (OS-clearable at any time), there was no way to get a match into the phone's actual photo gallery, and tapping a photo did nothing. All four are now solved together, because they share one root cause: nothing about a completed job outlived the in-memory `UploadUiState`.

**Persistence — Room.** `SnapFindDatabase` (`data/local/`) has two tables: `jobs` (one row per completed match job — timestamp, threshold) and `matched_photos` (foreign-keyed to a job, `ON DELETE CASCADE`, storing each match's photo path, distance, and an optional `savedAt` gallery-save timestamp). `JobHistoryRepositoryImpl.saveJob()` is called from `FindFacesInPhotosUseCase` right after matching completes: every job gets its own row (earlier versions kept only the latest — see [Job history](#job-history-and-the-shared-design-system) below), and each matched photo is **moved** — not copied — out of the run's working directory into a stable `filesDir/saved_matches/<jobId>/` before the DB rows are written. The relocated list goes back to the use case, so the UI displays paths that will actually still exist tomorrow.

Two details in that relocation are load-bearing rather than incidental. A job's photos are flattened into one directory, so **filenames are made unique within the job** — an event ZIP organised into `day1/IMG_001.jpg` and `day2/IMG_001.jpg` would otherwise have the second silently overwrite the first and leave two DB rows pointing at the same file, which then crashes the results grid on duplicate Compose keys. And the job directory is **cleared before use**, because deleting a job frees its `autoGenerate` id for SQLite to hand out again while nothing deletes the directory — so a new job could otherwise inherit a deleted one's photos.

**Why the DB doesn't duplicate the download.** A fair question this raised: since matched photos are already saved locally (for persistence) before the user ever taps "download," doesn't "save to gallery" just create a second copy? Yes, deliberately — they're for different things. The `filesDir/saved_matches/` copy is the app's own working copy (what the grid displays, what survives restart); the gallery copy is a MediaStore entry the user's Photos app and other apps can see, which Android has no API to alias back to an arbitrary app-private file. `markSavedToGallery()` just stamps `savedAt` on the existing row afterward — no new DB rows, no duplicate tracking, just a flag on what's already there.

**Gallery save — two code paths for two storage models.** `PhotoGalleryRepository.saveToGallery()` (data: `MediaStoreGalleryRepositoryImpl`) branches on API level: 29+ inserts via `MediaStore` with `RELATIVE_PATH`/`IS_PENDING` and needs no permission at all (scoped storage); below 29 it writes directly into the public `Pictures/` directory and calls `MediaScannerConnection.scanFile()` so the new file actually shows up in gallery apps, gated behind the `WRITE_EXTERNAL_STORAGE` permission (declared `maxSdkVersion="28"` in the manifest — simply not requested at all on newer OS versions). `SaveMatchedPhotoUseCase` wraps one save + the `markSavedToGallery()` DB update as a single `Result`.

**`ResultsScreen` — its own `ResultsViewModel`, not shared state.** Originally `ResultsScreen` had no ViewModel of its own and just read `UploadViewModel`'s `Success` state — fine for a static grid, wrong once the screen needed its own state (selection set, save status per photo, batch progress) that has nothing to do with the upload flow. `ResultsViewModel` now owns all of that independently, loading the same last job via `GetLastJobUseCase`. The one piece of cross-screen coordination left — resetting `UploadViewModel` back to `Idle` when the user navigates back from Results, so its `Success`-triggered navigation effect doesn't immediately fire again — lives in `MainActivity`'s nav graph, not inside either screen, since neither screen should need to know the other's ViewModel exists.

`ResultsUiState.Loaded` carries `matches`, `selectionMode`, `selectedPhotos: Set<File>`, and an optional `batchProgress` (completed/total, drives a `LinearProgressIndicator` during multi-photo saves). Long-press enters selection mode; tap toggles a selection when already in that mode, or opens the full-screen viewer otherwise (`combinedClickable`). Selected photos can be removed from the results set (`RemoveMatchedPhotosUseCase` — deletes the `filesDir` copy and DB row; this curates what's in the app, it does not touch anything already saved to the gallery) or downloaded as a batch.

**Save feedback — snackbar now, notification for later.** A batch save (`saveSelected()`/`saveAll()`) reports progress live via `batchProgress` and, on completion, emits a one-shot `ResultsEvent.BatchSaveCompleted` through a `MutableSharedFlow` (deliberately separate from the `StateFlow` UI state, which would otherwise re-fire the snackbar/notification on every recomposition or config change). `ResultsScreen` collects that event once to show a `Snackbar` and call `DownloadNotificationHelper.showCompleted()`, which posts a real system notification (channel created once in `SnapFindApplication.onCreate()`) whose tap action (`PendingIntent` + `ACTION_VIEW` on the last saved photo's `Uri`) opens the system Gallery/Photos app directly to that image — chosen over building a custom in-app gallery viewer, since the OS one already does that job well. `POST_NOTIFICATIONS` (API 33+ only) is requested **independently of saving**, once per screen visit and only when not already granted — see [Downloads](#downloads-one-control-per-scope-and-a-tick-that-tells-the-truth) below for why it was worth untangling the two.

**Full-screen viewer — `HorizontalPager`, no new dependency.** `PhotoViewerScreen` (`presentation/screens/viewer/`) uses Compose Foundation's `HorizontalPager` (already available via the existing Compose BOM) to swipe between every match in the job. It has its own `PhotoViewerViewModel` — independent of `ResultsViewModel`, loading the job via `GetJobUseCase` — and owns the per-photo download and remove actions (see [Downloads](#downloads-one-control-per-scope-and-a-tick-that-tells-the-truth) below), since those belong on the screen showing the photo they apply to.

**Navigation carries the photo's identity, not its position.** The route is `photo_viewer/{jobId}?photo={path}` and the viewer resolves that to an index against the list it loaded itself. It used to pass the grid index — which only lands on the right photo for as long as two independently-fetched lists happen to be built identically. `getMatchedPhotosForJob` now also has an explicit `ORDER BY id` for the same reason: SQLite makes no ordering promise without one, and two callers read the same job separately.

## Job history and the shared design system

**Job history.** Every completed job already had its own row; this surfaces them. `GetJobHistoryUseCase` returns `JobSummary` — id, timestamp, match count, and one preview photo — deliberately *not* the full match list, so drawing the Recent Jobs grid doesn't load every job's photos. Tapping a card navigates to `results?jobId=N`, and `ResultsViewModel` loads that job via `GetJobUseCase`; with no id it falls back to `GetLastJobUseCase`, which is the just-finished-a-job flow. The grid is a plain wrapping `Column` of `Row`s rather than a `LazyVerticalGrid`, because a Lazy grid needs its own bounded height to coexist with the screen's outer `verticalScroll` — which would re-introduce the "reserve space whether or not there's content" problem the scrolling layout exists to avoid.

**Deleting a job, and showing what one costs.** A job holds a full-resolution copy of every photo it matched, for as long as the app is installed, and nothing said so anywhere — the first place a user would notice was the system settings screen. Recent Jobs now shows the total next to its heading.

**The grid selects like the results grid, because it is the same gesture.** Long-press starts selecting, tapping toggles, back clears it, and the app bar swaps for one reading "*n* selected" with Download and Delete — the selection bar *replacing* the default bar rather than adding to it, so two sets of actions are never on screen together. Learning it once covers both screens; a long-press that meant "delete this" on one screen and "start selecting" on the other would be the same gesture with two meanings.

The batch-save loop behind Download is shared rather than copied. `SaveMatchedPhotosUseCase` owns what is the same wherever it runs — one photo at a time, a failure that never ends the batch, the saved/skipped/failed tally, and the rule that a raw exception is logged and replaced — and reports per-photo through `PhotoSaveEvent`. What belongs to a screen stays with the screen: the results grid turns those events into a tick per tile, the history grid ignores everything but the progress count. A second copy of that loop would have been a second place to fix the next bug in it, and two places for the same failure to end up worded differently.

Deleting several jobs is one confirmation, not one per job, and it states the combined count and size — plus the sentence that makes it tappable at all: photos already in the gallery stay there.

The confirmation states the whole trade, including the part that matters most: **photos already saved to the gallery stay there.** That isn't a promise the delete has to be careful to keep — it's structural. `deleteJob` touches exactly two things: the job's subdirectory under `filesDir/saved_matches/`, and its row (whose `matched_photos` go with it via `CASCADE`). The gallery is MediaStore under `Pictures`, which this code addresses by no path at all, so a delete *cannot* reach a photo the user chose to keep. The row is deleted last, so a failure leaves a job that still lists and can be deleted again rather than a row pointing at files that are gone.

`JobSummary` gained `sizeBytes`, summed from the files the summary query already lists — one `stat` per file on top of a read that was happening anyway. The same change moved `deleteEmptyJobs` **off** that read path: listing the history used to mutate the database, which is surprising on its own and meant the cleanup only ran when something happened to be looking. It now runs in `removeMatches`, where a job can actually become empty.

**The design system.** `ui/theme/` now carries the constants the screens were otherwise hardcoding: `SnapFindSpacing` for layout rhythm, `SnapFindDimens` for the few dimensions it's legitimate to fix (touch-target floors, badge sizes, upper bounds), `ClayShapes`/`ClayPillShape` for corner treatment, plus a night palette and the bundled font family. The rule being enforced is that a dp literal in a screen is a smell — either it's a visual primitive and belongs in `SnapFindDimens`, or it's layout and should come from constraints.

`presentation/components/` holds one definition each for the things every screen had been drawing its own version of:

- **`SnapFindButton`** — every pill button, with content-driven sizing: `heightIn(min = …)` respects the accessibility floor without forcing every button to exactly that height, `widthIn(max = …)` stops a full-width button stretching edge-to-edge on a tablet while still following its container on a phone.
- **`SnapFindPhotoCard`** — the one photo-tile shape, using `aspectRatio` rather than a fixed height so a tile follows its grid column's actual width on any screen. It carries a nullable `selected` for TalkBack semantics only; selection has no visual weight of its own here.
- **`SnapFindStatusBadge`** and **`SnapFindSelectionIndicator`** — the badge owns the one icon-overlay look (size, accent tint, no filled disc), and the selection indicator *is* that badge with the selection icons filled in. That nesting is the point: a saved tick and a selected tick are the same mark in the same colour because there is only one definition of it, not two that happen to agree.
- **`WavyProgressRing`** — the single waiting motif, a thin wrapper over Material 3's `CircularWavyProgressIndicator`. `progress = null` means indeterminate, `label = null` draws the ring alone. Every indicator in the app is now this or its linear sibling; nothing uses a plain `CircularProgressIndicator` any more, so "the app is working" looks the same everywhere.

## Downloads: one control per scope, and a tick that tells the truth

Three ways to download a photo were visible at once: a "Download All" button in the app bar, a download button on *every* tile, and a download action in the selection bar. That's one verb offered at three scopes, two of them competing for the same screen space.

**One control per mode.** Tiles now carry *status* only — saved, failed, or nothing. The single-photo download moved to the viewer, where "download" unambiguously means the photo you're looking at. Bulk lives in the app bar, subsets live in the selection bar, and because selection mode *replaces* the default bar rather than adding to it, no two download controls are ever on screen together. The one tappable badge left on a tile is retry, which is the only per-photo action with no bulk equivalent to fall back on.

**Every scope is explicit.** Nothing filters by save status any more: `saveAll()` saves all, `saveSelected()` saves exactly what was selected, even photos already in the gallery. That used to be unsafe, which is why the filters existed — and the fix was underneath, not in the UI.

**Idempotent saving is what makes that safe.** The gallery filename used to be built from `System.currentTimeMillis()`, so every save wrote a new file: downloading 24 matches twice left **48 copies** in the user's gallery. `displayNameFor()` now derives the name from the source photo's path, so the same photo always maps to the same gallery entry, and `saveToGallery` checks for it first — reporting `alreadyExisted` instead of writing a duplicate. Re-tapping download is a no-op, which is exactly what a user who isn't sure whether it worked will do.

**The stable name doubles as a join key.** Because a photo's gallery filename is derivable, `ReconcileSavedPhotosUseCase` can answer "is this match still in the user's gallery?" for an entire grid in **one** MediaStore query, and correct the stored `savedAt` flags in both directions: a photo deleted in Google Photos loses its tick, and a photo present but unflagged (app data cleared, gallery kept) gets it back. It runs on screen resume, which is the moment that matters — deleting from the gallery means leaving this app, so returning to it is exactly when a stale tick would be on screen.

So the tick stops meaning *"you tapped download at some point"* and starts meaning *"this is in your gallery right now"* — which is what makes it trustworthy enough to put a count on the bulk button. `Download 17` states its own scope before the tap, and at zero it says `All saved` and disables itself, rather than remaining a live button that silently does nothing (which is precisely what it was once everything had been saved: `saveBatch` early-returns on an empty list, so the old button emitted no snackbar, no notification, nothing at all).

`savedDisplayNames()` returns `Set<String>?` where **null means "couldn't read the gallery"** — distinct from an empty set — so a missing permission on API ≤ 28 leaves every flag untouched instead of wiping every tick at once.

**Permissions untangled.** Saving used to launch the `POST_NOTIFICATIONS` request on *every* save action, re-asking whether or not it had already been answered, with the notification request woven into the save trigger. A photo saves whether or not the app can post a notification about it, so the two are now independent: `rememberGallerySaveGate()` is the single place that knows saving needs `WRITE_EXTERNAL_STORAGE` below API 29 and nothing above it (shared by the results grid and the viewer), and the notification permission is requested once per screen visit, only if not already granted.

## Running a real event folder on device: threading, storage, and cancel

A 500MB, 200+ photo folder surfaced two bugs that no amount of code reading had.

**The app froze for the entire job.** `viewModelScope` is `Dispatchers.Main.immediate`, and nothing below `submitJob` switched dispatcher — so unzipping the folder, every full-resolution decode and EXIF rotation, hundreds of file deletes, and the relocation of matched photos *all ran on the UI thread*. The irony is that `:facesdk` was already careful, hopping to `Dispatchers.Default` inside `detect` and `embed`; it was the decoding around those calls that never moved. Matching now owns `Dispatchers.Default` (it's CPU- and allocation-bound: a 12MP photo is a 48MB `ARGB_8888` bitmap, and the rotation copy briefly holds a second one), and the file work owns `Dispatchers.IO`.

**The job died partway through with `Could not decode temp_selfie.jpg`.** The selfie, the ZIP, and (at the time) the extracted folder all lived in `cacheDir` — which Android empties under storage pressure, at any time, including mid-operation. Extracting gigabytes *into* cacheDir is itself enough to trigger that, and what it deleted was the selfie we were about to read. `JobHistoryRepositoryImpl` had documented this exact rule for saved matches since it was written; the upload path simply didn't follow it. Working files moved to `filesDir/job_work/`, with explicit cleanup in a `finally` that removes the whole of a run's directory — which a failed job used to leave behind permanently.

The trade is that nothing clears `filesDir` for us, so cleanup is deliberate in two places: the `finally` (which also runs on cancellation) handles every normal and failed path, and a sweep at process start reclaims whatever a process killed mid-job left behind. Where that sweep runs, and why it is *not* at the start of the next job, is in [Running the job outside the app](#running-the-job-outside-the-app-workmanager-and-a-foreground-service) below.

**Failure got cheap, and the selfie is read once.** `prepareSelfie` runs *before* any scoring, so an unusable selfie costs a second rather than minutes of work — and it hands back the result instead of discarding it. `matchPhotos` takes that `PreparedSelfie` rather than a `File`, which means the selfie is decoded and embedded exactly once per job **and** the check cannot be skipped by accident: there is no other way to obtain the argument. `PreparedSelfie` is an empty interface in `domain/`, with the implementation's embedding-holding class private to the data layer, so a facesdk type still never crosses the boundary.

**Cancel.** A five-minute job committed by a single tap needs a way out. `UploadViewModel` holds the `Job` and `cancelJob()` cancels it and returns to the picker. Cancellation is cooperative, so `matchPhotos` calls `ensureActive()` before each photo's read and decode — the most expensive step in the loop — rather than letting the next suspension point handle it. Progress callbacks are also ignored unless the state is still `Processing`, since a cancel sets `Idle` immediately and an in-flight callback would otherwise flip the screen back.

## Running the job outside the app: WorkManager and a foreground service

A five-minute job in `viewModelScope` is a five-minute job tied to the Activity that started it. Android ranks processes and kills from the bottom when it needs memory, and a coroutine has no standing in that ranking:

| Priority | | Killed |
|---|---|---|
| 1 | foreground, visible activity | last |
| 2 | visible, not focused | |
| 3 | **running a foreground service** | |
| 4 | cached (user left the app) | **first** |

Leaving the app drops the process to row 4. And a matching job is the worst possible background citizen: minutes of saturated CPU plus ~96MB allocation spikes per photo, which both lengthens the window and pressures the very memory that triggers the killing. On a device aggressive enough to kill YouTube, being killed was the expected outcome, not the exception — and the job vanished with no error and no trace.

**Two mechanisms, fixing two different things.** A foreground service moves the process from row 4 to row 3, paid for with a non-dismissable notification — visibility in exchange for survival. WorkManager, separately, makes the *request* durable: it records "this work should happen" in its own database, so if the process dies anyway it reschedules on the next app start or after a reboot. One prevents death; the other recovers from it.

`MatchPhotosWorker` is a `CoroutineWorker` built through `HiltWorkerFactory`, which is the only reason it can take the same injected use cases every other layer uses instead of reaching for a service locator. That requires removing WorkManager's automatic initializer in the manifest, or the default factory wins before ours is ever read.

**The UI became a view of the job rather than its owner.** `WorkManager` is now the source of truth for "is a job running and how far along", and `UploadViewModel` maps `WorkInfo` into `UploadUiState`. So leaving the screen tears down the ViewModel and the flow collection while the worker carries on; returning builds a new ViewModel that subscribes to the same unique work and draws the right screen. Nothing is handed over, because nothing was owned.

The side effect is the better feature: **a job can now finish while the app is closed**, and the completion notification is how the result arrives.

Two details that are easy to get wrong. Progress is bridged through a `MutableStateFlow` because the use case reports via a plain callback while `setProgress` and `setForeground` both suspend; notification updates fire only on whole-percent changes, capping them at 100 for a job of any size. And a finished `WorkInfo` is replayed to every new observer until pruned, so terminal states are handled once per work id and then pruned — otherwise reopening the app would re-navigate to Results for a job dealt with days ago.

### One job per request, not per attempt

The job row is created **inside the worker**, keyed by the work request id.

The obvious design is to record the job before enqueuing, and it was wrong twice over. Creating it per *attempt* meant every process kill produced a second row for the same job while the first was orphaned — and WorkManager would never resume that orphan, because from its point of view the work had succeeded. And creating it in the ViewModel needed a three-step sequence (record the job, build the request, link them, enqueue) with a crash gap at every step, plus an enqueue failure that would strand the UI in `Processing` forever.

Keyed on the request, retries converge: an attempt killed mid-run is rescheduled with the same id, finds the same job, and continues it. An enqueue that never happens leaves no row at all.

A worker also checks whether its job still needs work before starting, because an attempt can finish everything and die before reporting success — and redoing the job would then wipe and re-save photos already on disk.

**Completion is one transaction.** As two writes there was a window where a process death left photos recorded against a still-running job, and the retry inserted a *second* set of rows for the same paths. That's duplicate keys in the results grid, which throws, and keeps throwing until app data is cleared. A unique `(jobId, photoPath)` index backs it up so the corruption can't be represented at all.

### A directory per request

Every run owns `job_work/<requestId>/` — its selfie, its ZIP, and the photos out of that archive that matched — derived on both sides from the request id, so no path is ever passed around and a worker cannot be handed one belonging to another run. The worker removes that directory when the job ends, since it is the thing that created it; it deliberately does *not* do so in a `finally`, because a process killed mid-job must leave it exactly where it is for the next attempt to resume from.

Runs previously shared `job_work/temp_selfie.jpg` and `temp_events.zip`, which was a real race rather than a theoretical one. **Cancelling a work request only records the cancellation; it does not wait for the worker to stop.** So a new run could overwrite a still-unwinding worker's inputs, and that worker's `finally` would then delete the *new* run's files. The window is widest exactly where it's least obvious: relocating hundreds of matched photos has no cancellation checks at all, so it can run for tens of seconds after a cancel.

### Cleaning up what the process never got to

`AbandonedJobSweeper` runs at **process start**, not at the start of the next job. Cleaning up on the way *into* a job put a potentially multi-gigabyte delete on the critical path of the user's tap, and conflated "set up my run" with "recover from an abandoned one".

The decision per leftover is **not a timeout**. "How old is this?" was only ever a proxy for a question that can be answered directly: *is the work request that owns this still live?* If it is, something is going to resume it and its files must be left strictly alone. If it isn't, nothing will ever pick it up.

It's driven by directories rather than database rows, which matters twice. A run cancelled before its worker ever started has a directory but no row, and a row-driven sweep would never see it. And since a new run's request is live by definition, the sweep **structurally cannot touch it** — an earlier version took one liveness snapshot and then emptied the shared directory wholesale, which could destroy the inputs of a job started while it was running.

Tested against a **real** WorkManager on an in-memory database (`work-testing`), not a stub. Its entire decision is a liveness question put to WorkManager, so a stub would answer the one thing under test and the test would be checking the stub. A request held `ENQUEUED` by an initial delay stands in for "something is still coming"; one run to completion by a synchronous executor stands in for "nothing is". Seven cases, and they validate each other: three assert a leftover *is* deleted, so if the sweep were a no-op those would fail rather than the survival cases passing for the wrong reason.

### Resuming instead of restarting

WorkManager's guarantee is that the work *eventually completes*. It is not that an attempt is resumable: a killed worker's next attempt re-enters `doWork()` at line one. For a job measured in minutes on a device that kills aggressively, that isn't merely slow — it can be **non-terminating**. Every attempt starts from zero, gets killed before finishing, and is rescheduled to start from zero again. The promise is unsatisfiable without progress being kept, which is what this is.

**Three pieces of state, and the reason each is shaped the way it is.**

**Two pieces of state** — it was three, until the change in [Nothing is unpacked](#nothing-is-unpacked-the-write-that-bought-nothing) deleted the third along with the phase it guarded.

`jobs.scoredCount` is how many photos have been scored. It is a count into a list, which makes the list's **order** load-bearing rather than cosmetic. Entry order inside an archive is whatever the tool that built it chose, and a directory listing promises no order at all — so a cursor into "whatever order came back" would point at a different photo on every attempt, silently skipping some and re-scoring others, which is worse than not resuming. The list is therefore always built the same way: the archive's photo entries, sorted by entry name.

`pending_matches` holds the matches found so far. It's a second table rather than early rows in `matched_photos` because the two mean different things — a matched photo is a result in stable storage with a content-derived gallery name, and one of these is a note to a future attempt pointing into a working directory. Merging them would have meant a nullable `galleryName` on a type whose whole invariant is having one, plus result rows for jobs with no results. The second table costs one entity; the merge costs an invariant. They're cleared in the same transaction that writes the real results, and CASCADE takes them when a job is abandoned or deleted.

**The write order is the interesting part.** `checkpointScored` records the match *then* advances the cursor, in one transaction. A process killed between the two leaves the photo looking un-scored, so the next attempt scores it again and writes the same match again — which the unique `(jobId, photoPath)` index absorbs as a no-op. The other order would mean a photo marked done whose match was never written, and **a match silently missing from the results is the one failure the user cannot detect.** Of the two ways to be wrong, redundant work is the recoverable one.

**A matched photo is on disk before the checkpoint names it**, and it is written under a temporary name then renamed, so a file under its real name is complete *by construction*. Writing directly would leave a truncated photo after a kill that every later check accepts as present.

**Progress is seeded before any work.** A resumed job reports its checkpoint to the UI immediately, because otherwise the bar sits at 0% until this attempt's first photo is scored — and on a job that was 90% done, that reads as "it threw my work away".

The cost of all this is one small database write per photo, against a second or more of decoding and scoring per photo. The invariant it buys: `FindFacesInPhotosUseCase` is safe to call repeatedly for the same job, and every call either advances it or finishes it — never redoing and never losing work.

Resumption is tested rather than reasoned about, because a resume and a restart produce the *same results* and differ only in the work done. The fake matcher therefore records which photos it was handed: one test asserts a first attempt writes a checkpoint per photo, another seeds the checkpoint a killed attempt would have left and asserts only the remaining photos are scored, and a third asserts that a cursor past the end of the list scores nothing. A real process kill can't be simulated in-process — it is precisely the absence of unwinding, and any exception still runs the `finally` that cleans up what a kill leaves behind — so the two halves are driven through the same repository API from both sides.

## Nothing is unpacked: the write that bought nothing

A 500MB archive used to cost about **1GB of writes**, and the same again in peak storage. Two writes, two different causes, and only one of them was buying anything.

The **copy of the ZIP** into the job's own directory exists because of the *picker contract*, not the archive. `GetContent()` hands back a read grant scoped to the Activity, and by the time a worker runs — possibly after a process death — that grant can be gone. It buys more than durable access, too: a content Uri is not a file, and can be backed by Google Drive, in which case reading it is a network download of something that may not be on the phone at all. One copy makes everything after it local, seekable, re-readable and unchanging, which is what lets a resumed attempt start instantly instead of re-downloading.

The **extraction** bought nothing. Every photo was written to disk so that it could be read once, decoded, and — for the 95% that don't match — deleted again.

So it is gone. `ZipFile` (not `ZipInputStream`) reads the archive's central directory, which gives the entry list at once *and* random access to any entry, so each photo's bytes are read when its turn comes and dropped straight after. Only the photos that match are written anywhere.

Three things fell out of that, and they are the interesting part:

- **`extractionComplete` went with it.** That flag existed for exactly one reason — a half-extracted folder is indistinguishable from a finished one — and with no extraction there is no partially-done directory to misread. Deleting a phase deleted the state that guarded it.
- **The free-space precheck went too.** It demanded 1.3× the archive because extraction would definitely write that much. Writes are now proportional to *matches*, which cannot be known up front — and a check that cannot be computed is worse than none, because it rejects jobs that would have succeeded. Which is exactly what it did on a real device.
- **The zip-slip guard moved rather than disappeared.** Entry names are still attacker-controlled, and are now used to *name* a kept photo, so every path segment is dropped. Those names are also worked out for the whole list up front, because a resumed attempt scores only part of it: disambiguating as it goes would number photos differently depending on where it started, and hand one photo's name to another.

`ZipInputStream` straight off the picked Uri would remove the copy as well, but it is sequential-only: every resumed attempt would re-read the archive from the start to reach photo 214, and on a Drive-backed Uri that is a second download. That trade only pays off alongside a folder picker, where there is no archive at all.

### Cancelling

`cancelUniqueWork` plus what makes it responsive rather than nominal. Cancellation is cooperative, so `matchPhotos` checks before each photo's read and decode — the expensive step, and the one worth not spending on a job the user has called off — rather than leaving it to the next suspension point.

Both ViewModels also **rethrow `CancellationException`** before their generic handlers. It extends `Exception`, so a blanket catch reports a deliberate cancel to the user as a failure, and leaves the coroutine looking like it completed normally, which is how structured concurrency quietly breaks. The job's status resolution runs inside `NonCancellable` for the same family of reasons: a suspend database call in an already-cancelled coroutine throws immediately, which would leave a cancelled job looking *interrupted* and get it resumed against the user's wishes.

## Keeping the database changeable

`exportSchema` is on and the JSON under `app/schemas/` is **committed**, which is the part that's easy to skip and expensive to skip. It's the only record of what a shipped schema actually looked like, and without it there is no way to move an installed app forward: Room can't generate an `@AutoMigration` with nothing to diff against, and `MigrationTestHelper` has no starting point to build from.

Without that, the only way to ship a change is `fallbackToDestructiveMigration()` — which deletes the job history this database exists to hold. It's deliberately absent, so a missing migration **throws on open**: loud in development, where it's a two-line fix, rather than quiet in production, where it's data loss.

So changing an entity from here means bumping the version and saying how existing rows reach the new shape. Additive, unambiguous changes — a nullable column, a new table — are declared as an `autoMigrations` entry and Room generates the SQL from the two exported schemas. Anything it can't infer (a rename, a type change, a column computed from other columns) needs a hand-written `Migration` registered in `DatabaseModule`.

## The dormant server path (kept, not deleted)

The original backend-upload flow — `SnapFindApi.kt`, `JobRepository`/`JobRepositoryImpl`, `NetworkModule.kt` — is untouched code, just no longer called by `FindFacesInPhotosUseCase`. It's worth knowing two things about it if it's ever revived:

1. **It predates this backend's storage migration and is already out of sync.** `SnapFindApi.uploadJob()` still calls the single-shot `POST /jobs/upload`, which the backend replaced with a presigned-URL, resumable multipart flow (`/jobs/upload/init` → presigned PUTs → `/jobs/upload/complete`) — see [Cross-project status](../Face_recognition/DEEP_DIVE.md#cross-project-status), item 2. Reviving this path means updating it to the new upload sequence, not just re-enabling a call site.
2. **The threshold it used (0.5 in code, described as "current production" at 0.68-0.70 in docs) was never the same threshold the on-device path uses (0.60, `FaceMatcher.DEFAULT_THRESHOLD`)** — the two paths are tuned for different embedding models (DeepFace ArcFace server-side vs. `w600k_mbf` on-device) and are not interchangeable numbers.

Original context on the server flow this was built against — 4 REST endpoints, a polling loop for a multi-minute CPU job, DeepFace/ArcFace at a 0.68 threshold — is preserved below for anyone reviving it, but describes dormant, not current, app behavior.

<details>
<summary>Original server-flow reference (dormant)</summary>

The backend had 4 API endpoints the app called, in this sequence:

```
1. POST /jobs/upload
   → sends selfie + ZIP as multipart form data
   → backend creates a job, extracts photos, saves to disk
   → returns job_id (e.g. "a3f9c1d2-...")

2. POST /jobs/{job_id}/process
   → tells backend to start face matching
   → backend atomically marks the job "queued" and pushes it to a Redis/RQ queue
   → returns IMMEDIATELY with status "queued" — a separate worker process
     picks it up, marks it "processing", then "completed" or "failed"

3. GET /jobs/{job_id}           ← POLLING LOOP
   → called every 2 seconds
   → when status == "completed" → move to step 4
   → when status == "failed"    → show error to user
   → polls up to 60 times (2 minutes max)

4. GET /jobs/{job_id}/matches
   → fetches list of matched photos
   → each match has a download_url (full URL to the image)
   → app displays images using those URLs directly
```

The reason for the polling loop: face matching took 30 seconds to several minutes on CPU. If the app waited for a single HTTP response that long, Android's network layer would time out. By making the server return immediately and polling separately, the app stayed responsive and showed the user that work was happening.

The backend used **DeepFace** with the **ArcFace** model to turn each face into a 512-number vector (an "embedding"). Matching meant computing the **cosine distance** between two embeddings — a value from `0.0` (identical) to `~1.0` (very different people). Anything under the configured threshold counted as a match. The full pipeline (detection, embedding, caching, parallelism) is documented in `../Face_recognition/README.md`.

</details>

---

## Architecture overview

The app follows **Clean Architecture** with **MVVM**, which means the code is split into three layers with strict rules about what can talk to what.

```
Presentation Layer  (what the user sees)
      ↓  only calls
Domain Layer        (business rules, no Android/Retrofit imports)
      ↓  only calls
Data Layer          (network calls, file handling)
```

The rule: each layer can only talk to the layer below it. The UI never directly makes network calls. The repository never imports Compose. This separation makes the code testable and maintainable.

---

## Layer-by-layer explanation

### Data Layer

**`OnDeviceFaceMatchRepositoryImpl.kt`** — The active repository. Wraps `:facesdk`'s `FaceMatchEngine`, lazily created on first use (guarded by a `Mutex`, not eagerly at DI-graph construction, so model loading never blocks app startup) and kept as a Hilt `@Singleton` so the ~16 MB of ONNX models load once, not per job. Decodes and scores event photos one at a time — see [How on-device matching actually works](#how-on-device-matching-actually-works) for why.

**`SnapFindApi.kt` / `JobRepositoryImpl.kt`** — The dormant server path. Still real, compiling code — see [The dormant server path](#the-dormant-server-path-kept-not-deleted) — just not in the active call graph.

**`FileHelper.kt`** — Two jobs: `uriToFile()` copies a picked `Uri`'s content into a real `File` (Android doesn't let you read a `Uri` directly the way file APIs expect); `writeAtomically()` writes a file under a temporary name and renames it into place, so a file under its real name is complete by construction rather than possibly truncated by a process kill.

### Domain Layer

**`FaceMatchRepository.kt`** — The active contract:

```kotlin
interface FaceMatchRepository {
    suspend fun matchPhotos(selfie: File, eventPhotos: List<File>, threshold: Float): List<FaceMatchResult>
}
```

Named and shaped like `JobRepository` deliberately — not `OnDeviceRepository` — so a server-backed implementation could satisfy this exact interface later without the use case or ViewModel changing. That's the seam a hybrid on-device/server routing decision would plug into, whenever that decision gets made.

**`FaceMatchResult.kt`** — A plain domain type (`File` + `distance: Float`), not a reused Retrofit DTO. The old code returned `MatchItem` (a backend JSON shape — snake_case fields, a `download_url`) straight out of the domain layer, which is a real Clean Architecture violation: the domain layer shouldn't know what the backend's JSON looks like. This flow has nothing to do with the backend at all now, so it gets its own type.

**`JobRepository.kt`** — The dormant server contract (unchanged, still compiles, just unused):

```kotlin
interface JobRepository {
    suspend fun uploadJob(selfie: File, zip: File): UploadJobResponse
    suspend fun triggerProcessing(jobId: String, threshold: Double): JobSummaryResponse
    suspend fun getJobStatus(jobId: String): JobSummaryResponse
    suspend fun getJobMatches(jobId: String): List<MatchItem>
}
```

**`FindFacesInPhotosUseCase.kt`** — Owns the on-device sequence end to end: read the job's checkpoint → list the archive's photo entries in a fixed order → `FaceMatchRepository.matchPhotos()` over the ones not yet scored, writing out each match as it is found → clean up the temp selfie/zip files in a `finally` block regardless of success or failure. Catches `NoFaceDetectedException` (thrown by the SDK when the selfie itself has no detectable face) specifically, to surface a clear user-facing message instead of a raw exception string. Defaults `threshold` to `FaceMatcher.DEFAULT_THRESHOLD` (0.60, `:facesdk`'s own constant) rather than a second hardcoded copy of that number.

Returns `Result<List<FaceMatchResult>>` — same reasoning as before: the ViewModel gets a success value or a caught exception, never a crash.

### Presentation Layer

**`UploadUiState.kt`** — Unchanged shape, new payload type:

```kotlin
sealed interface UploadUiState {
    object Idle       : UploadUiState   // nothing happening, form visible
    object Processing : UploadUiState   // on-device matching in progress
    data class Success(val matches: List<FaceMatchResult>) : UploadUiState
    data class Error(val message: String) : UploadUiState
}
```

**`UploadViewModel.kt`** — Converts picked `Uri`s to `File`s and enqueues the job; it no longer *runs* one. The job lives in a worker now (see [Running the job outside the app](#running-the-job-outside-the-app-workmanager-and-a-foreground-service)), so this class maps `WorkInfo` into `UploadUiState` rather than owning that state itself. Note what didn't change when the use case went from server-calling to on-device, and again when it went from ViewModel-hosted to worker-hosted: the use case's own signature. That's the point of the boundary.

**`UploadScreen.kt`** — Same two-button-plus-submit flow; the "Processing" copy now reads "Finding matches on your device..." instead of "Uploading...".

**`ResultsScreen.kt`** — Displays matched photos in a 2-column grid via Coil's `AsyncImage` — now given a local `File` (`match.photo`) instead of a network URL. Coil supports `File` as a model source natively, no extra configuration needed. Owns its own `ResultsViewModel` (selection mode, per-photo save status, batch save progress) — see [Results persistence, gallery save, and the full-screen viewer](#results-persistence-gallery-save-and-the-full-screen-viewer) above. Tapping a photo (outside selection mode) opens `PhotoViewerScreen`, a full-screen swipeable viewer.

---

## Dependency Injection with Hilt

**`FaceMatchModule.kt`** — The active binding: when something asks for a `FaceMatchRepository`, give it a `OnDeviceFaceMatchRepositoryImpl` singleton.

**`RepositoryModule.kt`** / **`NetworkModule.kt`** — The dormant server path's bindings (`JobRepository` → `JobRepositoryImpl`, the `Retrofit`/`SnapFindApi` singletons). Left in place; an unused Hilt binding is harmless, nothing in the graph requires every binding to be reached.

Once these modules are set up, Hilt handles everything else. Any class annotated with `@Inject constructor(...)` gets its dependencies filled in automatically.

---

## StateFlow and Compose reactivity

`StateFlow` is a Kotlin data stream that always holds the current value and emits every update to anyone listening. In Compose:

```kotlin
val uiState by viewModel.uiState.collectAsState()
```

This line makes Compose subscribe to state updates. Whenever `uiState` changes (e.g. from `Processing` to `Success`), Compose automatically redraws only the parts of the screen that use that value. You never manually call `invalidate()` or `notifyDataSetChanged()`.

---

## Networking with Retrofit + OkHttp

Part of the dormant server path — not exercised by the app's active flow today, kept for context on how it was built. Retrofit is a type-safe HTTP client. You define an interface describing your API and Retrofit generates the implementation. Under the hood, it uses OkHttp to make actual network requests.

For multipart upload (sending files), the code manually builds `MultipartBody.Part` objects:

```kotlin
val selfiePart = MultipartBody.Part.createFormData(
    "selfie",          // form field name — must match what the backend expects
    selfieFile.name,   // filename shown in the request
    selfieFile.asRequestBody("image/*".toMediaTypeOrNull())  // raw file bytes + MIME type
)
```

The backend's FastAPI endpoint receives this as an `UploadFile` with `field_name = "selfie"`.

---

## Project structure

```
SnapFindAI/                             # this Gradle project
├── app/src/main/java/com/example/snapfindai/
│   ├── di/
│   │   ├── FaceMatchModule.kt        # Binds OnDeviceFaceMatchRepositoryImpl (ACTIVE)
│   │   ├── NetworkModule.kt          # Retrofit/SnapFindApi/base URL (dormant path)
│   │   └── RepositoryModule.kt       # Binds JobRepositoryImpl (dormant path)
│   ├── data/
│   │   ├── local/                    # Room: SnapFindDatabase, JobEntity, MatchedPhotoEntity, JobDao
│   │   ├── remote/
│   │   │   └── SnapFindApi.kt        # Retrofit interface (dormant path)
│   │   ├── ModelConfig.kt            # model URLs + checksums, shared by both repos below (ACTIVE)
│   │   └── repository/
│   │       ├── OnDeviceFaceMatchRepositoryImpl.kt   # Wraps facesdk's FaceMatchEngine (ACTIVE)
│   │       ├── ModelProvisioningRepositoryImpl.kt   # checks/downloads models for onboarding (ACTIVE)
│   │       ├── JobHistoryRepositoryImpl.kt          # Room-backed last-job persistence (ACTIVE)
│   │       ├── MediaStoreGalleryRepositoryImpl.kt   # Save a match to the system gallery (ACTIVE)
│   │       └── JobRepositoryImpl.kt  # One method per API call (dormant path)
│   ├── domain/
│   │   ├── model/
│   │   │   ├── FaceMatchResult.kt    # Plain domain type: File + distance + optional savedAt (ACTIVE)
│   │   │   └── SavedJob.kt           # timestamp + matches, what GetLastJobUseCase returns (ACTIVE)
│   │   ├── repository/
│   │   │   ├── FaceMatchRepository.kt         # The active matching contract
│   │   │   ├── ModelProvisioningRepository.kt # areModelsReady/downloadModels (ACTIVE)
│   │   │   ├── JobHistoryRepository.kt        # saveJob/getLastJob/markSavedToGallery/removeMatches (ACTIVE)
│   │   │   ├── PhotoGalleryRepository.kt      # saveToGallery(photo): Uri? (ACTIVE)
│   │   │   └── JobRepository.kt               # The dormant contract
│   │   └── usecase/
│   │       ├── FindFacesInPhotosUseCase.kt   # checkpoint -> match from archive -> persist -> cleanup
│   │       ├── CheckModelsReadyUseCase.kt    # backs AppStartupViewModel's splash-gating check
│   │       ├── DownloadModelsUseCase.kt      # backs OnboardingViewModel's download + progress
│   │       ├── GetLastJobUseCase.kt          # restores the last saved job on app relaunch
│   │       ├── SaveMatchedPhotoUseCase.kt    # save to gallery + mark saved, as one Result
│   │       └── RemoveMatchedPhotosUseCase.kt # curate results before downloading (not gallery delete)
│   ├── presentation/
│   │   ├── AppStartupViewModel.kt    # decides Onboarding vs. Upload before the splash dismisses
│   │   └── screens/
│   │       ├── onboarding/ # OnboardingScreen.kt + OnboardingViewModel.kt + OnboardingUiState.kt
│   │       ├── upload/     # UploadScreen.kt, UploadViewModel.kt, UploadUiState.kt
│   │       ├── results/    # ResultsScreen.kt + ResultsViewModel.kt + ResultsUiState.kt
│   │       └── viewer/     # PhotoViewerScreen.kt (HorizontalPager) + PhotoViewerViewModel.kt
│   ├── ui/theme/                     # Material 3 color, typography, theme setup
│   ├── utils/
│   │   ├── FileHelper.kt             # Uri->File, plus atomic file writes
│   │   └── DownloadNotificationHelper.kt  # posts the batch-save-completed system notification
│   ├── spike/                        # facesdk validation harness — see facesdk/README below
│   ├── MainActivity.kt               # Single activity, hosts Compose navigation
│   └── SnapFindApplication.kt        # Hilt application entry point (@HiltAndroidApp)
│
└── facesdk/                          # standalone module, no Compose/Hilt/Retrofit deps
    ├── src/debug/assets/models/      # bundled models -- debug builds only (see Model distribution)
    └── src/main/java/com/example/snapfindai/facesdk/
        ├── api/                      # FaceDetector, FaceEmbedder, FaceDetectorConfig — interfaces + config
        ├── detector/                 # ScrfdFaceDetector + ScrfdPostprocess (the one impl)
        ├── embedder/                 # OnnxFaceEmbedder (the one impl)
        ├── model/                    # DetectedFace, FaceLandmarks, FaceEmbedding, ...
        ├── internal/                 # OrtSessions — shared ONNX session loading
        ├── FaceMatchEngine.kt        # the facade
        ├── ModelDownloader.kt        # generic fetch+cache+checksum-verify, for release builds
        ├── FaceAligner.kt, FaceMatcher.kt, FaceSdkLogger.kt, InvalidModelException.kt, ...
        └── (src/test/ — 38 unit tests)
```

---

## Setup and running

### Prerequisites
- Android Studio Ladybug or newer
- JDK 17 (AGP 8.11.2 requires it — Android Studio bundles its own JBR if your `JAVA_HOME` is older)
- Physical Android device or emulator (API 24+)
- **No backend needed** — face matching runs entirely on-device now. The backend is only relevant if you're reviving the dormant server path.
- **Internet needed on first launch, release builds only.** Debug builds bundle the models and work offline immediately; release builds show an Onboarding screen that downloads them once (~16 MB) and caches them — see [First-run onboarding and the splash screen](#first-run-onboarding-and-the-splash-screen).

### Steps

1. Clone the project and open the `SnapFindAI` folder in Android Studio
2. Wait for Gradle sync to complete (pulls in both `:app` and `:facesdk`)
3. Run the app on your device — pick a selfie and a ZIP of event photos from the picker, tap "Find My Photos"

The model weights (`det_500m.onnx`, `w600k_mbf.onnx`, ~16 MB total) live in `facesdk/src/debug/assets/models/` and are gitignored — same rationale as the backend never committing its downloaded model weights. They're only bundled in debug builds; if they're missing there, `FaceDetector.create()`/`FaceEmbedder.create()` will fail to find the asset — pull them from wherever the backend's `models/insightface/models/buffalo_sc/` weights came from. Release builds don't need them locally at all — they download from the GitHub Release instead.

### Why cleartext HTTP is allowed
`AndroidManifest.xml` still contains `android:usesCleartextTraffic="true"`, a leftover from the dormant server path (the backend ran on plain HTTP on a local IP). Not load-bearing for the active on-device flow, but not removed either — reviving the server path would need it again.

---

## Tech stack

| Technology | Why it was chosen |
|---|---|
| **Kotlin** | Modern Android language, null safety, coroutines built-in |
| **Jetpack Compose** | Declarative UI — the screen automatically redraws when state changes, no manual view updates |
| **ViewModel + StateFlow** | Survives screen rotations, single source of truth for UI state |
| **Hilt** | Removes manual dependency wiring, makes code testable |
| **ONNX Runtime (Android)** | Runs the on-device SCRFD detector + w600k_mbf embedder — the actual matching engine now |
| **Coroutines** | Makes async code look sequential, no callback hell; every facesdk call is suspend |
| **Coil** | Native Compose image loading — now loading local `File`s instead of network URLs |
| **Clean Architecture** | Separation of concerns — each layer has one job |
| **Retrofit + OkHttp** | Still present for the dormant server path; not used by the active flow |
| **`java.net.URL` (plain, no library)** | `ModelDownloader`'s model fetch — deliberately no Retrofit/Ktor dependency, so facesdk doesn't force a networking stack on whatever a host app already uses |
| **Room** | Last-completed-job persistence (`jobs`/`matched_photos` tables) — survives app restart; not used for file bytes, only metadata pointing at files in `filesDir` |
| **MediaStore** | Saving a matched photo into the system gallery — scoped-storage insert on API 29+, legacy public-directory write + `WRITE_EXTERNAL_STORAGE` below that |
| **`androidx.core:core-splashscreen`** | System splash screen kept on screen until `AppStartupViewModel` knows whether Onboarding is needed — avoids a blank frame or a wrong-screen flash |
| **Robolectric + JUnit** | facesdk's 38 unit tests — real Android graphics classes (Bitmap, Canvas, RectF) under test, not stubbed out |

---

## Status & roadmap

The original MVP checklist (Compose UI, Retrofit integration, Hilt DI, Coil image loading) is done.

**On-device matching (2026-09-27): done.** Three validation phases (detection, alignment, end-to-end matching) each checked against the real backend, a standalone `:facesdk` module with a clean public API, and the real app wired to use it — no server call in the active flow. See [How on-device matching actually works](#how-on-device-matching-actually-works) and [The `:facesdk` module](#the-facesdk-module) above.

**SDK hardening (2026-09-27): done.** The four gaps flagged in an architecture review are fixed: `FaceDetectorConfig` makes detection/NMS thresholds and input size real constructor parameters instead of hardcoded constants; a wrong-shaped bring-your-own model is rejected at construction with a clear `InvalidModelException`; `FaceMatchEngine` no longer exposes its detector/embedder as independently closeable, and using it after `close()` throws instead of misbehaving quietly; real ProGuard consumer rules ship in the AAR automatically. 12 tests added for this alone.

**Model distribution (2026-09-27): done.** Release builds no longer bundle the ~16MB of ONNX models at all — moved to a debug-only asset source set, fetched and cached on first use instead via a new `ModelDownloader`, checksum-verified, hosted as a GitHub Release asset. Confirmed on a real device: fresh install (uninstalled first, no cached models anywhere), ran the real upload flow with no shortcuts, downloaded both models on first use, same 4/4 expected matches as every prior run. See [Model distribution](#model-distribution-why-release-builds-download-instead-of-bundling) above.

**Results persistence, gallery save, and full-screen viewer (2026-09-28): done.** Last-completed-job results now survive an app restart (Room, photos relocated to stable `filesDir`), matches can be saved individually or in bulk to the system gallery (MediaStore, correct dual-path handling for API 29+ vs older), the grid supports long-press multi-select with batch remove/download, a batch save reports progress live and confirms via snackbar + a real system notification that opens the saved photo in the system Gallery app on tap, and tapping any photo opens a full-screen swipeable viewer (`HorizontalPager`). Verified end-to-end on a real API 31 device: persistence across restart, selection/remove/download-all/download-selected, and the full-screen viewer all confirmed working; the storage-permission popup itself couldn't be exercised on that device (API 31 is already past the API <29 legacy-permission path). See [Results persistence, gallery save, and the full-screen viewer](#results-persistence-gallery-save-and-the-full-screen-viewer) above.

**First-run onboarding and splash screen (2026-09-28): done.** The ~16MB model download is no longer silent: a real system splash screen (`androidx.core.splashscreen`) covers the app until a one-time check knows whether the models are already cached, then opens straight to Upload if so, or an Onboarding screen (explanation + "Get Started" + a live progress bar wired to `ModelDownloader`'s existing `onProgress` callback) if not. Verified on a real device with a genuinely fresh install (app uninstalled first, not just app data cleared, since `pm clear` was denied by adb on that device): splash showed briefly, Onboarding appeared with a working progress bar, and it landed on Upload afterward. See [First-run onboarding and the splash screen](#first-run-onboarding-and-the-splash-screen) above.

**Job history and the design system (2026-09-29 → 09-30): done.** Past jobs now show as Recent Jobs cards on the Upload screen and reopen via `results?jobId=N`. The hardcoded-constant sprawl is gone: `ui/theme/` owns spacing, dimensions, shapes and a night palette, and `presentation/components/` owns one definition each of the pill button, photo tile, status badge, selection indicator and wavy progress ring. See [Job history and the shared design system](#job-history-and-the-shared-design-system).

**Download flow rework and idempotent saving (2026-10-01 → 10-02): done.** Three competing download controls reduced to one per mode, with the single-photo download and remove moved into the viewer. Saving to the gallery is now idempotent (stable filename derived from the source path, not the clock — re-downloading 24 photos no longer leaves 48 copies), the saved tick is reconciled against MediaStore on resume so it means "in your gallery right now", the bulk button states its real scope (`Download 17`) and disables itself at zero instead of being a live no-op, and notification permission is no longer entangled with saving. Navigation to the viewer now passes photo identity rather than grid position, and `getMatchedPhotosForJob` has an explicit `ORDER BY`. See [Downloads](#downloads-one-control-per-scope-and-a-tick-that-tells-the-truth).

**Threading, storage, and cancel (2026-10-03 → 10-04): done.** Verified against a real 500MB / 200+ photo event folder: the whole job had been running on the main thread (unzip, every decode, every file delete) and froze the app; working files had been living in `cacheDir`, which Android emptied mid-job and took the selfie with it. Both fixed, the selfie is now prepared before the unzip and read exactly once per job, and the job can be cancelled. That run completed in under five minutes with no freeze. See [Running a real event folder on device](#running-a-real-event-folder-on-device-threading-storage-and-cancel).

**The database became changeable (2026-10-05): done.** `exportSchema` was off, so nothing recorded what a shipped schema looked like — which meant the only way to ship any change at all was to wipe user data. Schemas are now exported and committed, and destructive fallback is deliberately absent. See [Keeping the database changeable](#keeping-the-database-changeable).

**The job moved out of the app (2026-10-05 → 10-06): done.** A matching job now runs in `MatchPhotosWorker` under a foreground service, so leaving the app no longer ends it and a process kill reschedules rather than loses it. One job row per work request, created inside the worker, so retries converge instead of orphaning rows. A directory per request, which removed a real race where a cancelled worker's cleanup deleted the *next* job's inputs. Completion is one transaction, with a unique `(jobId, photoPath)` index behind it so the duplicate-row corruption it guards against cannot be represented at all. Cleanup of what a killed process never got to runs at process start, correlated against WorkManager rather than against a timeout. See [Running the job outside the app](#running-the-job-outside-the-app-workmanager-and-a-foreground-service).

**Audit follow-ups (2026-10-06): done.** Two external code audits found six issues reasoning alone had missed — the shared-input-file race, the sweeper's own race, non-transactional completion, concurrent save/remove in Results, a download that could hang forever with a decorative Cancel button, and inference resources released only on the success path. All fixed. Backup is configured and off, and cleartext HTTP is debug-only, which makes the README's "photos never leave your phone" claim true rather than aspirational.

**Storage control, real pause, and honest error text (2026-10-07): done.** Three things the user could see but not act on. Recent Jobs now shows how much storage past jobs are holding and a long-press deletes one, with the gallery left untouched by construction rather than by care. Onboarding's Pause button, previously `onPause = {}` with a content description that admitted it, now genuinely pauses: `ModelDownloader` keeps its partial file and resumes with an HTTP `Range` request, treating only a `206` as a resume and `fsync`ing the bytes a later resume will trust — Pause keeps them, Cancel discards them. And raw exception text no longer reaches the screen: a new `UserFacingException` marks the messages written for a person, everything else is logged and replaced with a sentence that's true of all of them. See [Job history](#job-history-and-the-shared-design-system) and [First-run onboarding](#first-run-onboarding-and-the-splash-screen).

**Checkpoint and resume (2026-10-08): done.** A matching job now carries on from where a killed attempt stopped instead of restarting it: a scored cursor into a deterministically ordered photo list (the sort is what makes a count mean anything), and matches written as they're found into their own table. The checkpoint records the match before advancing the cursor, so the only way to be wrong is to re-score one photo, which a unique index absorbs — never to lose a match, which the user could not detect. See [Resuming instead of restarting](#resuming-instead-of-restarting).

**Nothing is unpacked (2026-10-09): done.** A 500MB archive cost about 1GB of writes and the same in peak storage; half of that bought nothing. Photos are now read out of the archive as they are scored, via `ZipFile` rather than a stream so the entry list and random access come free, and only the photos that match are written anywhere. The copy of the archive stays, because it is what makes a job independent of the network and of a picker grant that expires. Three things followed: `extractionComplete` was deleted along with the phase it guarded, the free-space precheck went with the writes it was predicting, and the zip-slip guard moved to naming the kept photos. See [Nothing is unpacked](#nothing-is-unpacked-the-write-that-bought-nothing).

**The lifecycle is fully covered (2026-10-09): done.** `AbandonedJobSweeper` was the last untested piece, and the piece most able to do damage: it deletes a job's working files, so the question every test asks is whether it can ever delete something a job was coming back for. Seven cases against a real WorkManager, since a stub would have answered the liveness question the sweeper exists to ask.

**Selection on the job grid, and a sheet that stops moving (2026-10-09): done.** The Upload screen gained an app bar and the results grid's selection behaviour, so several jobs can be downloaded or deleted at once; the batch-save loop moved into `SaveMatchedPhotosUseCase` rather than being copied into a second ViewModel. Onboarding's sheet no longer changes height as its text changes: every state is measured and one is placed, so the sheet is exactly as tall as its tallest state — derived rather than declared, which survives translation and font scaling in a way a fixed height does not. And the job card dropped the timestamp it was showing, which told the user nothing they could act on and cost a line of chrome over the preview.

**Still open:**

*Resource ceilings*
- **The ZIP is still copied into app storage** — one write of the folder's size, now the only large one, and deleted when the job ends. Two Android facts force it, both of them true for a purely local file: `ActivityResultContracts.GetContent()` issues a read grant scoped to the Activity, which is not persistable and does not survive the process death that resume exists for; and `java.util.zip.ZipFile` has only `File`/path constructors, so there is no stdlib way to hand it a `content://` Uri while keeping the random access the scored cursor depends on. Removing it needs *both* a switch to `OpenDocument()` + `takePersistableUriPermission` *and* an answer to the second — a `/proc/self/fd/<n>` path (works, undocumented, fails when a provider returns a pipe) or Apache Commons Compress's `ZipFile(SeekableByteChannel)`. A folder picker would remove the archive, and the copy with it.
- **Peak memory is ~96MB per photo**, since the EXIF rotation holds a second full bitmap alongside the first. Per-photo, not cumulative, so folder size doesn't change it. Downsampling was tried and **rejected** — event photos have small faces in large frames, and reducing resolution measurably cost matching accuracy. A single-allocation decode (`ImageDecoder`, API 28+) would halve the peak without touching resolution, but it replaces a decoder validated pixel-for-pixel against `cv2.imread`, so it needs the alignment harness re-run.

*Missing controls*
- **The user has to zip the folder themselves** — there's no folder picker and no multi-select. The most-felt friction in real use.
- **`distance` and `JobEntity.threshold` are persisted and never surfaced.** Nothing passes a threshold, so every job runs at the default, and the per-photo distance behind a match is never shown. The no-matches message used to advertise "or a higher threshold" — a control the app has never had; that wording is gone, but the data behind it is still write-only.

*Known limitations, accepted*
- **Gallery-saved photos are duplicated, not aliased.** A saved match exists both as the app's internal `filesDir` copy (what the grid renders) and as a separate MediaStore copy. Android has no API to alias an arbitrary app-private file into MediaStore; the grid could instead load from the gallery `Uri` once saved and drop the internal copy — a storage-efficiency concern, not a correctness one.
- **Reinstalling loses MediaStore ownership** of previously saved photos, so they read as un-downloaded and re-downloading produces `foo (1).jpg` duplicates.
- **The tick only reconciles on resume** — no `ContentObserver`, so a gallery deletion in split-screen while Results is visible leaves it stale until the screen resumes.
- **Nothing in the job lifecycle is untested any more.** The app module has 29 tests across four classes (`:facesdk` has 41 across eight): DAO guards via `Room.inMemoryDatabaseBuilder`, the gallery reconcile, the use case's resumption, naming and write behaviour, and the sweeper against a real WorkManager. What is still untested is the UI layer, deliberately.
- **Server-vs-on-device routing is an open question, not a decision.** `FaceMatchRepository` is shaped so a server-backed implementation could plug in later without touching the use case — but whether/when that's worth building is undecided, and the Retrofit path remains wired into Hilt with nothing calling it. See [Cross-project status](../Face_recognition/DEEP_DIVE.md#cross-project-status) in the backend's docs.

Cross-project status (this app + the backend) is tracked in one place to avoid two docs drifting out of sync: see "Cross-project status" in `../Face_recognition/README.md`.