# Audit: AVC profile/level adaptation gating for ExoPlayer's DefaultTrackSelector

Date: 2026-09-15/16. All claims below are backed by tool output from the actual
machine (awrawr-pc); evidence lives next to this file.

## Problem

ExoPlayer's `DefaultTrackSelector` bundled video tracks with different AVC
profiles or levels into a single adaptive selection, as long as the MIME type
matched. On real ladders (e.g. Big Buck Bunny served with mixed
`avc1.42c00d` / `avc1.42c01e` / `avc1.640028` renditions) the player would try
to adapt between a Constrained Baseline level 1.3 stream and a High level 4.0
stream mid-playback.

## Change (primary patch)

`libraries/exoplayer/.../trackselection/DefaultTrackSelector.java`
- `VideoTrackInfo` now carries a nullable packed AVC profile/level key,
  `(profile_idc << 8) | level_idc`, parsed from the RFC 6381 codecs string.
- `isCompatibleForAdaptationWith` returns false when both tracks have known
  and unequal keys. Missing or malformed codec strings stay compatible
  (fail-open, no behavior change for non-AVC or unparseable tracks).
- `parseAvcProfileLevelKey` is a manual hex parser (no `String.split` regex)
  fronted by a bounded (256-entry) `ConcurrentHashMap` cache, since
  selections are rebuilt repeatedly with the same codec strings.
- Accepts `avc1` and `avc3`, upper/lowercase hex. Exact 11-char single codec
  strings only; comma-separated lists and the decimal `avc1.66.30` form return
  null (stay compatible).

Why not `CodecSpecificDataUtil.getCodecProfileAndLevel` (audited, deliberate
divergence, documented in the code):
- it only recognizes `avc1`/`avc2`, not `avc3`;
- it maps to MediaCodec profile/level constants rather than the raw pair;
- `getCodecProfileAndLevel` returns null for device-unsupported codecs, which
  would silently disable the gate depending on the device;
- it regex-splits and `Log.w`s on malformed input in the selection hot path.

## Change (Gate B, 2026-09-16) — Pixel 10 family AVC decoder workaround

Gate A stops mixed profile/level tracks from sharing one adaptive selection,
but issue #3185's reporter is explicit that the freeze happens on **any**
bitrate switch — SD to SD with the same AVC profile/level included. Gate B
covers that remainder.

- New `libraries/exoplayer/.../util/Pixel10FamilyDevice.java`:
  - `isPixel10FamilyDevice()` matches `Build.DEVICE` case-insensitively
    against `frankel` (Pixel 10), `blazer` (Pixel 10 Pro), `mustang`
    (Pixel 10 Pro XL), `rango` (Pixel 10 Pro Fold), `stallion` (Pixel 10a),
    with `Build.MODEL` ("Pixel 10" prefix) and `Build.PRODUCT` ("pixel 10"
    contains) fallbacks for variants. Deliberately excludes the Pixel 9
    family (`tokay`, `caiman`, `komodo`, `tegu`, `comet` — Tensor G4, not
    affected per the issue thread).
  - `shouldForceAvcCodecReinit(old, new)` returns true on Pixel 10 family
    devices when both formats carry known AVC profile/level keys and the keys
    are unequal, OR the keys are equal but both bitrates are known and differ.
    Unknown keys / unknown bitrates fail open (false). No decoder-name
    checks, no forced software decoding — the same decoder is reconfigured.
  - Owns the AVC profile/level parser (`parseAvcProfileLevelKey`), moved
    verbatim from `DefaultTrackSelector` so Gate A and Gate B share one
    implementation and one bounded cache. `DefaultTrackSelector`'s
    `parseAvcProfileLevelKey` is back to package-private and delegates;
    behavior is byte-identical (Gate A constraint holds).
- `DefaultTrackSelector.VideoTrackInfo.isCompatibleForAdaptationWith` now
  also requires `areAvcBitratesCompatibleForAdaptation`: on Pixel 10 family
  devices, same-key tracks with differing known bitrates are incompatible, so
  the selector keeps switch-prone tracks out of one adaptive selection.
  Off Pixel 10 this is a no-op (normal ABR); Gate A's known-unequal-key
  behavior is unchanged globally.
