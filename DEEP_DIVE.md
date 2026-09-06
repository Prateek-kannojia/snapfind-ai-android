# SnapFind AI — Android Client

An Android app that lets you find yourself in a batch of event photos. You pick a selfie and a ZIP archive of event photos, the app sends them to a backend server, and shows you every photo from the archive where your face was detected.

The backend lives in `../Face_recognition/` (its own README covers the ML pipeline and API in depth). This doc covers the Android client only.

---

## The problem this solves

After large events (weddings, parties, conferences), photographers distribute massive folders of unorganized photos. Finding photos of a specific person in an archive of thousands of images is a tedious, manual process. This app automates that: upload a selfie and the event archive, get back only the photos containing your face.

---

## What this app does (user flow)

1. User opens the app and lands on the **Upload Screen**
2. User picks a selfie from their gallery (the face to search for)
3. User picks a ZIP file containing event photos
4. User taps **Find My Photos**
5. App uploads both files to the backend and triggers face matching
6. App polls the backend every 2 seconds until processing is complete
7. App navigates to the **Results Screen** showing matched photos in a grid
8. User can scroll through and view every event photo they appear in

---

## How the app communicates with the backend

The backend has 4 API endpoints the app uses, in this exact sequence:

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

The reason for the polling loop: face matching takes 30 seconds to several minutes on CPU. If the app waited for a single HTTP response that long, Android's network layer would time out and show an error. By making the server return immediately and polling separately, the app stays responsive and shows the user that work is happening.

---

## Backend AI context (what's actually happening server-side)

The backend uses **DeepFace** with the **ArcFace** model to turn each face into a 512-number vector (an "embedding"). Faces of the same person produce embeddings that are numerically close together; different people produce embeddings that are far apart. Matching a selfie against an event photo means computing the **cosine distance** between their two embeddings — a value from `0.0` (identical) to `~1.0` (very different people). Anything under the configured threshold (default `0.68`) counts as a match. The full pipeline (detection, embedding, caching, parallelism) is documented in `../Face_recognition/README.md` — worth reading if you need to explain *why* a job takes as long as it does, or why the threshold query param exists on `processJob()` in `SnapFindApi.kt`.

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

**`SnapFindApi.kt`** — The Retrofit interface. Defines what the HTTP calls look like. Each function is a `suspend fun` (Kotlin coroutine) that represents one API call. The data classes next to it define exactly what JSON the backend sends back:

```kotlin
// This tells Retrofit: make a POST to /jobs/upload with two multipart fields
@Multipart
@POST("jobs/upload")
suspend fun uploadJob(
    @Part selfie: MultipartBody.Part,
    @Part eventPhotosZip: MultipartBody.Part
): UploadJobResponse
```

The data classes (like `UploadJobResponse`, `MatchItem`) must have field names that exactly match the JSON keys the backend sends. Gson (the JSON library) maps them automatically. If a field name is wrong, Gson silently gives you `null` instead of crashing, so mismatches are important to catch.

