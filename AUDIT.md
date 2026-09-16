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
  selection (budget asserted < 50 ms).

## Build / formatting

- Repo has no Spotless config (verified: no `spotless` in any `*.gradle` /
  `*.gradle.kts`); formatting done with Google Java Format 1.25.2 on all
  touched files.
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

Patches: `evidence/patches/primary-avc-gating.patch`,
`evidence/patches/secondary-avc-benchmark.patch`.

## Limitations / open questions

- The gate is currently global — no `Parameters` flag or device allowlist.
- Only exact 11-char `avc1.PPCCLL` / `avc3.PPCCLL` strings are parsed; codec
  lists and the decimal form stay compatible (fail-open).
- Cache is a static 256-entry map shared across selector instances.
- Microbenchmarks inside Robolectric/JUnit are noisy; numbers above are ranges
  across runs, not single-shot claims.

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

- Issue #3185 thread (via GitHub API, 11 comments, 2026-04-23 → 2026-08-17):
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
to fix — the Pixel 10 firmware defect. Gate B: not implemented, spec was wrong
on device identity, held pending confirmed device list + explicit approval.
This round's repo change is docs-only: the perspective section and this audit.