- `MediaCodecVideoRenderer.canReuseCodec` adds `DISCARD_REASON_WORKAROUND`
  when the shared helper says re-init is needed, turning the evaluation into
  `REUSE_RESULT_NO`. The existing machinery then drains and re-initializes
  the codec (`drainAndReinitializeCodec` → EOS + drain if buffers were
  received, else immediate re-init; `releaseCodec` + `maybeInitCodecOrBypass`).
  The output surface is not explicitly cleared, so the last frame should stay
  up during the brief stall — but issue evidence (the reporter's rejected
  workaround A, 2026-05-22) shows black frames can still occur on this path.
  The tradeoff is accepted: a brief stall/black frame instead of a permanent
  freeze with audio continuing.

Why device identity, not decoder component name: the issue thread names
`c2.android.avc.decoder` (2026-05-14) and `c2.google.avc.decoder`
(2026-07-22) for different failure reports, so component-name gating is
unreliable. Device identity (`Build.DEVICE`) is the stable signal.

Device-list provenance (source quality stated honestly):
- Google engineer Douglas Anderson's LKML device-tree series (2025-11-11):
  `frankel` = Pixel 10, `blazer` = Pixel 10 Pro, `mustang` = Pixel 10 Pro XL
  (primary source).
- Android Authority codename table: `rango` / RG5 = Pixel 10 Pro Fold,
  `stallion` / STA5 = Pixel 10a; Pixel 9 family exclusions `tokay`, `caiman`,
  `komodo`, `tegu`, `comet` (secondary press).
- GSMA device listing: Pixel 10 Pro XL model `GUL82` (industry database).
- TWRP device trees (`jsauce454`/`eychoong` `twrp_device_google_blazer`):
  Tensor G5 platform `deepspace` (community source).
- Android Authority leak relayed by blog-nouvelles-technologies.fr:
  first-generation Chips&Media WAVE677DV replaces the Samsung MFC/BigWave
  decoder stack on Tensor G5 (secondary/leak reporting — treated as
  attributed, not independently verified).

Protobuf search (2026-09-16): the only codec/device-relevant proto in the
tree is `demos/session_service/src/main/proto/preferences.proto`; no
codec/device configuration proto exists. Recorded, not blocking.

## Tests

`DefaultTrackSelectorTest` — 5 new tests:
1. mixed AVC profiles (`avc1.42001F` vs `avc1.64001F`, both orders) -> fixed
2. same profile, different levels (`avc1.64001F` vs `avc1.640028`) -> fixed
3. same profile+level, different bitrates -> adaptive, ordered (1, 0)
4. missing codec strings, different bitrates -> adaptive, ordered (1, 0)
5. Big Buck Bunny ladder: codec strings are *derived in-test* from real avcC
   box payloads (AVCDecoderConfigurationRecord bytes 1..3 -> `avc1.%02x%02x%02x`),
   asserted against known-good values, then used for the ladder assertions.

Big Buck Bunny provenance (verified downloads on this machine):
- Source: `https://archive.org/download/BigBuckBunny_328/BigBuckBunny_512kb.mp4`
  (the file referenced by GitHub `ab2525/ia-more_animation` via git-annex),
  43,315,070 bytes, H.264 Constrained Baseline `avc1`, 416x240, level 1.3,
  avcC -> `avc1.42c00d`.
- `bbb_baseline.mp4`: `ffmpeg -i ia_bbb.mp4 -t 12 -c:v libx264 -profile:v baseline
  -level 3.0 -pix_fmt yuv420p` -> `avc1.42c01e`.
- `bbb_high.mp4`: `ffmpeg -i ia_bbb.mp4 -t 12 -c:v libx264 -profile:v high
  -level 4.0 -pix_fmt yuv420p` -> `avc1.640028`.
- The full avcC payload hex for all three files is embedded in the test with
  the extraction command, so the codec strings are reproducible offline with
  no network dependency.

## Red / green evidence

- RED (unpatched `release` @ `8c6678b657ede1e7883fc164ef73ed483c7796c3`, new
  tests only, isolated worktree): job `20260915-213805-63d8` — 3 tests,
  3 failures, 0 errors. The unpatched selector produced adaptive selections
  where fixed selections were expected. (`evidence/red-20260915/`)
- GREEN (patched): job `20260915-213704-070a` and `20260915-213953-1254`,
  Java 21 — `DefaultTrackSelectorTest`: 132 tests, 0 failures;
  `DefaultTrackSelectorAvcBenchmarkTest`: 3 tests, 0 failures; exit code 0.
  (`evidence/green-20260915/`)

