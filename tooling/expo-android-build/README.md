# Containerized Android build for an Expo / React Native app

Same idea as TactileSight's native build box, but for **Expo/RN**: an isolated image with
**Node + JDK 17 + Android SDK + Gradle**. Nothing installed on the host.
Pattern: **build the APK in the container, install from the host** (container never touches USB).

> Use the container to **build an APK**. For day-to-day **Expo Go** development
> (`npx expo start` → scan QR), just run Node on the host — no container needed.

## 1. Dockerfile

Save as `Dockerfile.expo` in the Expo project root:

```dockerfile
FROM eclipse-temurin:17-jdk-jammy

ENV ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_HOME=/opt/android-sdk \
    DEBIAN_FRONTEND=noninteractive
ENV PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools

# Node 20 + build deps
RUN apt-get update && apt-get install -y --no-install-recommends \
        curl unzip ca-certificates git && \
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get install -y --no-install-recommends nodejs && \
    rm -rf /var/lib/apt/lists/*

# Android SDK (+ NDK, which many RN native deps need)
ARG CMDLINE_TOOLS_VERSION=11076708
RUN mkdir -p ${ANDROID_SDK_ROOT}/cmdline-tools && \
    curl -fsSL -o /tmp/clt.zip \
        https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip && \
    unzip -q /tmp/clt.zip -d ${ANDROID_SDK_ROOT}/cmdline-tools && \
    mv ${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools ${ANDROID_SDK_ROOT}/cmdline-tools/latest && \
    rm /tmp/clt.zip && \
    yes | sdkmanager --licenses >/dev/null && \
    sdkmanager --install \
        "platform-tools" "platforms;android-34" "build-tools;34.0.0" \
        "ndk;26.1.10909125" >/dev/null

WORKDIR /workspace
```

## 2. Build the image (one-time)

```bash
docker build -t expo-android-build -f Dockerfile.expo .
```

## 3. Build the APK (from the Expo project root)

```bash
docker run --rm \
  -v "$(pwd):/workspace" \
  -v expo-gradle:/root/.gradle \
  -v expo-node:/workspace/node_modules \
  expo-android-build \
  bash -lc "npm ci && npx expo prebuild --platform android && cd android && ./gradlew assembleDebug"
```

APK lands at `android/app/build/outputs/apk/debug/app-debug.apk`.

## 4. Install from the host

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

## Notes

- **Prereqs:** Docker (user in `docker` group), host `adb`, phone with USB debugging authorized.
- The `expo-gradle` / `expo-node` named volumes cache Gradle + node_modules so rebuilds are fast.
- **Managed vs bare:** `expo prebuild` generates the native `android/` project from the managed
  config, then Gradle builds it. If the app is already bare (has an `android/` folder), skip
  prebuild and just run `./gradlew assembleDebug`.
- **Dev loop:** for `npx expo start` (Metro) you don't need this image — run it on the host with
  Node; scan the QR in **Expo Go**. The container is for producing installable builds.
- **EAS alternative:** `eas build -p android` builds in Expo's cloud (no local container). Use
  that if they'd rather not manage a local Android toolchain at all.
```
