# Roadmap & Future Enhancements

This roadmap outlines the path from the current MVP to a highly scalable, production-grade application system.

## Phase 1: MVP Android Client (Current Focus)
- [ ] Implement Jetpack Compose UI (Upload Screen, Progress Screen, Results Grid).
- [ ] Integrate Retrofit for API communication.
- [ ] Implement robust file selection and Multipart uploading for images and ZIP files.
- [ ] Implement Coil for rendering matched images.
- [ ] Setup Hilt for Dependency Injection.

## Phase 2: Hybrid AI & On-Device Processing
To save bandwidth and improve privacy, the app will introduce a hybrid processing model.
- **On-Device Matching (<200 photos):** If the user uploads a small batch of photos, use Google ML Kit for Face Detection and a TensorFlow Lite (TFLite) model to calculate cosine distance entirely on the phone.
- **Backend Matching (>200 photos):** Fallback to the FastAPI backend for heavy processing to prevent OOM (Out of Memory) errors and battery drain on the client device.

## Phase 3: Google Drive Integration (Progressive Auth)
Allow users to match faces directly from Google Drive without downloading the photos to their device.
- **Progressive Authentication:** No global login screen required. Use Google OAuth 2.0 *only* when a user clicks "Import from Google Drive".
- **Backend Streaming:** The Android app sends the Folder ID and OAuth token to the backend. The backend streams the photos into memory, runs the DeepFace model, and discards the image bytes without ever saving them to the backend storage.

## Phase 4: Asynchronous Backend Processing
Currently, the FastAPI backend processes images synchronously, which blocks the HTTP request and could lead to timeouts on the Android client for large ZIP files.
- **Workers:** Implement a background task queue (e.g., Celery, ARQ) to handle the DeepFace extraction.
- **SQS (Simple Queue Service):** Use a message broker to queue jobs robustly.

## Phase 5: Database & Search Optimization
- **pgvector:** Migrate the database to PostgreSQL and install the `pgvector` extension.
- Store the DeepFace 512-d embeddings directly in Postgres as vector types.
- Perform similarity searches using Postgres' native vector math (`ORDER BY embedding <-> '[...]'`).

## Phase 6: Event-Driven Architecture
- Transition to a fully Event-Driven Model.
- Publish domain events (e.g., `JobCreated`, `FaceMatched`, `JobFailed`) to an event bus.
