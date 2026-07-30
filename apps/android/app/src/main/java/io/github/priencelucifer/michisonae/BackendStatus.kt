package io.github.priencelucifer.michisonae

internal enum class SnapshotFreshness {
    CURRENT,
    STALE,
    NOT_AVAILABLE,
}

internal data class BackendStatus(
    val connectionLabel: String,
    val uploadLabel: String,
    val snapshotLabel: String,
    val snapshotFreshness: SnapshotFreshness,
)

internal fun backendStatus(
    pendingUploadCount: Int,
    hasNetwork: Boolean,
    lastSyncFailed: Boolean,
    hasCachedSnapshot: Boolean,
    snapshotGeneratedAtMillis: Long?,
    nowMillis: Long,
): BackendStatus {
    require(pendingUploadCount >= 0)
    require(nowMillis >= 0)
    val freshness = when {
        !hasCachedSnapshot || snapshotGeneratedAtMillis == null ->
            SnapshotFreshness.NOT_AVAILABLE

        snapshotGeneratedAtMillis <= nowMillis &&
            nowMillis - snapshotGeneratedAtMillis <= 30 * 60 * 1_000L ->
            SnapshotFreshness.CURRENT

        else -> SnapshotFreshness.STALE
    }
    return BackendStatus(
        connectionLabel = when {
            !hasNetwork -> "Offline — saved hazard warnings remain available."
            lastSyncFailed -> "Sync delayed — saved hazard warnings remain available."
            else -> "Backend connected."
        },
        uploadLabel = if (pendingUploadCount == 0) {
            "All road reports uploaded."
        } else {
            "$pendingUploadCount road report${if (pendingUploadCount == 1) "" else "s"} waiting to upload."
        },
        snapshotLabel = when (freshness) {
            SnapshotFreshness.CURRENT -> "Nearby hazard data is current."
            SnapshotFreshness.STALE -> "Using older saved hazard data."
            SnapshotFreshness.NOT_AVAILABLE -> "No saved nearby hazard data yet."
        },
        snapshotFreshness = freshness,
    )
}
