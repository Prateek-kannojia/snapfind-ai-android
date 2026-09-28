# SnapFind AI — Android Client

An Android app that finds you in a batch of event photos: pick a selfie and a ZIP of event photos, and the app matches faces **entirely on-device** — no upload, no server call.

**Full architecture walkthrough, layer-by-layer explanation, and code snippets:** [DEEP_DIVE.md](DEEP_DIVE.md)

Backend lives in [`../Face_recognition/`](../Face_recognition/) — no longer called by the app's active flow, but the on-device SDK (`:facesdk`) was built and validated against it across three separate phases before this app used it. Full story in DEEP_DIVE.md.

---

## User flow

1. Pick a selfie + a ZIP of event photos
2. Tap "Find My Photos" — unzips locally, matches on-device
3. Results screen shows every matched photo in a grid — tap one for a full-screen swipeable view, long-press to select multiple and remove or download them, or download all straight to the system gallery
4. Results survive closing the app — the last completed job reloads automatically on relaunch

## Architecture

Clean Architecture + MVVM, three layers, each only talks to the one below it:

```
Presentation (Compose, ViewModel)
      ↓
Domain (use case, repository interface)
      ↓
Data (repository impl, wraps :facesdk's FaceMatchEngine)
```

The use case (`FindFacesInPhotosUseCase`) owns the actual sequence: unzip → match on-device → clean up. `:facesdk` is a standalone Gradle module (no Compose/Hilt/Retrofit deps) with its own public API — see DEEP_DIVE.md for its facade + primitives shape.

## Tech stack

Kotlin · Jetpack Compose · Hilt (DI) · ONNX Runtime (on-device inference) · Coroutines + StateFlow · Coil (image loading) · Room (last-job persistence) · MediaStore (gallery save) · Clean Architecture · Retrofit (dormant server path, kept not deleted)

## Run it

1. Open in Android Studio, let Gradle sync (pulls in `:app` and `:facesdk`)
2. Run the app — no backend required for face matching. Debug builds bundle the models and work offline immediately; release builds download them once (~16MB) on first use instead, to keep the shipped APK small — see DEEP_DIVE.md.

## Project structure

```
app/src/main/java/com/example/snapfindai/
├── di/            # Hilt modules
├── data/          # repository implementations (active: on-device, Room, MediaStore; dormant: Retrofit)
├── domain/        # repository interfaces, domain models, use cases
├── presentation/  # Compose screens + ViewModels (upload, results, full-screen viewer)
└── utils/

facesdk/           # standalone on-device face-matching SDK, sibling module
```

Full layer-by-layer explanation, why each pattern was chosen, and code walkthroughs: **[DEEP_DIVE.md](DEEP_DIVE.md)**.
