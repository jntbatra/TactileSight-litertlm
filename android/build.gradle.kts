// Versions are pinned to what is already in the local Gradle cache so the build
// resolves with no network. The venue network is not dependable (TEAM.md).
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
}
