> **Branch [`dts/avc-profile-level-adaptation-gating`](https://github.com/toxicwind/media/tree/dts/avc-profile-level-adaptation-gating)** — production-grade, PR-ready pending rebase onto current upstream `main`.

## Why: gate adaptive selections on AVC profile/level

### The problem this fixes

Adaptive playback breaks when ExoPlayer switches mid-stream between AVC renditions encoded at different H.264 profiles or levels. The Big Buck Bunny ladder does exactly this: it mixes renditions like `avc1.42c00d` (Baseline) and `avc1.640028` (High) in a single ladder. Every switch between such renditions forces the decoder to reconfigure itself mid-stream for a new profile/level — and many Android hardware decoders cannot do that seamlessly. What the viewer gets is a stall, corrupted frames, or a black flash while the codec is torn down and rebuilt, at precisely the moment the player was trying to adapt smoothly.

The track selector was at fault. `DefaultTrackSelector` grouped tracks into adaptive selections using a compatibility check keyed on MIME-type equality (`video/avc` == `video/avc`) plus a few capability signals — it never looked at the profile/level hiding inside each track's `avc1`/`avc3` codec string. So it happily handed the renderer a single "adaptive" set of mutually incompatible tracks, and ABR would pick switches the device couldn't execute.

### What this branch does

`DefaultTrackSelector` now parses the RFC 6381 codec string (`avc1.PPCCLL` / `avc3.PPCCLL`) into a packed key — `(profile_idc << 8) | level_idc` — and `VideoTrackInfo.isCompatibleForAdaptationWith` refuses to bundle tracks whose keys are both known and unequal. Mixed ladders still play; they now adapt *within* compatible profile/level families instead of across incompatible ones. The bad switches stop being offered in the first place.

### Design decisions

- **Conservative by default.** Tracks whose profile/level cannot be determined (missing or malformed codec string, non-AVC content) stay compatible — the gate only partitions selections where *both* tracks declare *differing* profiles or levels. Streams that don't declare are unaffected.
- **Codec string is the source of truth.** It is the only universally available signal; `sampleMimeType` does not carry profile/level.
- **No regex in the hot path.** `parseAvcProfileLevelKey` is a manual hex parse backed by a bounded (256-entry) `ConcurrentHashMap` cache, because track selections are rebuilt repeatedly with the same codec strings. It deliberately does *not* reuse `CodecSpecificDataUtil.getCodecProfileAndLevel`: that API only recognizes `avc1`/`avc2` (not `avc3`), returns MediaCodec profile/level constants instead of the raw idc pair this gate compares, returns null for device-unsupported codecs (which would silently disable the gate depending on the device), and logs on malformed input in the selection hot path.
- **Measured, not assumed.** Latency micro-benchmarks (`DefaultTrackSelectorAvcBenchmarkTest`): manual parse ~37–42ns vs legacy regex ~166–179ns (~4.5x faster); `selectTracks` end-to-end ~100–117µs.

### Evidence

- 132 functional tests + 3 benchmark tests green across 2 runs (exit 0), base release `8c6678b` (1.11.1).
- Red baseline preserved: all 3 new behavioral tests fail on the unpatched base.
- The Big Buck Bunny ladder test derives codec strings in-test from real `avcC` box payloads (embedded payloads, not full MP4s).
- Full audit, PR description, patches, benchmark numbers, and the red-baseline notes are mirrored in the companion gist.

### Status

- **Production-grade.** 132 functional tests + 3 benchmark tests green across 2 runs (exit 0); red baseline preserved (all 3 new behavioral tests fail unpatched); latency measured, not assumed.
- **Not yet proposed upstream.** Base is release `8c6678b`; proposing it needs a rebase/port onto current `main`, plus upstream review of whether level-up adaptation should ever be allowed when the decoder supports it.
- Keyed on the codec string only: wrong or missing codec strings fall back to previous behavior rather than blocking playback.
- No PR is open from this branch yet; the branch stays push-ready.

# AndroidX Media

## Perspective: the bug trap, the product question, and what this patch is not

Added 2026-09-16, after re-reading the full issue #3185 thread (11 comments via
GitHub API, 2026-04-23 → 2026-08-17) and the reporter's follow-ups. This section
does not change the code; it records the framing the patch is written against,
so the next person doesn't fall into the same trap.

### Two definitions of "fix" collided

The thread runs two different definitions of "fix" at once:

