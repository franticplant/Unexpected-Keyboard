// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 franticplant

package juloo.keyboard2;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import juloo.keyboard2.clipboard.ClipEntry;
import juloo.keyboard2.clipboard.ClipboardStore;

/**
 * Owns the system clipboard listener and hands entries to the store.
 *
 * The UI talks to [ClipboardStore] directly. This class stays small on purpose:
 * it is the only place that needs a [ClipboardManager] and the current
 * [Config].
 */
public final class ClipboardHistoryService
{
  public static void on_startup(Context ctx, ClipboardPasteCallback cb)
  {
    _paste_callback = cb;
    ClipboardStore store = ClipboardStore.init(ctx);
    Config config = Config.globalConfig();
    if (config != null)
    {
      store.set_enabled(config.clipboard_history_enabled);
      store.expire();
    }
    get_service(ctx);
    _service.add_current_clip();
  }

  public static ClipboardHistoryService get_service(Context ctx)
  {
    if (_service == null)
      _service = new ClipboardHistoryService(ctx);
    return _service;
  }

  public static ClipboardStore store(Context ctx)
  {
    return ClipboardStore.init(ctx);
  }

  public static void set_history_enabled(boolean enabled)
  {
    Config config = Config.globalConfig();
    if (config != null)
      config.set_clipboard_history_enabled(enabled);
    ClipboardStore store = ClipboardStore.get();
    if (store == null)
      return;
    store.set_enabled(enabled);
    if (enabled && _service != null)
      _service.add_current_clip();
  }

  /** Paste a text or image entry into the editor. */
  public static void paste(ClipEntry entry)
  {
    if (_paste_callback != null)
      _paste_callback.paste_from_clipboard_pane(entry);
  }

  static ClipboardHistoryService _service = null;
  static ClipboardPasteCallback _paste_callback = null;

  final ClipboardManager _cm;

  ClipboardHistoryService(Context ctx)
  {
    _cm = (ClipboardManager)ctx.getSystemService(Context.CLIPBOARD_SERVICE);
    if (_cm != null)
      _cm.addPrimaryClipChangedListener(this.new SystemListener());
  }

  /** Add what is currently in the system clipboard into the store. */
  void add_current_clip()
  {
    Config config = Config.globalConfig();
    if (config == null || !config.clipboard_history_enabled)
      return;
    if (config.editor_config != null && !config.editor_config.clipboard_enabled)
      return;
    if (_cm == null)
      return;
    ClipData clip = null;
    try { clip = _cm.getPrimaryClip(); }
    catch (Exception _e) { /* Not focused or permission denied. */ }
    if (clip == null)
      return;
    ClipboardStore store = ClipboardStore.get();
    if (store != null)
      store.ingest(clip, true);
  }

  final class SystemListener
    implements ClipboardManager.OnPrimaryClipChangedListener
  {
    public SystemListener() {}

    @Override
    public void onPrimaryClipChanged()
    {
      add_current_clip();
    }
  }

  public interface ClipboardPasteCallback
  {
    void paste_from_clipboard_pane(ClipEntry entry);
  }
}
