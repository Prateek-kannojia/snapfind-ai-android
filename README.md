# SnapFind AI — Android Client

An Android app that finds you in a batch of event photos: pick a selfie and a ZIP of event photos, the app sends them to a backend, and shows every photo where your face was detected.

**Full architecture walkthrough, layer-by-layer explanation, and code snippets:** [DEEP_DIVE.md](DEEP_DIVE.md)

Backend lives in [`../Face_recognition/`](../Face_recognition/).

---

## User flow

1. Pick a selfie + a ZIP of event photos
2. Tap "Find My Photos" — uploads both, triggers matching
3. App polls the backend every 2 seconds until done
4. Results screen shows every matched photo in a grid

## Architecture

Clean Architecture + MVVM, three layers, each only talks to the one below it:

```
Presentation (Compose, ViewModel)
      ↓
Domain (use case, repository interface)
      ↓
Data (Retrofit, repository impl)
```

The use case (`FindFacesInPhotosUseCase`) owns the actual sequence: upload → trigger processing → poll status → fetch matches. The repository only knows individual API calls — no orchestration logic lives there.

## Tech stack

Kotlin · Jetpack Compose · Hilt (DI) · Retrofit + OkHttp · Coroutines + StateFlow · Coil (image loading) · Clean Architecture

## Run it

1. Open in Android Studio, let Gradle sync
2. In `NetworkModule.kt`, set `BASE_URL` to your laptop's local IP
3. Start the backend: `uvicorn main:app --host 0.0.0.0 --port 8000` (phone + laptop on the same Wi-Fi)
4. Run the app

## Project structure

```
app/src/main/java/com/example/snapfindai/
├── di/            # Hilt modules
├── data/          # Retrofit API + repository implementation
├── domain/        # Repository interface + the use case (business logic)
├── presentation/  # Compose screens + ViewModels
└── utils/
```

Full layer-by-layer explanation, why each pattern was chosen, and code walkthroughs: **[DEEP_DIVE.md](DEEP_DIVE.md)**.