1. **Product definition** (the reporter's): stop the freeze on Pixel 10 without
   degrading every other device — "hamstring Pixel 10, not all devices."
   Workaround A (`canReuseCodec=false` on Pixel 10) was rejected by the reporter
   because it caused black frames; workaround B (force software decode) shipped
   but costs quality and battery. The desired behavior is that the device stops
   failing on codec-level switches — most other devices handle them fine.
2. **Correctness definition** (this patch's): the selector should never bundle
   mutually incompatible AVC profiles/levels into one adaptive selection in the
   first place. The Big Buck Bunny ladder proves the selector bug exists
   independent of any one device.

Gate A serves definition 2, globally. It is not a Pixel 10 workaround.

### The firmware defect is separate — and already fixed upstream

The Pixel 10 freeze is a firmware defect, not a selector defect: the Tensor G5
VPU (`google.hardware.media.c2@3.0-service`) fails `VPU_DecCompleteSeqInit`
(`FAILED TO DEC_PIC_HDR`, SEQERR 20480 = SPS/PPS reject) when the stream's
profile/level changes, surfacing as MediaCodec error `0xe`/`14`. Google's media
codec team identified the root cause and merged a firmware fix internally on
2026-08-17 (microkatz on #3185); it ships in a future Pixel update. No ExoPlayer
patch can fix firmware — Gate A reduces the *selector's* contribution (it stops
offering cross-profile switches), but on unpatched Pixel 10 firmware the VPU can
still fail on any reconfigure. That is Google's bug to ship, not ours to paper
over.

### The bug trap: four arguments, one thread

The discussion kept mixing four separate arguments without labeling them:

1. the firmware defect (VPU state machine),
2. the selector correctness bug (this patch),
3. the product workaround choice (which devices to hamstring, and how),
4. the philosophy layer — shipping a fix here is a negotiation with Google and
   the device vendors, not just a diff.

Arguing (4) as if it were (1), or "fixing" (2) while believing you've fixed
(1), is the trap. This patch fixes (2). It deliberately does not touch (1)
or (3).

### Proprietary, correctly attributed

`DefaultTrackSelector.java` is Apache 2.0 open source — it is not the
proprietary code in this story. The actual proprietary layers are the Pixel 10
VPU firmware/driver in `/vendor` and the reporter's production playback stack.
Aim the fix at the layer you can actually change; don't ask an open-source
selector to compensate for a closed firmware state machine beyond what
correctness already requires.

### Gate B: proposed, audited, NOT implemented

A device-aware follow-up ("Gate B": Pixel 10-only stricter gating, matching the
reporter's product preference) was specified in review notes — and the double
audit below caught the spec gating the **wrong devices**: one draft listed
`tokay` (Pixel 9, Tensor G4) and another listed `comet` (Pixel 9 Pro Fold),
neither of which is a Pixel 10. The verified Tensor G5 / Pixel 10 set is
`frankel` (Pixel 10), `blazer` (Pixel 10 Pro), `mustang` (Pixel 10 Pro XL),
`rango` (Pixel 10 Pro Fold), `stallion` (Pixel 10a). Gate B stays unimplemented
until the device list is confirmed and explicitly approved — and with the
firmware fix already merged internally, the product question belongs to the
exo-team proposal discussion, not to this correctness patch.

AndroidX Media is a collection of libraries for implementing media use cases on
Android, including local playback (via ExoPlayer), video editing (via
Transformer) and media sessions.

## Documentation

*   The [developer guide] provides a wealth of information.
*   The [class reference] documents the classes and methods.
*   The [release notes] document the major changes in each release.
*   The [media dev center] provides samples and guidelines.
*   Follow our [developer blog] to keep up to date with the latest developments!

[developer guide]: https://developer.android.com/guide/topics/media/media3
[class reference]: https://developer.android.com/reference/androidx/media3/common/package-summary
[release notes]: RELEASENOTES.md
[media dev center]: https://developer.android.com/media
[developer blog]: https://medium.com/google-exoplayer

## Migration for existing ExoPlayer and MediaSession projects

You'll find a [migration guide for existing ExoPlayer and MediaSession users] on
developer.android.com.

[migration guide for existing ExoPlayer and MediaSession users]: https://developer.android.com/guide/topics/media/media3/getting-started/migration-guide

## API stability

AndroidX Media releases provide API stability guarantees, ensuring that the API
surface remains backwards compatible for the most commonly used APIs. APIs
intended for more advanced use cases are marked as unstable. To use an unstable
method or class without lint warnings, you’ll need to add the OptIn annotation
before using it. For more information see the [UnstableApi] documentation.

[UnstableApi]: https://github.com/androidx/media/blob/main/libraries/common/src/main/java/androidx/media3/common/util/UnstableApi.java

## Using the libraries

You can get the libraries from [the Google Maven repository]. It's also possible
to clone this GitHub repository and depend on the modules locally.

[the Google Maven repository]: https://developer.android.com/studio/build/dependencies#google-maven

### From the Google Maven repository

#### 1. Add module dependencies

The easiest way to get started using AndroidX Media is to add Gradle
dependencies on the libraries you need in the `build.gradle.kts` file of your
app module.

For example, to depend on ExoPlayer with DASH playback support and UI components
you can add dependencies on the modules like this:

```kotlin
implementation("androidx.media3:media3-exoplayer:1.X.X")
implementation("androidx.media3:media3-exoplayer-dash:1.X.X")
implementation("androidx.media3:media3-ui:1.X.X")
```

Or in Gradle Groovy DSL `build.gradle`:

```groovy
implementation 'androidx.media3:media3-exoplayer:1.X.X'
implementation 'androidx.media3:media3-exoplayer-dash:1.X.X'
implementation 'androidx.media3:media3-ui:1.X.X'
```

where `1.X.X` is your preferred version. All modules must be the same version.

Please see the [AndroidX Media3 developer.android.com page] for more
information, including a full list of library modules.

This repository includes some modules that depend on external libraries that
need to be built manually, and are not available from the Maven repository.
Please see the individual READMEs under the [libraries directory] for more
details.

[AndroidX Media3 developer.android.com page]: https://developer.android.com/jetpack/androidx/releases/media3#declaring_dependencies
[libraries directory]: libraries

#### 2. Turn on Java 8 support

If not enabled already, you also need to turn on Java 8 support in all
`build.gradle.kts` files depending on AndroidX Media, by adding the following to
the `android` section:

```kotlin
compileOptions {
  targetCompatibility = JavaVersion.VERSION_1_8
}
```

Or in Gradle Groovy DSL `build.gradle`:

```groovy
compileOptions {
    targetCompatibility JavaVersion.VERSION_1_8
}
```

### Locally

Cloning the repository and depending on the modules locally is required when
using some libraries. It's also a suitable approach if you want to make local
changes, or if you want to use the `main` branch.

First, clone the repository into a local directory:

```sh
git clone https://github.com/androidx/media.git
```

Next, add the following to your project's `settings.gradle.kts` file, replacing
`path/to/media` with the path to your local copy:

```kotlin
import androidx.media3.buildlogic.includeMedia3

pluginManagement {
  includeBuild("path/to/media/build-logic-settings")
  // your other plugins
}
plugins {
  id("gradlebuild.media3-settings-logic")
  // your other plugins
}

includeMedia3(file("path/to/media"))
```

Or in Gradle Groovy DSL `settings.gradle`:

```groovy
pluginManagement {
    includeBuild('path/to/media/build-logic-settings')
}

plugins {
    id 'gradlebuild.media3-settings-logic'
}

includeMedia3.execute(file('path/to/media'))
```

The AndroidX Media checkout will now appear as a separate included build
side-by-side with your main project. You can depend on the individual modules
from your app's `build.gradle.kts` as you would on any other module. Gradle will
resolve those seemingly published releases as a local checkout.

If your project depends on a particular version of Media3 (here: 1.X.X), it will
be completely ignored, so you can leave your build files intact. For example:

```kotlin
implementation("androidx.media3:media3-exoplayer:1.X.X")
implementation("androidx.media3:media3-exoplayer-dash:1.X.X")
implementation("androidx.media3:media3-ui:1.X.X")
```

Or in Gradle Groovy DSL `build.gradle`:

```groovy
implementation 'androidx.media3:media3-exoplayer:1.X.X'
implementation 'androidx.media3:media3-exoplayer-dash:1.X.X'
implementation 'androidx.media3:media3-ui:1.X.X'
```

If you are getting `NDK not configured` error, check that
`path/to/media/local.properties` has `sdk.dir` variable set. It will be
autogenerated and populated if you open `path/to/media` project in your Android
Studio.

#### MIDI module

By default, the [MIDI module](libraries/decoder_midi) is disabled as a local
dependency, because it requires additional Maven repository config. If you want
to use it as a local dependency, please configure the JitPack repository as
[described in the module README](libraries/decoder_midi/README.md#getting-the-module),
and then enable building the module in by modifying `modules.kt` file:

```kotlin
Media3Module("libraries/decoder_midi", "media3-exoplayer-midi", includeInCompositeBuild = true)
```

## Developing AndroidX Media

#### Project branches

Development work happens on the `main` branch. Pull requests should normally be
made to this branch.

The `release` branch holds the most recent stable release.

#### Using Android Studio

To develop AndroidX Media using Android Studio, simply open the project in the
root directory of this repository.
