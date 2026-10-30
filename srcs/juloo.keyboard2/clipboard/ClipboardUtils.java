// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 franticplant

package juloo.keyboard2.clipboard;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Pure formatting helpers for the clipboard UI and tests. */
public final class ClipboardUtils
{
  public static String ext_for_mime(String mime)
  {
    if (mime == null)
      return "bin";
    switch (mime)
    {
      case "image/png": return "png";
      case "image/jpeg":
      case "image/jpg": return "jpg";
      case "image/gif": return "gif";
      case "image/webp": return "webp";
      case "image/heic":
      case "image/heif": return "heic";
      case "image/bmp": return "bmp";
      default: return "bin";
    }
  }

  public static String format_bytes(long bytes)
  {
    if (bytes < 1024)
      return bytes + " B";
    double kib = bytes / 1024.0;
    if (kib < 1024)
      return String.format(Locale.getDefault(), "%.0f KiB", kib);
    double mib = kib / 1024.0;
    if (mib < 1024)
      return String.format(Locale.getDefault(), "%.1f MiB", mib);
    return String.format(Locale.getDefault(), "%.1f GiB", mib / 1024.0);
  }

  public static String format_dimensions(int width, int height)
  {
    if (width <= 0 || height <= 0)
      return "";
    return width + " x " + height;
  }

  public static String format_age(long created_at, long now)
  {
    long seconds = Math.max(0, (now - created_at) / 1000);
    if (seconds < 60)
      return "just now";
    long minutes = seconds / 60;
    if (minutes < 60)
      return minutes + "m ago";
    long hours = minutes / 60;
    if (hours < 24)
      return hours + "h ago";
    long days = hours / 24;
    if (days < 7)
      return days + "d ago";
    return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
      .format(new Date(created_at));
  }

  /** A one-line detail string for a row. */
  public static String details(ClipEntry e)
  {
    StringBuilder b = new StringBuilder();
    if (e.is_image())
    {
      b.append(format_dimensions(e.width, e.height));
      if (b.length() > 0)
        b.append(" · ");
      b.append(format_bytes(e.size));
    }
    else
      b.append(format_bytes(e.size));
    if (b.length() > 0)
      b.append(" · ");
    b.append(format_age(e.created_at, System.currentTimeMillis()));
    return b.toString();
  }
}
