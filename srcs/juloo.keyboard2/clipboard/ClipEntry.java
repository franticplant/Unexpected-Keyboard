// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 franticplant

package juloo.keyboard2.clipboard;

import java.io.File;

/** One clipboard history entry. */
public final class ClipEntry
{
  public enum Kind { TEXT, IMAGE, URI }

  public final String id;
  public final Kind kind;
  /** Text content for [Kind.TEXT], otherwise [null]. */
  public final String text;
  /** Stored payload for [Kind.IMAGE]/[Kind.URI], otherwise [null]. */
  public File file;
  /** Thumbnail for [Kind.IMAGE], otherwise [null]. */
  public File thumb;
  public final String mime;
  public final String label;
  public final int width;
  public final int height;
  public final long size;
  public final long created_at;
  public final String sha256;

  public long last_used_at;
  public boolean pinned;

  public ClipEntry(String id, Kind kind, String text, File file, File thumb,
      String mime, String label, int width, int height, long size,
      long created_at, String sha256, boolean pinned)
  {
    this.id = id;
    this.kind = kind;
    this.text = text;
    this.file = file;
    this.thumb = thumb;
    this.mime = mime;
    this.label = label;
    this.width = width;
    this.height = height;
    this.size = size;
    this.created_at = created_at;
    this.sha256 = sha256;
    this.pinned = pinned;
    this.last_used_at = created_at;
  }

  public boolean is_text()
  {
    return kind == Kind.TEXT;
  }

  public boolean is_image()
  {
    return kind == Kind.IMAGE;
  }

  /** Display label for the list row. */
  public String title()
  {
    if (kind == Kind.TEXT)
      return text;
    if (label != null && label.length() > 0)
      return label;
    return is_image() ? "Image" : "Clipboard item";
  }
}
