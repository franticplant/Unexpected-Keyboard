// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 franticplant

package juloo.keyboard2.clipboard;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public class ClipboardUtilsTest
{
  @Test
  public void mimeExtensions()
  {
    assertEquals("png", ClipboardUtils.ext_for_mime("image/png"));
    assertEquals("jpg", ClipboardUtils.ext_for_mime("image/jpeg"));
    assertEquals("webp", ClipboardUtils.ext_for_mime("image/webp"));
    assertEquals("bin", ClipboardUtils.ext_for_mime("image/*"));
    assertEquals("bin", ClipboardUtils.ext_for_mime(null));
  }

  @Test
  public void byteFormatting()
  {
    assertEquals("12 B", ClipboardUtils.format_bytes(12));
    assertEquals("1 KiB", ClipboardUtils.format_bytes(1024));
    assertEquals("1.0 MiB", ClipboardUtils.format_bytes(1024 * 1024));
  }

  @Test
  public void dimensions()
  {
    assertEquals("1080 x 2400", ClipboardUtils.format_dimensions(1080, 2400));
    assertEquals("", ClipboardUtils.format_dimensions(0, 2400));
  }

  @Test
  public void ages()
  {
    long now = 1_700_000_000_000L;
    assertEquals("just now", ClipboardUtils.format_age(now - 5_000, now));
    assertEquals("5m ago", ClipboardUtils.format_age(now - 5 * 60_000, now));
    assertEquals("2h ago", ClipboardUtils.format_age(now - 2 * 3600_000, now));
    assertEquals("3d ago", ClipboardUtils.format_age(now - 3 * 86400_000L, now));
  }

  @Test
  public void textEntry()
  {
    ClipEntry e = new ClipEntry("id", ClipEntry.Kind.TEXT, "hello", null, null,
        "text/plain", "hello", 0, 0, 5, 1, "sha", false);
    assertTrue(e.is_text());
    assertFalse(e.is_image());
    assertEquals("hello", e.title());
  }

  @Test
  public void imageEntry()
  {
    ClipEntry e = new ClipEntry("id", ClipEntry.Kind.IMAGE, null, null, null,
        "image/png", "Screenshot", 10, 20, 30, 1, "sha", false);
    assertTrue(e.is_image());
    assertFalse(e.is_text());
    assertEquals("Screenshot", e.title());
  }

  @Test
  public void labelForTextCollapsesNewlines()
  {
    assertEquals("a b c", ClipboardStore.label_for_text("a\nb\r\nc"));
  }
}
