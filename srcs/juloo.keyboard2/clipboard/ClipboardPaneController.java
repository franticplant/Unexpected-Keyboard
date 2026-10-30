// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 franticplant

package juloo.keyboard2.clipboard;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import juloo.keyboard2.ClipboardHistoryService;
import juloo.keyboard2.Config;
import juloo.keyboard2.R;
import juloo.keyboard2.Utils;
import juloo.keyboard2.clipboard.ClipboardStore.Filter;

/** Wires the clipboard pane: history toggle, filters, list and actions. */
public final class ClipboardPaneController implements ClipboardStore.Listener
{
  final Context _context;
  final View _root;
  final ClipboardStore _store;
  final ListView _list;
  final TextView _empty;
  final CheckBox _history;
  final ClipboardAdapter _adapter;
  final Button[] _filter_buttons;
  Filter _filter = Filter.ALL;

  public ClipboardPaneController(Context context, View root)
  {
    _context = context;
    _root = root;
    _store = ClipboardStore.init(context);
    _list = (ListView)root.findViewById(R.id.clipboard_list);
    _empty = (TextView)root.findViewById(R.id.clipboard_empty);
    _history = (CheckBox)root.findViewById(R.id.clipboard_history_toggle);
    _filter_buttons = new Button[] {
      (Button)root.findViewById(R.id.clipboard_filter_all),
      (Button)root.findViewById(R.id.clipboard_filter_text),
      (Button)root.findViewById(R.id.clipboard_filter_images),
      (Button)root.findViewById(R.id.clipboard_filter_pinned)
    };
    _adapter = new ClipboardAdapter();
    _list.setAdapter(_adapter);
    _list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
      public void onItemClick(AdapterView<?> parent, View view, int position, long id)
      {
        ClipboardHistoryService.paste(_adapter.entry(position));
      }
    });
    _list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
      public boolean onItemLongClick(AdapterView<?> parent, View view,
          int position, long id)
      {
        show_actions(_adapter.entry(position));
        return true;
      }
    });
    for (int i = 0; i < _filter_buttons.length; i++)
    {
      final Filter f = Filter.values()[i];
      _filter_buttons[i].setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) { set_filter(f); }
      });
    }
    Config config = Config.globalConfig();
    boolean enabled = config != null && config.clipboard_history_enabled;
    _history.setChecked(enabled);
    _history.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
      public void onCheckedChanged(CompoundButton button, boolean checked)
      {
        ClipboardHistoryService.set_history_enabled(checked);
        refresh();
      }
    });
    root.findViewById(R.id.clipboard_clear).setOnClickListener(
        new View.OnClickListener() {
          public void onClick(View v) { show_clear(); }
        });
    _store.set_listener(this);
    set_filter(Filter.ALL);
  }

  @Override
  public void on_clipboard_changed()
  {
    refresh();
  }

  public void refresh()
  {
    if (Looper.myLooper() != Looper.getMainLooper())
    {
      new Handler(Looper.getMainLooper()).post(new Runnable() {
        public void run() { refresh(); }
      });
      return;
    }
    List<ClipEntry> entries = _store.query(_filter, null);
    _adapter.set_entries(entries);
    if (!entries.isEmpty())
    {
      _empty.setVisibility(View.GONE);
      return;
    }
    if (!_store.enabled() && _filter != Filter.PINNED)
      _empty.setText(R.string.clipboard_history_off);
    else if (_filter == Filter.PINNED)
      _empty.setText(R.string.clipboard_empty_pinned);
    else
      _empty.setText(R.string.clipboard_empty);
    _empty.setVisibility(View.VISIBLE);
  }

  void set_filter(Filter filter)
  {
    _filter = filter;
    for (int i = 0; i < _filter_buttons.length; i++)
      _filter_buttons[i].setAlpha(i == filter.ordinal() ? 1f : 0.45f);
    refresh();
  }

  void show_actions(final ClipEntry entry)
  {
    if (entry == null)
      return;
    String[] items;
    if (entry.pinned)
      items = new String[] {
        _context.getString(R.string.clipboard_copy),
        _context.getString(R.string.clipboard_unpin),
        _context.getString(R.string.clipboard_share),
        _context.getString(R.string.clipboard_delete)
      };
    else
      items = new String[] {
        _context.getString(R.string.clipboard_copy),
        _context.getString(R.string.clipboard_pin),
        _context.getString(R.string.clipboard_share),
        _context.getString(R.string.clipboard_delete)
      };
    AlertDialog dialog = new AlertDialog.Builder(_context)
      .setTitle(R.string.clipboard_item_actions)
      .setItems(items, new DialogInterface.OnClickListener() {
        public void onClick(DialogInterface d, int which)
        {
          switch (which)
          {
            case 0: copy(entry); break;
            case 1: toggle_pin(entry); break;
            case 2: _store.share(entry); break;
            case 3: confirm_delete(entry); break;
          }
        }
      })
      .create();
    show(dialog);
  }

  void copy(ClipEntry entry)
  {
    boolean ok = _store.copy_to_system_clipboard(entry);
    toast(ok ? R.string.clipboard_copied : R.string.clipboard_copy_failed);
  }

  void toggle_pin(ClipEntry entry)
  {
    if (!_store.toggle_pin(entry))
      toast(R.string.clipboard_could_not_pin);
  }

  void confirm_delete(final ClipEntry entry)
  {
    AlertDialog dialog = new AlertDialog.Builder(_context)
      .setTitle(R.string.clipboard_delete)
      .setMessage(R.string.clipboard_remove_confirm)
      .setNegativeButton(android.R.string.cancel, null)
      .setPositiveButton(R.string.clipboard_remove_confirmed,
          new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which)
            {
              _store.remove(entry);
            }
          })
      .create();
    show(dialog);
  }

  void show_clear()
  {
    String[] items = new String[] {
      _context.getString(R.string.clipboard_clear_unpinned),
      _context.getString(R.string.clipboard_clear_pinned)
    };
    AlertDialog dialog = new AlertDialog.Builder(_context)
      .setTitle(R.string.clipboard_clear_unpinned)
      .setItems(items, new DialogInterface.OnClickListener() {
        public void onClick(DialogInterface d, int which)
        {
          if (which == 0)
            confirm_clear(false);
          else
            confirm_clear(true);
        }
      })
      .create();
    show(dialog);
  }

  void confirm_clear(final boolean pinned)
  {
    int msg = pinned ? R.string.clipboard_clear_pinned_confirm
      : R.string.clipboard_clear_unpinned_confirm;
    AlertDialog dialog = new AlertDialog.Builder(_context)
      .setMessage(msg)
      .setNegativeButton(android.R.string.cancel, null)
      .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
        public void onClick(DialogInterface d, int which)
        {
          if (pinned)
            _store.clear_pinned();
          else
            _store.clear_unpinned();
        }
      })
      .create();
    show(dialog);
  }

  void show(AlertDialog dialog)
  {
    IBinder token = _root.getWindowToken();
    if (token != null)
      Utils.show_dialog_on_ime(dialog, token);
    else
      dialog.show();
  }

  void toast(int res)
  {
    Toast.makeText(_context, res, Toast.LENGTH_SHORT).show();
  }

  final class ClipboardAdapter extends BaseAdapter
  {
    List<ClipEntry> _entries = new ArrayList<ClipEntry>();

    void set_entries(List<ClipEntry> entries)
    {
      _entries = entries;
      notifyDataSetChanged();
    }

    ClipEntry entry(int pos)
    {
      return pos >= 0 && pos < _entries.size() ? _entries.get(pos) : null;
    }

    @Override
    public int getCount() { return _entries.size(); }
    @Override
    public Object getItem(int pos) { return entry(pos); }
    @Override
    public long getItemId(int pos)
    {
      ClipEntry e = entry(pos);
      return e == null ? 0 : e.id.hashCode();
    }

    @Override
    public View getView(int pos, View v, ViewGroup parent)
    {
      if (v == null)
        v = View.inflate(_context, R.layout.clipboard_entry, null);
      final ClipEntry e = entry(pos);
      ImageView thumb = (ImageView)v.findViewById(R.id.clipboard_entry_thumb);
      TextView title = (TextView)v.findViewById(R.id.clipboard_entry_title);
      TextView details = (TextView)v.findViewById(R.id.clipboard_entry_details);
      ImageView pin = (ImageView)v.findViewById(R.id.clipboard_entry_pin);
      ImageView delete = (ImageView)v.findViewById(R.id.clipboard_entry_delete);
      if (e == null)
        return v;
      title.setText(e.title());
      details.setText(ClipboardUtils.details(e));
      if (e.is_image())
      {
        thumb.setVisibility(View.VISIBLE);
        ThumbnailLoader.load(thumb, e);
      }
      else
      {
        thumb.setVisibility(View.GONE);
        thumb.setImageDrawable(null);
        thumb.setTag(null);
      }
      pin.setAlpha(e.pinned ? 1f : 0.35f);
      pin.setContentDescription(_context.getString(
            e.pinned ? R.string.clipboard_unpin : R.string.clipboard_pin));
      pin.setOnClickListener(new View.OnClickListener() {
        public void onClick(View view) { toggle_pin(e); }
      });
      delete.setOnClickListener(new View.OnClickListener() {
        public void onClick(View view) { confirm_delete(e); }
      });
      return v;
    }
  }

  static final class ThumbnailLoader
  {
    static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(32);
    static final ExecutorService EXEC = Executors.newFixedThreadPool(2);
    static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void load(final ImageView view, final ClipEntry entry)
    {
      if (entry.thumb == null || !entry.thumb.exists())
      {
        view.setTag(null);
        view.setImageResource(R.drawable.ic_clipboard_paste);
        return;
      }
      final String key = entry.thumb.getAbsolutePath();
      Bitmap cached = CACHE.get(key);
      if (cached != null)
      {
        view.setTag(key);
        view.setImageBitmap(cached);
        return;
      }
      view.setTag(key);
      view.setImageResource(R.drawable.ic_clipboard_paste);
      EXEC.execute(new Runnable() {
        public void run()
        {
          final Bitmap bmp = BitmapFactory.decodeFile(key);
          MAIN.post(new Runnable() {
            public void run()
            {
              if (!key.equals(view.getTag()))
                return;
              if (bmp == null)
                view.setImageResource(R.drawable.ic_clipboard_paste);
              else
              {
                CACHE.put(key, bmp);
                view.setImageBitmap(bmp);
              }
            }
          });
        }
      });
    }
  }
}