**`JobRepositoryImpl.kt`** — The only class that knows how to talk to the backend, one API call per method (see `JobRepository.kt` below — the *sequence* of calls is a domain-layer concern, not this class's job). It also:
- Converts Android `File` objects into Retrofit `MultipartBody.Part` (the format HTTP multipart uploads require)
- Prepends the backend base URL to relative image paths returned by the server (e.g. `/jobs/x/matches/1/download` → `http://192.168.0.110:8000/jobs/x/matches/1/download`)

**`FileHelper.kt`** — Android does not let you access files directly from a `Uri` (the way files are referenced in Android's content system). This utility copies the file content from the Uri into a real `File` in the app's cache directory so Retrofit can read it.

### Domain Layer

**`JobRepository.kt`** — A Kotlin interface with one method per API call, deliberately kept "dumb" (no polling, no orchestration, no business decisions):

```kotlin
interface JobRepository {
    suspend fun uploadJob(selfie: File, zip: File): UploadJobResponse
    suspend fun triggerProcessing(jobId: String, threshold: Double): JobSummaryResponse
    suspend fun getJobStatus(jobId: String): JobSummaryResponse
    suspend fun getJobMatches(jobId: String): List<MatchItem>
}
```

This is the **contract** between the domain layer and the data layer. The ViewModel never talks to this directly — it goes through the use case below. This separation means you could swap the real implementation for a fake one in tests, or replace the backend with a different API, without touching the ViewModel or the use case's orchestration logic.

**`FindFacesInPhotosUseCase.kt`** — This is where the actual business logic for the feature lives, and it's the most important file to understand in this layer. The repository above only knows how to make individual API calls; this use case knows the *sequence* and the *rules*:
- calls `uploadJob` → gets a `job_id`
- calls `triggerProcessing`
- polls `getJobStatus` every 2 seconds, up to 60 times (2 minute business-rule timeout), until `"completed"` or `"failed"`
- calls `getJobMatches` and returns the result
- **always** deletes the temporary selfie/zip files from cache in a `finally` block, whether the job succeeded or failed

Returns `Result<List<MatchItem>>` so the ViewModel gets either a success value or a caught exception, never a crash. Splitting this out from the repository matters because "how the feature works end-to-end" (retry counts, timeout duration, cleanup) is a business decision, not a networking concern — if the matching strategy ever changes (e.g. an on-device path is added later), only this file needs a second implementation; the repository interface doesn't change.

### Presentation Layer

**`UploadUiState.kt`** — A sealed interface that represents every possible state the upload screen can be in:

```kotlin
sealed interface UploadUiState {
    object Idle       : UploadUiState   // nothing happening, form visible
    object Processing : UploadUiState   // upload + matching in progress
    data class Success(val matches: List<MatchItem>) : UploadUiState
    data class Error(val message: String) : UploadUiState
}
```

A sealed interface means there are no other possible states. The UI switches on this type and renders accordingly. This pattern eliminates an entire class of bugs where the UI gets into an undefined state (e.g. showing a loading spinner and an error message at the same time).

**`UploadViewModel.kt`** — Sits between the UI and the use case. It:
- Holds the current `UploadUiState` in a `MutableStateFlow` (a stream of values that Compose can observe)
- Converts the picked `Uri`s to `File`s via `FileHelper`, then calls `FindFacesInPhotosUseCase` on `viewModelScope` — no API calls or polling logic here, that all lives in the use case
- Updates the state to `Processing` while work is happening, then to `Success` or `Error` based on the result
- The ViewModel survives screen rotations (unlike an Activity). If you rotate your phone while processing, the UI reconnects to the same ViewModel and the work continues uninterrupted.

**`UploadScreen.kt`** — A Compose screen that observes `viewModel.uiState` and redraws whenever it changes:
- In `Idle` state: shows two file picker buttons and a submit button
- In `Processing` state: shows a loading indicator, buttons disabled
- In `Success` state: navigates to ResultsScreen
- In `Error` state: shows the error message

**`ResultsScreen.kt`** — Displays matched photos in a 2-column grid using `LazyVerticalGrid`. Each photo is loaded from the backend download URL using **Coil's `AsyncImage`**. Coil handles network fetching, disk caching, and displaying a placeholder while the image loads — all in one line of code.

---

## Dependency Injection with Hilt

**Why DI at all?** `UploadViewModel` needs a `JobRepository`. `JobRepositoryImpl` needs a `SnapFindApi` and a base URL string. `SnapFindApi` needs a `Retrofit` instance. Without DI, you would build this chain manually every time and have no control over singleton vs new instance.

Hilt is a DI framework that builds and manages this object graph for you.

**`NetworkModule.kt`** — Tells Hilt how to build the network layer:
- Provides a `Retrofit` singleton built with the backend base URL and Gson for JSON parsing
- Provides a `SnapFindApi` singleton created from that Retrofit instance
- Provides the base URL string as a `@Named("baseUrl")` value so the repository can prepend it to image URLs

**`RepositoryModule.kt`** — Tells Hilt that when something asks for a `JobRepository`, give it a `JobRepositoryImpl` singleton.

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

Retrofit is a type-safe HTTP client. You define an interface describing your API and Retrofit generates the implementation. Under the hood, it uses OkHttp to make actual network requests.

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
app/src/main/java/com/example/snapfindai/
├── di/
│   ├── NetworkModule.kt          # Provides Retrofit, SnapFindApi, base URL
│   └── RepositoryModule.kt       # Binds JobRepositoryImpl to JobRepository interface
├── data/
│   ├── remote/
│   │   └── SnapFindApi.kt        # Retrofit interface + all data classes
│   └── repository/
│       └── JobRepositoryImpl.kt  # One method per API call, no orchestration
├── domain/
│   ├── repository/
│   │   └── JobRepository.kt      # Interface (the contract the use case uses)
│   └── usecase/
│       └── FindFacesInPhotosUseCase.kt  # Owns the upload→process→poll→fetch business logic
├── presentation/
│   └── screens/
│       ├── upload/
│       │   ├── UploadScreen.kt   # Compose UI for file picking and submission
│       │   ├── UploadViewModel.kt # State management, calls the use case
│       │   └── UploadUiState.kt  # All possible UI states as a sealed interface
│       └── results/
│           └── ResultsScreen.kt  # Photo grid with Coil image loading
├── ui/theme/                     # Material 3 color, typography, theme setup
├── utils/
│   └── FileHelper.kt             # Converts Android URI to a File for Retrofit upload
├── MainActivity.kt               # Single activity, hosts Compose navigation
└── SnapFindApplication.kt        # Hilt application entry point (@HiltAndroidApp)
```

---

## Setup and running

### Prerequisites
- Android Studio Ladybug or newer
- JDK 11+
- Physical Android device or emulator (API 24+)
- The FastAPI backend running on the same Wi-Fi network as your device

### Steps

1. Clone the project and open the `SnapFindAI` folder in Android Studio
2. Wait for Gradle sync to complete
3. Open `NetworkModule.kt` and update `BASE_URL` to your laptop's local IP address:
   ```kotlin
   private const val BASE_URL = "http://YOUR_LAPTOP_IP:8000/"
   ```
   Find your laptop's IP with `ipconfig` (Windows) or `ifconfig` (Mac/Linux). Use the IPv4 address under your Wi-Fi adapter.
4. Make sure both your phone and laptop are on the same Wi-Fi network
5. Start the FastAPI backend with `uvicorn main:app --host 0.0.0.0 --port 8000`
6. Run the app on your device

### Why cleartext HTTP is allowed
`AndroidManifest.xml` contains `android:usesCleartextTraffic="true"`. This is required because the backend runs on plain HTTP (not HTTPS) on a local IP. For production, you would run the backend behind HTTPS and remove this flag.

---

## Tech stack

| Technology | Why it was chosen |
|---|---|
| **Kotlin** | Modern Android language, null safety, coroutines built-in |
| **Jetpack Compose** | Declarative UI — the screen automatically redraws when state changes, no manual view updates |
| **ViewModel + StateFlow** | Survives screen rotations, single source of truth for UI state |
| **Hilt** | Removes manual dependency wiring, makes code testable |
| **Retrofit** | Type-safe HTTP — you define an interface, Retrofit handles the network calls |
| **Coroutines** | Makes async code look sequential, no callback hell |
| **Coil** | Native Compose image loading with caching — loads images from URLs in one line |
| **Clean Architecture** | Separation of concerns — each layer has one job |

---

## Status & roadmap

Current state matches the Phase 1 checklist in `ROADMAP.md` (Compose UI, Retrofit integration, Hilt DI, Coil image loading — all done). No work has started yet on this app beyond that MVP.

Cross-project status (this app + the backend) is tracked in one place to avoid two docs drifting out of sync: see "Cross-project status" in `../Face_recognition/README.md`. Short version of what's open on the Android side specifically: the UI is still bare-bones and needs a redesign, and no hybrid/on-device matching work has started. Future phases (hybrid AI, Google Drive ingestion, event-driven backend) are in `ROADMAP.md`.