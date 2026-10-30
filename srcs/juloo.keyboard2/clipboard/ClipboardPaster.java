// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 franticplant

package juloo.keyboard2.clipboard;

import android.content.ClipDescription;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputContentInfo;
import android.widget.Toast;
import juloo.keyboard2.R;

/** Paste text, images and other content into the current editor. */
public final class ClipboardPaster
{
  private ClipboardPaster() {}

  /**
   * Paste [entry]. Returns [true] when the editor accepted the content through
   * [InputConnection.commitContent]. A [false] return means the fallback was
   * used (or the entry could not be pasted).
   */
  public static boolean paste(Context context, InputConnection conn, ClipEntry entry)
  {
    if (entry == null || conn == null)
      return false;
    if (entry.is_text())
    {
      conn.commitText(entry.text, 1);
      return true;
    }
    if (try_commit_content(context, conn, entry))
      return true;
    fallback(context, conn, entry);
    return false;
  }

  static boolean try_commit_content(Context context, InputConnection conn,
      ClipEntry entry)
  {
    if (Build.VERSION.SDK_INT < 25 || entry.file == null)
      return false;
    ClipboardStore store = ClipboardStore.get();
    if (store == null)
      return false;
    try
    {
      Uri uri = store.content_uri(entry);
      if (uri == null)
        return false;
      String mime = entry.mime == null ? "image/*" : entry.mime;
      ClipDescription description = new ClipDescription(
          entry.label == null ? "image" : entry.label, new String[]{mime});
      InputContentInfo info = new InputContentInfo(uri, description, null);
      return conn.commitContent(info,
          InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null);
    }
    catch (Exception _e)
    {
      return false;
    }
  }

  static void fallback(Context context, InputConnection conn, ClipEntry entry)
  {
    ClipboardStore store = ClipboardStore.get();
    if (store == null || !store.copy_to_system_clipboard(entry))
      return;
    // Apps that support image paste usually handle one of these. The toast
    // tells the user what happened when the app ignores both.
    if (Build.VERSION.SDK_INT >= 24)
      send_key(conn, KeyEvent.KEYCODE_PASTE, 0);
    else
      send_key(conn, KeyEvent.KEYCODE_V,
          KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON);
    Toast.makeText(context, R.string.clipboard_image_fallback,
        Toast.LENGTH_SHORT).show();
  }

  static void send_key(InputConnection conn, int code, int meta)
  {
    long now = SystemClock.uptimeMillis();
    try
    {
      conn.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0,
            meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE));
      conn.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0,
            meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE));
    }
    catch (Exception _e)
    {
      // The editor may have disconnected between the commit and the key.
    }
  }
}
