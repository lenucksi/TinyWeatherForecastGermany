# Release notes

## Android 13 (API 33) is now the minimum version

This fork now requires **Android 13 (API 33) or newer**. `minSdkVersion` in
`app/build.gradle` is 33; `targetSdkVersion` and `compileSdkVersion` stay at 36.

**Dropped device support:** phones and tablets running Android 12 (API 32) or
older can no longer install new builds of this fork. This is intentional: the
compatibility code needed for those versions is no longer maintained. Behaviour
for Android 13 and newer is unchanged.

Alongside the SDK change, test-only AndroidX Test dependency pins that existed
solely to keep the unit-test manifest compatible with API 19 were removed.
Robolectric test sources still declare SDK 26/28 variants, but the build now
enables only the API 33 and 36 variants: an app with `minSdkVersion 33` cannot be
loaded by the older Robolectric SDK images. This does not change behaviour on
Android 13+.

A live install/run check on an **API 33 (Android 13) emulator is still pending**:
no API 33 AVD is available in this environment (the current emulator image is
API 37), so this change is not claimed as verified on a real Android 13 device.

The original application remains Copyright (c) Pawel Dube and contributors;
this fork is AGPL-3.0-or-later (see COPYING and NOTICE).
