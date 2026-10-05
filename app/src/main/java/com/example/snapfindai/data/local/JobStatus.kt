package com.example.snapfindai.data.local

/**
 * Where a job got to. Stored as the enum's name rather than an ordinal, so
 * the column stays readable in a database dump and reordering this enum can't
 * silently reinterpret existing rows.
 *
 * There is deliberately no `Failed` or `Cancelled`: a job that fails or is
 * cancelled has nothing to show and nothing to resume, so its row is deleted
 * outright. That leaves [Running] meaning exactly one thing — "started, and
 * never reached an ending" — which is only reachable by the process dying
 * mid-job, since every in-app ending removes or completes the row. The
 * absence of cleanup *is* the signal that a job was interrupted.
 */
enum class JobStatus {
    Running,
    Complete,
}
