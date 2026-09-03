# Wear OS companion app

A companion app for Wear OS that shows the current flight route and live navigation state from
Enroute Flight Navigation on the phone. Requested in
[issue #447](https://github.com/Akaflieg-Freiburg/enroute/issues/447).

This directory is a **standalone Gradle project**. It is not part of the Qt build: the top-level
`CMakeLists.txt` lists its subdirectories explicitly, so nothing here is picked up by CMake, and
`androiddeployqt` generates the phone app's Gradle project at build time. Building the watch app
needs a JDK and the Android SDK, but **no NDK and no Qt**, which is what makes it verifiable on a
machine that cannot build the phone app.

The wire protocol between the two is specified in
[`doc/companion-protocol.md`](../doc/companion-protocol.md). That document is normative; this app and
`src/companion/` on the phone side are both implementations of it.

## Why not the Wearable Data Layer

The usual way to build a Wear OS companion is `com.google.android.gms:play-services-wearable`. That
library is distributed under Google's proprietary Android SDK terms, and Enroute Flight Navigation is
GPL-3.0-or-later, so linking it would need an additional permission under GPL section 7 that only the
copyright holders can grant. It would also force the watch APK to carry the same applicationId and
the same signing key as the phone app.

The protocol therefore uses only what the phone app already links: an HTTP server over Qt HttpServer,
and Bluetooth Low Energy over Qt Bluetooth. Every dependency of this watch app is Apache-2.0 or
permissive, and the build actively refuses Play Services:

```kotlin
configurations.configureEach {
    exclude(group = "com.google.android.gms")
    exclude(group = "com.google.android.support", module = "wearable")
}
```

so a transitive dependency that pulled them in would fail the build rather than ship silently.

## Licence

GPL-3.0-or-later, the same as the rest of the repository, with the project's standard header block in
every file.

Strictly speaking this app is a separate program that talks to the phone over a documented protocol,
so it need not inherit the phone app's licence and could be permissive. Keeping it GPL-3.0-or-later
is a deliberate choice: the repository has one `LICENSE` and one header block everywhere, a second
licence inside the same tree is pure review and compliance overhead for a small team, and all
dependencies here are one-way compatible into GPLv3.

## Building

```bash
cd wearos && ./gradlew :app:assembleDebug
```

Point Gradle at the SDK once by creating `wearos/local.properties`:

```
sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
```

Use **forward slashes**, even on Windows. `local.properties` is a Java properties file, in which
a backslash is an escape character, so a path written with backslashes is silently mangled and the
build fails with the thoroughly unhelpful "filename, directory name, or volume label syntax is
incorrect". The file is git-ignored, so it stays a local setting.

## Installing

**Always pass `-s <serial>` to `adb`.** A bare `adb install`, or `gradlew installDebug` without
`ANDROID_SERIAL` set, targets every attached device — which on a development machine may include
phones, tablets or headsets that have no use for a watch APK.

```bash
adb devices -l
adb -s <serial> install -r wearos/app/build/outputs/apk/debug/app-debug.apk
```

## Testing without the phone app

`tools/mock-server.mjs` stands in for the phone. It serves the same documents with the same rounding
and the same field-omission rules, and flies a canned route so a client sees genuinely moving data.
Node standard library only — no `package.json`, nothing to install.

```bash
node wearos/tools/mock-server.mjs --gs 90 --units nm
```

Reach it with `adb reverse`, which works on the emulator **and** on a real watch, so the app can
target `127.0.0.1` in both cases:

```bash
adb -s <serial> reverse tcp:8973 tcp:8973
```

This also lets the transport be tested with the watch's Wi-Fi switched off, which separates transport
bugs from Wear OS's Wi-Fi power management. Note that UDP discovery does not traverse `adb reverse`;
testing that needs a real watch on the same network as the phone.

The mock logs one line per request, which is the quickest way to tell a client that is polling
happily from one that never connected at all.

Run `node wearos/tools/mock-server.mjs --help` for the fault-injection endpoints. They force each of
the five route-status values (and an unknown one, to prove a client's fallback), stall the feed to
exercise stale rendering, emit a frame with every optional key absent, and regenerate the route with
100 waypoints.

Its NOTAM fixtures are chosen to be awkward rather than tidy, because the happy path is not what
breaks a client: a permanent NOTAM with no end date, one already marked read, one with no affected
area, one whose text needs scrolling, and a category this build has never heard of. The default
four-waypoint route is arranged so that all four of the knowledge states appear at once — NOTAMs
listed, NOTAMs listed, nothing known, and confirmed empty. `debug/notams?m=cap` then adds the fifth
case, a group the document's cap emptied, which must never render as "no NOTAMs here".

## What the emulator can and cannot tell you

The Wear OS emulator is enough for layout, the status and freshness renderings, ambient-mode
transitions (`adb shell am broadcast -a com.google.android.wearable.action.ENTER_AMBIENT --ez
enter_ambient true`) and the HTTP transport over `adb reverse`.

It cannot answer anything about Bluetooth, Wi-Fi power management, battery life, thermal behaviour in
a sunlit cockpit, or readability through polarised sunglasses. Those need the real watch, and a green
emulator run says nothing about them.

## Transport staging

**Wi-Fi is the bench transport, not the flight transport.** Wear OS keeps Wi-Fi powered down while a
Bluetooth companion link to the phone exists, prefers that link for general traffic, and drops Wi-Fi
aggressively when the screen goes off. It works on a desk, and it works with the phone acting as a
hotspot at some cost in battery, but it is not something to rely on in the air.

Bluetooth Low Energy is the transport for actual flying. The HTTP transport exists because it can be
built and tested today, it needs no new dependency on either side, and it is useful in its own right
for any other client.
