# SnapFind AI — Android Client

An Android app that lets you find yourself in a batch of event photos. You pick a selfie and a ZIP archive of event photos, and the app matches faces **entirely on-device** — no upload, no network call, no server in the loop.

That wasn't the original design. The app was first built against a backend (`../Face_recognition/`, its own README covers the ML pipeline and API) that uploads photos, matches server-side, and polls for results. As of 2026-09-27, matching has moved fully on-device, backed by a validated on-device SDK (`:facesdk`, its own Gradle module in this repo) built and proven against that same backend across three separate phases — detection, alignment, then end-to-end match decisions — each checked against the server's real output before moving to the next, not assumed correct. The server-calling code is still in the repo, intact, unused by the app today; see [How on-device matching actually works](#how-on-device-matching-actually-works) and [The dormant server path](#the-dormant-server-path-kept-not-deleted).

---

## The problem this solves

After large events (weddings, parties, conferences), photographers distribute massive folders of unorganized photos. Finding photos of a specific person in an archive of thousands of images is a tedious, manual process. This app automates that: pick a selfie and the event archive, get back only the photos containing your face — matched right there on the phone.

---

## What this app does (user flow)

1. User opens the app and lands on the **Upload Screen**
2. User picks a selfie from their gallery (the face to search for)
3. User picks a ZIP file containing event photos
4. User taps **Find My Photos**
5. App unzips the archive into local cache, and runs on-device face detection + matching against every extracted photo
6. App navigates to the **Results Screen** showing matched photos in a grid
7. User can scroll through and view every event photo they appear in

No upload, no polling loop — steps 5-6 happen on the phone's CPU, typically in a few seconds for a handful of photos.

---

## How on-device matching actually works

`FindFacesInPhotosUseCase` (domain layer) owns the sequence:

```
1. FileHelper.unzip(zipFile, extractDir)
   → extracts every photo from the ZIP into app cache, guarded against
     "zip slip" (an entry trying to write outside extractDir)

2. FaceMatchRepository.matchPhotos(selfie, eventPhotos, threshold)
   → the actual matching, delegated to OnDeviceFaceMatchRepositoryImpl
     (data layer), which wraps :facesdk's FaceMatchEngine

3. Non-matched extracted photos are deleted
   → only what the Results screen will actually display stays on disk
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

**Known UX gap, not yet fixed:** the download currently happens silently inside the existing "Processing" step the first time a user submits a job — there's no dedicated screen explaining that ~16MB is about to download, and no real progress bar shown (`ModelDownloader` already supports an `onProgress` callback; it's just not wired up to any UI yet). A proper first-run setup screen is the next piece of work here, not a redesign of the download mechanism itself.

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

**`FileHelper.kt`** — Two jobs: `uriToFile()` copies a picked `Uri`'s content into a real cache `File` (Android doesn't let you read a `Uri` directly the way file APIs expect); `unzip()` extracts a ZIP into a directory, rejecting any entry whose resolved path would land outside the target directory ("zip slip" — a zip is user-supplied input, worth the same suspicion as a downloaded one).

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

**`FindFacesInPhotosUseCase.kt`** — Owns the on-device sequence end to end: unzip → `FaceMatchRepository.matchPhotos()` → delete the non-matched extracted photos → clean up the temp selfie/zip files in a `finally` block regardless of success or failure. Catches `NoFaceDetectedException` (thrown by the SDK when the selfie itself has no detectable face) specifically, to surface a clear user-facing message instead of a raw exception string. Defaults `threshold` to `FaceMatcher.DEFAULT_THRESHOLD` (0.60, `:facesdk`'s own constant) rather than a second hardcoded copy of that number.

Returns `Result<List<FaceMatchResult>>` — same reasoning as before: the ViewModel gets a success value or a caught exception, never a crash.

### Presentation Layer

**`UploadUiState.kt`** — Unchanged shape, new payload type:

```kotlin
sealed interface UploadUiState {
    object Idle       : UploadUiState   // nothing happening, form visible
    object Processing : UploadUiState   // unzip + on-device matching in progress
    data class Success(val matches: List<FaceMatchResult>) : UploadUiState
    data class Error(val message: String) : UploadUiState
}
```

**`UploadViewModel.kt`** — Unchanged responsibilities: converts picked `Uri`s to `File`s, calls `FindFacesInPhotosUseCase` on `viewModelScope`, updates state based on the result. No code here needed to change when the use case switched from server-calling to on-device — that's the point of the use-case boundary.

**`UploadScreen.kt`** — Same two-button-plus-submit flow; the "Processing" copy now reads "Finding matches on your device..." instead of "Uploading...".

**`ResultsScreen.kt`** — Displays matched photos in a 2-column grid via Coil's `AsyncImage` — now given a local `File` (`match.photo`) instead of a network URL. Coil supports `File` as a model source natively, no extra configuration needed.

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
│   │   ├── remote/
│   │   │   └── SnapFindApi.kt        # Retrofit interface (dormant path)
│   │   └── repository/
│   │       ├── OnDeviceFaceMatchRepositoryImpl.kt  # Wraps facesdk's FaceMatchEngine (ACTIVE)
│   │       └── JobRepositoryImpl.kt  # One method per API call (dormant path)
│   ├── domain/
│   │   ├── model/
│   │   │   └── FaceMatchResult.kt    # Plain domain type: File + distance (ACTIVE)
│   │   ├── repository/
│   │   │   ├── FaceMatchRepository.kt  # The active contract
│   │   │   └── JobRepository.kt        # The dormant contract
│   │   └── usecase/
│   │       └── FindFacesInPhotosUseCase.kt  # unzip -> match -> cleanup, on-device
│   ├── presentation/
│   │   └── screens/
│   │       ├── upload/     # UploadScreen.kt, UploadViewModel.kt, UploadUiState.kt
│   │       └── results/    # ResultsScreen.kt — grid of FaceMatchResult
│   ├── ui/theme/                     # Material 3 color, typography, theme setup
│   ├── utils/
│   │   └── FileHelper.kt             # Uri->File, plus ZIP extraction
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
- **Internet needed on first launch, release builds only.** Debug builds bundle the models and work offline immediately; release builds download them once (~16 MB) and cache them — see [Model distribution](#model-distribution-why-release-builds-download-instead-of-bundling).

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
| **Robolectric + JUnit** | facesdk's 38 unit tests — real Android graphics classes (Bitmap, Canvas, RectF) under test, not stubbed out |

---

## Status & roadmap

The original MVP checklist (Compose UI, Retrofit integration, Hilt DI, Coil image loading) is done.

**On-device matching (2026-09-27): done.** Three validation phases (detection, alignment, end-to-end matching) each checked against the real backend, a standalone `:facesdk` module with a clean public API, and the real app wired to use it — no server call in the active flow. See [How on-device matching actually works](#how-on-device-matching-actually-works) and [The `:facesdk` module](#the-facesdk-module) above.

**SDK hardening (2026-09-27): done.** The four gaps flagged in an architecture review are fixed: `FaceDetectorConfig` makes detection/NMS thresholds and input size real constructor parameters instead of hardcoded constants; a wrong-shaped bring-your-own model is rejected at construction with a clear `InvalidModelException`; `FaceMatchEngine` no longer exposes its detector/embedder as independently closeable, and using it after `close()` throws instead of misbehaving quietly; real ProGuard consumer rules ship in the AAR automatically. 12 tests added for this alone.

**Model distribution (2026-09-27): done.** Release builds no longer bundle the ~16MB of ONNX models at all — moved to a debug-only asset source set, fetched and cached on first use instead via a new `ModelDownloader`, checksum-verified, hosted as a GitHub Release asset. Confirmed on a real device: fresh install (uninstalled first, no cached models anywhere), ran the real upload flow with no shortcuts, downloaded both models on first use, same 4/4 expected matches as every prior run. See [Model distribution](#model-distribution-why-release-builds-download-instead-of-bundling) above.

**Still open:**
- **No first-run download experience.** The model download currently happens silently inside the existing "Processing" step — no dedicated screen explaining ~16MB is about to download, no real progress bar shown (the download mechanism already supports progress reporting; it's just not wired to any UI yet). The next piece of work here.
- **No local persistence.** Closing the app loses all match results — nothing survives a process restart. A prerequisite for an actual "download this photo" feature, not yet built.
- **UI is intentionally bare-bones** — two buttons and a spinner, functionally correct, not redesigned.
- **Server-vs-on-device routing is an open question, not a decision.** `FaceMatchRepository` is shaped so a server-backed implementation could plug in later without touching the use case — but whether/when that's worth building is undecided. See [Cross-project status](../Face_recognition/DEEP_DIVE.md#cross-project-status) in the backend's docs.

Cross-project status (this app + the backend) is tracked in one place to avoid two docs drifting out of sync: see "Cross-project status" in `../Face_recognition/README.md`.