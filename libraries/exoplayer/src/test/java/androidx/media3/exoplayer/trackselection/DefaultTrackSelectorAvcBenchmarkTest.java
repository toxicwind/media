/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer.trackselection;

import static androidx.media3.exoplayer.RendererCapabilities.ADAPTIVE_SEAMLESS;
import static androidx.media3.exoplayer.RendererCapabilities.TUNNELING_NOT_SUPPORTED;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.when;

import android.content.Context;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.TrackSelector.InvalidationListener;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import androidx.media3.test.utils.FakeTimeline;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Micro-benchmarks for the AVC profile/level parsing used by {@link DefaultTrackSelector}'s
 * adaptation gating.
 *
 * <p>The pre-optimization parser (regex {@code String.split} per call) is reproduced here as {@link
 * #parseAvcProfileLevelKeyLegacy(String)} so the benchmark shows the latency difference the manual
 * parser plus cache makes. Timings are printed to stdout so they show in the test logs.
 */
@RunWith(AndroidJUnit4.class)
public final class DefaultTrackSelectorAvcBenchmarkTest {

  /** Codec strings observed on the real Big Buck Bunny ladder (see the BBB test fixture). */
  private static final String[] REAL_CODEC_STRINGS = {
    "avc1.64001f", "avc1.42000d", "avc1.420016", "avc1.640028", "avc1.42c01e", "avc1.42c00d"
  };

  private static final int PARSE_ITERATIONS = 200_000;
  private static final int SELECTION_ITERATIONS = 300;

  @Mock private InvalidationListener invalidationListener;
  @Mock private BandwidthMeter bandwidthMeter;

  private DefaultTrackSelector trackSelector;
  private MediaSource.MediaPeriodId periodId;

  /**
   * The regex-based parser as it existed before the latency optimization. Kept as the baseline the
   * benchmark compares against.
   */
  private static @Nullable Integer parseAvcProfileLevelKeyLegacy(String codecs) {
    String[] codecsParts = codecs.split("\\.");
    if (codecsParts.length != 2 || codecsParts[1].length() != 6) {
      return null;
    }
    try {
      int profileIdc = Integer.parseInt(codecsParts[1].substring(0, 2), 16);
      int levelIdc = Integer.parseInt(codecsParts[1].substring(4, 6), 16);
      return (profileIdc << 8) | levelIdc;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    when(bandwidthMeter.getBitrateEstimate()).thenReturn(1000000L);
    Context context = ApplicationProvider.getApplicationContext();
    trackSelector = new DefaultTrackSelector(context);
    trackSelector.init(invalidationListener, bandwidthMeter);
    FakeTimeline timeline = new FakeTimeline();
    periodId = new MediaSource.MediaPeriodId(timeline.getUidOfPeriod(/* periodIndex= */ 0));
  }

  @After
  public void tearDown() {
    trackSelector.release();
  }

  @Test
  public void avcProfileLevelKeyParsing_legacyAndNewAgree() {
    String[] inputs = {
      "avc1.64001f",
      "avc1.42c00d",
      "avc3.640028",
      "avc1.64001F",
      "avc1.6400",
      "avc1.64001f,mp4a.40.2",
      "mp4a.40.2",
      "avc1.6400zz",
      ""
    };
    for (String input : inputs) {
      assertThat(DefaultTrackSelector.parseAvcProfileLevelKey(input))
          .isEqualTo(parseAvcProfileLevelKeyLegacy(input));
    }
  }

  @Test
  public void avcProfileLevelKeyParsing_benchmarkShowsLatencyImprovement() {
    // Warm up the JIT.
    for (int i = 0; i < 10_000; i++) {
      parseAvcProfileLevelKeyLegacy(REAL_CODEC_STRINGS[i % REAL_CODEC_STRINGS.length]);
      DefaultTrackSelector.parseAvcProfileLevelKey(
          REAL_CODEC_STRINGS[i % REAL_CODEC_STRINGS.length]);
    }

    // Legacy: regex split on every call.
    long legacyStartNs = System.nanoTime();
    for (int i = 0; i < PARSE_ITERATIONS; i++) {
      parseAvcProfileLevelKeyLegacy(REAL_CODEC_STRINGS[i % REAL_CODEC_STRINGS.length]);
    }
    long legacyNs = System.nanoTime() - legacyStartNs;

    // New parser, cold: 256 distinct valid codec strings, each parsed once (cache misses).
    String[] coldStrings = new String[256];
    for (int i = 0; i < coldStrings.length; i++) {
      coldStrings[i] = String.format("avc1.6400%02x", i);
    }
    long coldStartNs = System.nanoTime();
    for (int i = 0; i < PARSE_ITERATIONS; i++) {
      DefaultTrackSelector.parseAvcProfileLevelKey(coldStrings[i % coldStrings.length]);
    }
    long coldNs = System.nanoTime() - coldStartNs;

    // New parser, warm: repeated codec strings (cache hits), the steady-state cost.
    long warmStartNs = System.nanoTime();
    for (int i = 0; i < PARSE_ITERATIONS; i++) {
      DefaultTrackSelector.parseAvcProfileLevelKey(
          REAL_CODEC_STRINGS[i % REAL_CODEC_STRINGS.length]);
    }
    long warmNs = System.nanoTime() - warmStartNs;

    double legacyPerOpNs = (double) legacyNs / PARSE_ITERATIONS;
    double coldPerOpNs = (double) coldNs / PARSE_ITERATIONS;
    double warmPerOpNs = (double) warmNs / PARSE_ITERATIONS;
    System.out.println(
        "AVC profile/level parse benchmark ("
            + PARSE_ITERATIONS
            + " iterations):\n"
            + String.format("  legacy (regex split) : %8.1f ns/op%n", legacyPerOpNs)
            + String.format("  new (manual, cold)   : %8.1f ns/op%n", coldPerOpNs)
            + String.format("  new (cached, warm)   : %8.1f ns/op%n", warmPerOpNs)
            + String.format("  speedup (warm)       : %8.1fx%n", legacyPerOpNs / warmPerOpNs));

    // The steady-state (cached) path must beat the legacy regex path.
    assertThat(warmNs).isLessThan(legacyNs);
  }

  @Test
  public void selectTracks_mixedAvcLadder_latencyWithinBudget() throws Exception {
    Format[] formats = new Format[24];
    for (int i = 0; i < formats.length; i++) {
      formats[i] =
          new Format.Builder()
              .setSampleMimeType(MimeTypes.VIDEO_H264)
              .setCodecs(REAL_CODEC_STRINGS[i % REAL_CODEC_STRINGS.length])
              .setWidth(320 + i * 16)
              .setHeight(240 + i * 9)
              .setAverageBitrate(200_000 + i * 100_000)
              .build();
    }
    TrackGroupArray trackGroups = new TrackGroupArray(new TrackGroup(formats));
    RendererCapabilities[] rendererCapabilities = {
      new FakeRendererCapabilities(C.TRACK_TYPE_VIDEO)
    };

    // Warm up.
    for (int i = 0; i < 10; i++) {
      trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, new FakeTimeline());
    }

    long startNs = System.nanoTime();
    for (int i = 0; i < SELECTION_ITERATIONS; i++) {
      trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, new FakeTimeline());
    }
    long totalNs = System.nanoTime() - startNs;
    double perSelectionUs = (double) totalNs / SELECTION_ITERATIONS / 1_000.0;
    System.out.println(
        String.format(
            "selectTracks benchmark: %d iterations over a 24-track mixed AVC ladder: %.1f"
                + " us/selection",
            SELECTION_ITERATIONS, perSelectionUs));

    // Generous budget: track selection must stay comfortably interactive.
    assertThat(perSelectionUs).isLessThan(50_000.0);
  }

  /** Minimal {@link RendererCapabilities} stub advertising seamless adaptation for video. */
  private static final class FakeRendererCapabilities implements RendererCapabilities {

    private final int trackType;

    FakeRendererCapabilities(int trackType) {
      this.trackType = trackType;
    }

    @Override
    public String getName() {
      return "FakeRenderer(" + Util.getTrackTypeString(trackType) + ")";
    }

    @Override
    public int getTrackType() {
      return trackType;
    }

    @Override
    public @Capabilities int supportsFormat(Format format) {
      return MimeTypes.getTrackType(format.sampleMimeType) == trackType
          ? RendererCapabilities.create(
              C.FORMAT_HANDLED, ADAPTIVE_SEAMLESS, TUNNELING_NOT_SUPPORTED)
          : RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
    }

    @Override
    public @AdaptiveSupport int supportsMixedMimeTypeAdaptation() {
      return ADAPTIVE_SEAMLESS;
    }
  }
}
