# SnapFind AI — Android Client

An Android app that finds you in a batch of event photos: pick a selfie and a ZIP of event photos, and the app matches faces **entirely on-device** — no upload, no server call.

**Full architecture walkthrough, layer-by-layer explanation, and code snippets:** [DEEP_DIVE.md](DEEP_DIVE.md)

Backend lives in [`../Face_recognition/`](../Face_recognition/) — no longer called by the app's active flow, but the on-device SDK (`:facesdk`) was built and validated against it across three separate phases before this app used it. Full story in DEEP_DIVE.md.

---

## User flow

1. Pick a selfie + a ZIP of event photos
2. Tap "Find My Photos" — unzips locally, matches on-device
3. Results screen shows every matched photo in a grid

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

Kotlin · Jetpack Compose · Hilt (DI) · ONNX Runtime (on-device inference) · Coroutines + StateFlow · Coil (image loading) · Clean Architecture · Retrofit (dormant server path, kept not deleted)

## Run it

1. Open in Android Studio, let Gradle sync (pulls in `:app` and `:facesdk`)
2. Run the app — no backend required for face matching

## Project structure

```
app/src/main/java/com/example/snapfindai/
├── di/            # Hilt modules
├── data/          # repository implementations (active: on-device; dormant: Retrofit)
├── domain/        # repository interfaces, domain models, the use case
├── presentation/  # Compose screens + ViewModels
└── utils/

facesdk/           # standalone on-device face-matching SDK, sibling module
```

Full layer-by-layer explanation, why each pattern was chosen, and code walkthroughs: **[DEEP_DIVE.md](DEEP_DIVE.md)**.
