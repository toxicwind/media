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
package androidx.media3.exoplayer.util;

import android.os.Build;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import java.util.Locale;

/**
 * RED-BASELINE VARIANT. Identical to the Gate B {@code Pixel10FamilyDevice} except that the AVC
 * profile/level parser is inlined instead of delegated to {@link
 * androidx.media3.exoplayer.trackselection.DefaultTrackSelector#parseAvcProfileLevelKey(String)},
 * which is package-private on this pre-Gate-B baseline and cannot be referenced from another
 * package. The inlined parse logic is byte-identical to Gate A's; production code is otherwise
 * untouched.
 */
public final class Pixel10FamilyDevice {

  /**
   * {@link Build#DEVICE} codenames of the Pixel 10 family (Tensor G5). Verified against Google
   * engineer Douglas Anderson's LKML device-tree series (2025-11-11) and the Android Authority
   * codename table: frankel = Pixel 10, blazer = Pixel 10 Pro, mustang = Pixel 10 Pro XL, rango =
   * Pixel 10 Pro Fold, stallion = Pixel 10a.
   *
   * <p>Deliberately excludes the Pixel 9 family (Tensor G4): tokay, caiman, komodo, tegu, comet.
   */
  private static final String[] PIXEL_10_FAMILY_DEVICE_CODENAMES = {
    "frankel", "blazer", "mustang", "rango", "stallion"
  };

  private Pixel10FamilyDevice() {}

  /**
   * Returns whether this is a Pixel 10 family device affected by the Tensor G5 hardware AVC decoder
   * freeze (issue #3185).
   */
  public static boolean isPixel10FamilyDevice() {
    String device = Build.DEVICE;
    if (device != null) {
      for (String codename : PIXEL_10_FAMILY_DEVICE_CODENAMES) {
        if (codename.equalsIgnoreCase(device)) {
          return true;
        }
      }
    }
    // Belt and braces for variants reporting a different codename: the marketing name and the
    // product string.
    String model = Build.MODEL;
    if (model != null && model.regionMatches(/* ignoreCase= */ true, 0, "Pixel 10", 0, 8)) {
      return true;
    }
    String product = Build.PRODUCT;
    return product != null && product.toLowerCase(Locale.ROOT).contains("pixel 10");
  }

  /**
   * Returns whether switching the decoder from {@code oldFormat} to {@code newFormat} must drain
   * and re-initialize instead of reusing the codec instance, on this device.
   *
   * <p>On Pixel 10 family devices this is true when both formats carry a known AVC profile/level
   * key and the keys are unequal, or the keys are equal but both bitrates are known and differ (the
   * "any switch freezes" behavior from issue #3185). Unknown keys or unknown bitrates fail open
   * (false), matching Gate A's fail-open stance. Software decoding is never forced: the same
   * decoder is reconfigured, so quality and battery behavior are unchanged apart from a brief
   * re-initialization stall.
   *
   * <p>Off Pixel 10 family devices this always returns false.
   */
  public static boolean shouldForceAvcCodecReinit(Format oldFormat, Format newFormat) {
    if (!isPixel10FamilyDevice()) {
      return false;
    }
    @Nullable Integer oldKey = getAvcProfileLevelKey(oldFormat);
    @Nullable Integer newKey = getAvcProfileLevelKey(newFormat);
    if (oldKey == null || newKey == null) {
      return false;
    }
    if (!oldKey.equals(newKey)) {
      return true;
    }
    int oldBitrate = oldFormat.bitrate;
    int newBitrate = newFormat.bitrate;
    return oldBitrate != Format.NO_VALUE
        && newBitrate != Format.NO_VALUE
        && oldBitrate != newBitrate;
  }

  /**
   * Returns the packed AVC profile/level key for the format, or null if it is not AVC or the key
   * cannot be determined. Inlined from Gate A's {@code DefaultTrackSelector.parseAvcProfileLevelKey}
   * (byte-identical) because that method is package-private on this baseline.
   */
  private static @Nullable Integer getAvcProfileLevelKey(Format format) {
    if (!MimeTypes.VIDEO_H264.equals(format.sampleMimeType) || format.codecs == null) {
      return null;
    }
    return parseAvcProfileLevelKey(format.codecs);
  }

  private static @Nullable Integer parseAvcProfileLevelKey(String codecs) {
    return parseAvcProfileLevelKeyUncached(codecs);
  }

  private static @Nullable Integer parseAvcProfileLevelKeyUncached(String codecs) {
    int dotIndex = codecs.indexOf('.');
    // "avc1." is 5 chars; PPCCLL is 6 hex chars.
    if (dotIndex != 4 || codecs.length() != 11) {
      return null;
    }
    if (!codecs.startsWith("avc1") && !codecs.startsWith("avc3")) {
      return null;
    }
    int profileIdc = parseHexByte(codecs, /* offset= */ 5);
    int levelIdc = parseHexByte(codecs, /* offset= */ 9);
    if (profileIdc == -1 || levelIdc == -1) {
      return null;
    }
    return (profileIdc << 8) | levelIdc;
  }

  /** Parses two hex chars at {@code offset} as a byte, or returns -1 if either is not hex. */
  private static int parseHexByte(String s, int offset) {
    int hi = Character.digit(s.charAt(offset), /* radix= */ 16);
    int lo = Character.digit(s.charAt(offset + 1), /* radix= */ 16);
    return (hi == -1 || lo == -1) ? -1 : (hi << 4) | lo;
  }
}