### Gate B red / green (2026-09-16)

- RED: isolated worktree `/home/toxic/gateb-red-20260916` at pre-Gate-B
  commit `d4cc9fec38` (production untouched) + the new Gate B tests and the
  standalone `Pixel10FamilyDevice` (its parser inlined, byte-identical to
  Gate A's, because `DefaultTrackSelector.parseAvcProfileLevelKey` is
  package-private on the baseline and unreachable cross-package).
  2026-09-16T15:10:20Z → 15:10:39Z: **27 tests, 3 failed**, all three
  exercising the new wiring and nothing else:
  1. `DefaultTrackSelectorTest.selectTracks_pixel10_sameAvcProfileLevelDifferentBitrates_selectsFixed`
  2. `MediaCodecVideoRendererTest.canReuseCodec_pixel10_unequalAvcKeys_returnsNo`
     (baseline `MediaCodecInfo.canReuseCodec` does not compare AVC
     profile/level for H.264 — only Dolby Vision gets a profile check —
     so the baseline reuses across `avc1.4D401F` → `avc1.64002A`)
  3. `MediaCodecVideoRendererTest.canReuseCodec_pixel10_sameAvcKeyDifferentBitrate_returnsNo`
  The other 24 (Gate A behavior locks, Pixel 9 / malformed / unknown-bitrate
  fail-open cases, all 11 `Pixel10FamilyDeviceTest` pure-unit tests, the
  predicate benchmark) pass on baseline as designed.
- GREEN run 1: 2026-09-16T15:11:01Z → 15:11:15Z, `testDebugUnitTest` with the
  same 27-test filter on branch `dts/avc-profile-level-adaptation-gating`
  @ `2c893a4ce7`: **27 tests, 0 failures, 0 errors, 0 skipped**
  (selector 11, renderer 4, device helper 11, benchmark 1).
- GREEN run 2: 2026-09-16T15:11:36Z → 15:11:42Z, full benchmark class +
  the same targeted filters @ `2c893a4ce7`: **30 tests, 0 failures,
  0 errors, 0 skipped** (selector 11, renderer 4, device helper 11,
  benchmark 4). Benchmark numbers below are from this run.
- GREEN run 3 (benchmark-only re-run, second data point):
  2026-09-16T15:11:58Z -> 15:12:03Z: **4 tests, 0 failures** -
  selectTracks **108.1 us/selection**; legacy regex **75.0 ns/op**;
  new manual (cold) **11.8 ns/op**; new cached (warm) **7.3 ns/op**;
  Gate B predicate **85.8 ns/op**.

## Benchmarks (secondary patch)

`DefaultTrackSelectorAvcBenchmarkTest` — timings print to stdout (in test logs).
Two green runs on awrawr-pc:

| parser | run 1 (ns/op) | run 2 (ns/op) |
|---|---|---|
| legacy regex split | 178.6 | 166.2 |
| new manual (cold) | 42.2 | 36.7 |
| new cached (warm) | 6.3 | 27.7 |

- The stable claim: manual parsing alone is ~4.5x faster than the regex split
  (37-42 ns/op vs 166-179 ns/op). The cache adds more on top but is noisy
  run-to-run in the test JVM; the test asserts warm < legacy, which held in
  both runs (28.3x and 6.0x).
- `selectTracks` over a 24-track mixed AVC ladder: 117.4 us then 100.2 us per
  selection (budget asserted < 50 ms). Those were Gate A-era runs; re-measured
  with Gate B in green run 2 (2026-09-16): **109.3 us/selection**.
- Gate B predicate benchmark (`pixel10GateB_canReuseCodecPredicate_latencyWithinBudget`,
  200,000 iterations, asserts < 1,000 ns/op): run 1 **37.4 ns/op**, run 2
  **53.8 ns/op**.
- Parser micro-benchmark re-measured with Gate B (green run 2, same JVM as the
  other benchmarks): legacy regex split **108.5 ns/op**, new manual (cold)
  **12.1 ns/op**, new cached (warm) **7.0 ns/op**; run 3: 75.0 / 11.8 / 7.3.
  Faster than the Gate A-era numbers (166-179 / 37-42 / 6.3-27.7) — JVM
  warmth variance; the stable claim remains "manual parse is several-x faster
  than the regex split and the cached path is single-digit ns".

## Build / formatting

