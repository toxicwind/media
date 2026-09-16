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

import static com.google.common.truth.Truth.assertThat;

import android.os.Build;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.shadows.ShadowBuild;

/** Unit tests for {@link Pixel10FamilyDevice}. */
@RunWith(AndroidJUnit4.class)
public final class Pixel10FamilyDeviceTest {

  private String originalDevice;
  private String originalModel;
  private String originalProduct;

  @Before
  public void saveDevice() {
    originalDevice = Build.DEVICE;
    originalModel = Build.MODEL;
    originalProduct = Build.PRODUCT;
  }

  @After
  public void restoreDevice() {
    ShadowBuild.setDevice(originalDevice);
    ShadowBuild.setModel(originalModel);
    ShadowBuild.setProduct(originalProduct);
  }

  private static void fakeDevice(String device, String model, String product) {
    ShadowBuild.setDevice(device);
    ShadowBuild.setModel(model);
    ShadowBuild.setProduct(product);
  }

  @Test
  public void isPixel10FamilyDevice_pixel10Codenames_returnsTrue() {
    String[][] pixel10Devices = {
      {"frankel", "Pixel 10"},
      {"blazer", "Pixel 10 Pro"},
      {"mustang", "Pixel 10 Pro XL"},
      {"rango", "Pixel 10 Pro Fold"},
      {"stallion", "Pixel 10a"},
    };
    for (String[] pixel10Device : pixel10Devices) {
      fakeDevice(pixel10Device[0], pixel10Device[1], pixel10Device[0]);
      assertThat(Pixel10FamilyDevice.isPixel10FamilyDevice()).isTrue();
    }
  }

  @Test
  public void isPixel10FamilyDevice_pixel9Family_returnsFalse() {
    String[][] pixel9Devices = {
      {"tokay", "Pixel 9"},
      {"caiman", "Pixel 9 Pro"},
      {"komodo", "Pixel 9 Pro XL"},
      {"tegu", "Pixel 9a"},
      {"comet", "Pixel 9 Pro Fold"},
    };
    for (String[] pixel9Device : pixel9Devices) {
      fakeDevice(pixel9Device[0], pixel9Device[1], pixel9Device[0]);
      assertThat(Pixel10FamilyDevice.isPixel10FamilyDevice()).isFalse();
    }
  }

  @Test
  public void isPixel10FamilyDevice_nonPixelDevice_returnsFalse() {
    fakeDevice("a14x", "SM-A146P", "a14x");
    assertThat(Pixel10FamilyDevice.isPixel10FamilyDevice()).isFalse();
  }

  @Test
  public void isPixel10FamilyDevice_modelPrefixFallback_returnsTrue() {
    // A variant reporting an unknown codename but a Pixel 10 marketing name.
    fakeDevice("unknown_device", "Pixel 10 Pro XL", "unknown_product");
    assertThat(Pixel10FamilyDevice.isPixel10FamilyDevice()).isTrue();
  }

  @Test
  public void isPixel10FamilyDevice_productFallback_returnsTrue() {
    fakeDevice("unknown_device", "Unknown Model", "some pixel 10 variant");
    assertThat(Pixel10FamilyDevice.isPixel10FamilyDevice()).isTrue();
  }

  @Test
  public void isPixel10FamilyDevice_codenameCaseInsensitive_returnsTrue() {
    fakeDevice("MUSTANG", "Pixel 10 Pro XL", "MUSTANG");
    assertThat(Pixel10FamilyDevice.isPixel10FamilyDevice()).isTrue();
  }

  @Test
  public void shouldForceAvcCodecReinit_offPixel10Family_returnsFalse() {
    fakeDevice("tokay", "Pixel 9", "tokay");
    assertThat(
            Pixel10FamilyDevice.shouldForceAvcCodecReinit(
                avcFormat("avc1.64001F", 400_000), avcFormat("avc1.64001F", 800_000)))
        .isFalse();
  }

  @Test
  public void shouldForceAvcCodecReinit_pixel10_sameKeyDifferentBitrate_returnsTrue() {
    fakeDevice("mustang", "Pixel 10 Pro XL", "mustang");
    assertThat(
            Pixel10FamilyDevice.shouldForceAvcCodecReinit(
                avcFormat("avc1.64001F", 400_000), avcFormat("avc1.64001F", 800_000)))
        .isTrue();
  }

  @Test
  public void shouldForceAvcCodecReinit_pixel10_unequalKeys_returnsTrue() {
    fakeDevice("blazer", "Pixel 10 Pro", "blazer");
    assertThat(
            Pixel10FamilyDevice.shouldForceAvcCodecReinit(
                avcFormat("avc1.4D401F", 400_000), avcFormat("avc1.64002A", 2_000_000)))
        .isTrue();
  }

  @Test
  public void shouldForceAvcCodecReinit_pixel10_sameKeySameBitrate_returnsFalse() {
    fakeDevice("frankel", "Pixel 10", "frankel");
    assertThat(
            Pixel10FamilyDevice.shouldForceAvcCodecReinit(
                avcFormat("avc1.64001F", 400_000), avcFormat("avc1.64001F", 400_000)))
        .isFalse();
  }

  @Test
  public void shouldForceAvcCodecReinit_pixel10_unknownKeysOrBitrates_returnsFalse() {
    fakeDevice("rango", "Pixel 10 Pro Fold", "rango");
    // Malformed codec strings fail open.
    assertThat(
            Pixel10FamilyDevice.shouldForceAvcCodecReinit(
                avcFormat("avc1.6400", 400_000), avcFormat("avc1.6400", 800_000)))
        .isFalse();
    // Missing codec strings fail open.
    Format noCodecsOld =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setAverageBitrate(400_000)
            .build();
    Format noCodecsNew =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setAverageBitrate(800_000)
            .build();
    assertThat(Pixel10FamilyDevice.shouldForceAvcCodecReinit(noCodecsOld, noCodecsNew)).isFalse();
    // Unknown bitrates fail open.
    assertThat(
            Pixel10FamilyDevice.shouldForceAvcCodecReinit(
                avcFormatNoBitrate("avc1.64001F"), avcFormat("avc1.64001F", 800_000)))
        .isFalse();
    // Non-AVC formats fail open.
    Format hevcOld =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs("hvc1.1.6.L93.B0")
            .setAverageBitrate(400_000)
            .build();
    Format hevcNew =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs("hvc1.1.6.L93.B0")
            .setAverageBitrate(800_000)
            .build();
    assertThat(Pixel10FamilyDevice.shouldForceAvcCodecReinit(hevcOld, hevcNew)).isFalse();
  }

  private static Format avcFormat(String codecs, int bitrate) {
    return new Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_H264)
        .setCodecs(codecs)
        .setAverageBitrate(bitrate)
        .build();
  }

  private static Format avcFormatNoBitrate(String codecs) {
    return new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setCodecs(codecs).build();
  }
}