- Repo has no Spotless config (verified: no `spotless` in any `*.gradle` /
  `*.gradle.kts`); formatting done with Google Java Format on all touched
  files. Gate A used 1.25.2; for Gate B 1.25.2 fails on this machine's JDK 25
  (`NoSuchMethodError` in `JavaInput.buildToks` — binary incompatibility, not
  a config issue), so **1.27.0** was used
  (`google-java-format-1.27.0-all-deps.jar` from Maven Central). Output for
  these files is the standard google-java-format style either way.
- Machine-tuned, user-level `~/.gradle/gradle.properties` on awrawr-pc
  (16 cores / 62 GB): `parallel=true`, `workers.max=16`, `caching=true`,
  `-Xmx12g`, `kotlin.parallel.tasks.in.project=true`. Pure performance knobs;
  the repo's tracked `gradle.properties` is untouched so the diff stays clean.
  (Gradle builds don't use the GPU; the RTX 3090 is irrelevant here.)

## Branch / commits (pushed, no PR)

Fork: `https://github.com/toxicwind/media`,
branch `dts/avc-profile-level-adaptation-gating`.

- 2026-09-15: initial work on `release` base (`8c6678b`) — gating
  implementation + functional tests, latency micro-benchmarks,
  `[branch-only]` README banner.
- 2026-09-16: rebased onto current upstream `main` (`d4f8639696`) via
  `git rebase --onto main 8c6678b657 dts/avc-profile-level-adaptation-gating` —
  6/6 commits replayed, zero conflicts, new HEAD `c51ef982a0`, force-pushed
  (feature branch only; `main` untouched).
- 2026-09-16: Gate B implemented on top (`d4cc9fec38` → `795dc611d7`
  production: new `Pixel10FamilyDevice`, selector + renderer wiring, parser
  moved verbatim into the helper with Gate A's package-private visibility
  restored; → `2c893a4ce7` tests: 4 selector + 4 renderer + 11 device-helper
  + 1 benchmark test). Pushed to origin, no PR.

Patches: `evidence/patches/primary-avc-gating.patch`,
`evidence/patches/secondary-avc-benchmark.patch`.

## Limitations / open questions

- The gate is currently global — no `Parameters` flag or device allowlist.
- Only exact 11-char `avc1.PPCCLL` / `avc3.PPCCLL` strings are parsed; codec
  lists and the decimal form stay compatible (fail-open).
- Cache is a static 256-entry map shared across selector instances.
- Microbenchmarks inside Robolectric/JUnit are noisy; numbers above are ranges
  across runs, not single-shot claims.
- Gate B limitations: device list is a hardcoded codename table (plus
  model/product fallbacks) — new Pixel 10 variants with unknown codenames and
  non-"Pixel 10" marketing names would miss the workaround (fail-open, same as
  Gate A). The workaround re-initializes the same hardware decoder; on a
  device where the firmware fix has shipped (Google: merged internally
  2026-08-17, future Pixel update) the extra re-inits are pure overhead
  (tens of ms per switch) with no benefit — no build-fingerprint gating was
  added. `isPixel10FamilyDevice` is evaluated per call (no caching) because
  tests fake `Build` fields; cost is a few string comparisons.
- The `api.txt` metalava surface at repo root lists only a curated subset of
  `androidx.media3.exoplayer.util` (DebugTextViewHelper, EventLogger); no
  build task consumes it for this module, so the new `Pixel10FamilyDevice`
  public class needs no `api.txt` entry. `DefaultTrackSelector`'s parser is
  back to package-private — no public API expansion from Gate B.

## Double audit (2026-09-16) — patch vs. tree + spec vs. reality

Two passes. Pass 1: the Gate A patch against the real tree on awrawr-pc
(worktree `/home/toxic/dts-readme-why-20260915`, branch
`dts/avc-profile-level-adaptation-gating`, tip `c51ef982a0`, tree clean).
Pass 2: the review-note specs and issue-thread claims against independent
sources (GitHub API for #3185 comments; web for device codenames).

### Pass 1 — code findings (all verified in-tree)

- Gate A present at the expected locations (`parseAvcProfileLevelKey` ~L3801,
  `areAvcCodecProfilesCompatibleForAdaptation` ~L4042, wired into
  `VideoTrackInfo.isCompatibleForAdaptationWith` ~L4021). Gist patch matches
  the branch's code hunks.
- Parser: exact 11-char check, dot at index 4, lowercase `avc1`/`avc3` prefix
  (case-sensitive per RFC 6381; an uppercase `AVC1.` prefix fails open to
  compatible — documented behavior, not a bug), `Character.digit` hex
  (upper/lower hex digits accepted), `(profile_idc << 8) | level_idc` packing.
- Fail-open verified by construction: multi-codec lists
  (`avc1.64002A,mp4a.40.2`), decimal `avc1.66.30`, missing `codecs`, non-AVC
  mime (guarded by the `MimeTypes.VIDEO_H264` check in
  `getAvcProfileLevelKey`) all return null → compatible. No behavior change
  for anything the gate can't read.
- Cache bound note: the 256-entry cap is checked via `size() < MAX` before
  `put` — under concurrent selection rebuilds two threads can both pass the
  check, so the bound is approximate, not hard. Harmless (a few entries over),
  but "bounded" should be read as "approximately bounded." No eviction policy;
  acceptable for 256 short strings, but a long-lived process with adversarial
  codec strings could hold 256+ entries indefinitely — still negligible memory.
- Tests present in-branch: mixed-profile, mixed-level, same-key adaptive,
  missing-codecs adaptive, and the avcC-box-derived BBB ladder tests
  (`DefaultTrackSelectorTest` ~L2175-2273); benchmark file present.
- **Not in the branch: Gate B.** No `isPixel10`, no `Build.MODEL` /
  `Build.DEVICE` references anywhere in `DefaultTrackSelector.java`. The
  device-aware code described in review notes was never implemented — the
  branch is Gate A only. Any message describing Gate B as shipped is
  describing a proposal, not the tree.

### Pass 2 — spec/claim findings

- Issue #3185 thread (via GitHub API, 12 comments, 2026-04-23 → 2026-08-17;
  raw JSON at `/home/toxic/gateb-20260916/comments/comments.json`, 24,996 bytes):
  reporter's Pixel 10 Pro XL GUL82 freeze on any bitrate switch; SW-decoder
  workaround; microkatz workarounds A (canReuseCodec override → black frame,
  rejected by reporter 2026-05-22) and B (disable `c2.android.avc.decoder` →
  software, shipped); holofermes raw-MediaCodec repro with `err 0xe/14` and
  `VPU_DecCompleteSeqInit` failures; microkatz 2026-08-17: root cause found,
  firmware fix merged internally, ships in a future Pixel update. The review
  notes' timeline checks out.
- **Device codenames — spec was wrong, corrected here.** Review-note drafts for
  Gate B listed `tokay` and `comet` as Pixel 10 devices. Independent sources
  (9to5google factory-image report; carrier-settings device tables; Pixel 10
  thermal-fix repos with per-codename profiles):
  - Pixel 10 = `frankel`, Pixel 10 Pro = `blazer`, Pixel 10 Pro XL = `mustang`,
    Pixel 10 Pro Fold = `rango`, Pixel 10a = `stallion` (all Tensor G5).
  - `tokay` = **Pixel 9**, `comet` = **Pixel 9 Pro Fold** (Tensor G4).
  A Gate B built from the draft spec would have hamstrung the Pixel 9 / 9 Pro
  Fold — the exact opposite of the reporter's "hamstring Pixel 10, not all
  devices" preference. This is why Gate B is held, not implemented.
- The "specific place in hell for DefaultTrackSelector.java" line does not
  appear in the GitHub issue comments; it is attributed to the reporter's
  Discord follow-ups, unverified in GitHub. Treated here as
  unattributed-Discord hearsay, not as issue evidence.
- Proprietary correction stands: the selector is Apache 2.0; the closed layers
  are the Tensor G5 VPU firmware and the reporter's production stack.

### Verdict

Gate A: verified in-tree, spec-honest, tests present, fail-open where it can't
read. Ship-shape as a correctness patch. It does not fix — and does not claim
to fix — the Pixel 10 firmware defect. Gate B: now implemented
(`795dc611d7` + `2c893a4ce7`) with the corrected device list — the draft spec's
`tokay`/`comet` error is fixed (they are exclusions, used as negative test
cases), identity is by `Build.DEVICE` (not decoder component name, which the
thread shows is unreliable), and neither gate forces software decoding. The
mitigation trades a brief re-init stall (possibly a black frame) for the
permanent freeze; it does not claim glitch-free switching.
