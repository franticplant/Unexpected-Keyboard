// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 franticplant

package juloo.keyboard2.clipboard;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import juloo.keyboard2.Config;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Clipboard history with an explicit persistence split:
 *
 * - Unpinned entries live in memory. Unpinned images live in cacheDir and are
 *   deleted when the store starts.
 * - Pinned entries are written to files/clipboard/pinned.json and their image
 *   bytes live in files/clipboard/.
 *
 * There is no background service and no network.
 */
public final class ClipboardStore
{
  private static final String TAG = "ClipboardStore";
  private static final String PINNED_FILE = "pinned.json";
  private static final String PINNED_PREFS = "pinned_clipboards";
  private static final String PINNED_PREF_KEY = "pinned";

  public static final int MAX_UNPINNED = 50;
  public static final int MAX_PINNED = 100;
  public static final long MAX_IMAGE_BYTES = 20L * 1024 * 1024;
  public static final long MAX_PINNED_BYTES = 200L * 1024 * 1024;
  static final int THUMB_MAX = 256;

  public enum Filter { ALL, TEXT, IMAGES, PINNED }

  public static interface Listener
  {
    void on_clipboard_changed();
  }

  // This is the application context, not an Activity; the singleton lives for
  // the process lifetime.
  @SuppressLint("StaticFieldLeak")
  static ClipboardStore _instance;

  final Context _context;
  final File _files_dir;
  final File _cache_dir;
  final File _pinned_file;
  final File _files_images;
  final File _files_thumbs;
  final File _cache_images;
  final File _cache_thumbs;
  final Handler _handler;
  final ExecutorService _worker = Executors.newSingleThreadExecutor(
      new ThreadFactory() {
        public Thread newThread(Runnable r)
        {
          Thread t = new Thread(r, "clipboard-store");
          t.setDaemon(true);
          return t;
        }
      });
  final List<ClipEntry> _entries = new ArrayList<ClipEntry>();

  Listener _listener;
  boolean _enabled = true;

  public static synchronized ClipboardStore init(Context context)
  {
    if (_instance == null)
      _instance = new ClipboardStore(context);
    return _instance;
  }

  public static ClipboardStore get()
  {
    return _instance;
  }

  ClipboardStore(Context context)
  {
    this(context.getFilesDir(), context.getCacheDir(),
        context.getApplicationContext(), true);
  }

  /** Test constructor: no Android services, only file storage. */
  ClipboardStore(File files_root, File cache_root)
  {
    this(files_root, cache_root, null, false);
  }

  ClipboardStore(File files_root, File cache_root, Context context,
      boolean android)
  {
    _context = context;
    _handler = android ? new Handler(Looper.getMainLooper()) : null;
    _files_dir = new File(files_root, "clipboard");
    _cache_dir = new File(cache_root, "clipboard");
    _pinned_file = new File(_files_dir, PINNED_FILE);
    _files_images = new File(_files_dir, "images");
    _files_thumbs = new File(_files_dir, "thumbs");
    _cache_images = new File(_cache_dir, "images");
    _cache_thumbs = new File(_cache_dir, "thumbs");
    ensure_dir(_files_images);
    ensure_dir(_files_thumbs);
    delete_tree(_cache_dir);
    ensure_dir(_cache_images);
    ensure_dir(_cache_thumbs);
    load_pinned();
    migrate_old_pins();
  }

  public void set_listener(Listener listener)
  {
    _listener = listener;
  }

  public void set_enabled(boolean enabled)
  {
    synchronized (this)
    {
      _enabled = enabled;
      if (!enabled)
        clear_unpinned_locked();
    }
    notify_changed();
  }

  public boolean enabled()
  {
    return _enabled;
  }

  public List<ClipEntry> entries()
  {
    synchronized (this)
    {
      expire_locked();
      return new ArrayList<ClipEntry>(_entries);
    }
  }

  public List<ClipEntry> query(Filter filter, String search)
  {
    synchronized (this)
    {
      expire_locked();
      String q = search == null ? "" : search.trim().toLowerCase(Locale.getDefault());
      ArrayList<ClipEntry> out = new ArrayList<ClipEntry>();
      for (ClipEntry e : _entries)
      {
        if (filter == Filter.TEXT && !e.is_text())
          continue;
        if (filter == Filter.IMAGES && !e.is_image())
          continue;
        if (filter == Filter.PINNED && !e.pinned)
          continue;
        if (q.length() > 0 && !matches(e, q))
          continue;
        out.add(e);
      }
      Collections.sort(out, new java.util.Comparator<ClipEntry>() {
        public int compare(ClipEntry a, ClipEntry b)
        {
          if (a.pinned != b.pinned)
            return a.pinned ? -1 : 1;
          return Long.compare(b.created_at, a.created_at);
        }
      });
      return out;
    }
  }

  static boolean matches(ClipEntry e, String q)
  {
    if (e.title() != null
        && e.title().toLowerCase(Locale.getDefault()).contains(q))
      return true;
    return e.text != null
      && e.text.toLowerCase(Locale.getDefault()).contains(q);
  }

  public ClipEntry find(String id)
  {
    synchronized (this)
    {
      for (ClipEntry e : _entries)
        if (e.id.equals(id))
          return e;
    }
    return null;
  }

  /** Ingest every usable item in [clip]. Called from the clipboard listener. */
  public void ingest(ClipData clip, boolean allow)
  {
    if (clip == null || !allow || !_enabled)
      return;
    expire();
    ClipDescription desc = clip.getDescription();
    for (int i = 0; i < clip.getItemCount(); i++)
    {
      ClipData.Item item = clip.getItemAt(i);
      CharSequence text = item.getText();
      if (text != null && text.toString().trim().length() > 0)
        add_text(text.toString(), false);
      Uri uri = item.getUri();
      if (uri == null)
        continue;
      String mime = desc != null ? desc.getMimeType(i) : null;
      if (mime == null)
      {
        try { mime = _context.getContentResolver().getType(uri); }
        catch (Exception _e) { mime = null; }
      }
      if (mime == null || !mime.startsWith("image/"))
        continue;
      final Uri uri_ = uri;
      final String mime_ = mime;
      final String label = desc != null ? desc.getLabel().toString() : null;
      _worker.execute(new Runnable() {
        public void run() { ingest_image(uri_, mime_, label); }
      });
    }
  }

  void ingest_image(Uri uri, String mime, String label)
  {
    if (_context == null || !_enabled)
      return;
    String id = UUID.randomUUID().toString();
    String ext = ClipboardUtils.ext_for_mime(mime);
    File file = new File(_cache_images, id + "." + ext);
    String sha;
    long size;
    try
    {
      CopyResult r = copy_uri(uri, file);
      if (r == null)
      {
        file.delete();
        return;
      }
      sha = r.sha;
      size = r.size;
    }
    catch (Exception e)
    {
      file.delete();
      Log.w(TAG, "Could not copy clipboard image: " + e.getClass().getSimpleName());
      return;
    }
    BitmapFactory.Options bounds = decode_bounds(file);
    if (bounds == null)
    {
      file.delete();
      return;
    }
    File thumb = new File(_cache_thumbs, id + ".jpg");
    make_thumb(file, thumb, bounds.outWidth, bounds.outHeight);
    final ClipEntry entry = new ClipEntry(id, ClipEntry.Kind.IMAGE, null,
        file, thumb, mime, label == null ? "Image" : label,
        bounds.outWidth, bounds.outHeight, size, System.currentTimeMillis(),
        sha, false);
    if (_handler != null)
      _handler.post(new Runnable() {
        public void run() { add_image(entry); }
      });
    else
      add_image(entry);
  }

  public void add_text(String text, boolean pinned)
  {
    if (text == null || text.trim().length() == 0)
      return;
    synchronized (this)
    {
      expire_locked();
      ClipEntry duplicate = null;
      for (ClipEntry e : _entries)
      {
        if (e.is_text() && text.equals(e.text))
        {
          duplicate = e;
          break;
        }
      }
      if (duplicate != null)
      {
        touch_locked(duplicate);
        notify_changed();
        return;
      }
      long now = System.currentTimeMillis();
      ClipEntry e = new ClipEntry(UUID.randomUUID().toString(),
          ClipEntry.Kind.TEXT, text, null, null, "text/plain",
          label_for_text(text), 0, 0, text.getBytes(StandardCharsets.UTF_8).length,
          now, sha256(text.getBytes(StandardCharsets.UTF_8)), pinned);
      _entries.add(0, e);
      enforce_limits_locked();
      if (pinned)
        persist_pinned_locked();
    }
    notify_changed();
  }

  void add_image(ClipEntry e)
  {
    synchronized (this)
    {
      if (!_enabled)
      {
        delete_entry_files(e);
        return;
      }
      expire_locked();
      ClipEntry duplicate = null;
      for (ClipEntry old : _entries)
      {
        if (old.is_image() && old.sha256 != null && old.sha256.equals(e.sha256))
        {
          duplicate = old;
          break;
        }
      }
      if (duplicate != null)
      {
        delete_entry_files(e);
        touch_locked(duplicate);
        notify_changed();
        return;
      }
      _entries.add(0, e);
      enforce_limits_locked();
    }
    notify_changed();
  }

  /** Pin an entry. Returns [false] when the operation cannot be completed. */
  public boolean pin(ClipEntry e)
  {
    if (e == null || e.pinned)
      return e != null;
    synchronized (this)
    {
      if (pinned_count_locked() >= MAX_PINNED)
        return false;
      if (e.is_image())
      {
        if (total_pinned_bytes_locked() + e.size > MAX_PINNED_BYTES)
          return false;
        File file = move_to(e.file, _files_images,
            e.id + "." + ClipboardUtils.ext_for_mime(e.mime));
        if (file == null)
          return false;
        File thumb = e.thumb == null ? null :
          move_to(e.thumb, _files_thumbs, e.id + ".jpg");
        e.file = file;
        e.thumb = thumb;
      }
      e.pinned = true;
      persist_pinned_locked();
    }
    notify_changed();
    return true;
  }

  public boolean unpin(ClipEntry e)
  {
    if (e == null || !e.pinned)
      return e != null;
    synchronized (this)
    {
      if (e.is_image() && e.file != null)
      {
        File file = move_to(e.file, _cache_images,
            e.id + "." + ClipboardUtils.ext_for_mime(e.mime));
        if (file == null)
          return false;
        File thumb = e.thumb == null ? null :
          move_to(e.thumb, _cache_thumbs, e.id + ".jpg");
        e.file = file;
        e.thumb = thumb;
      }
      e.pinned = false;
      persist_pinned_locked();
    }
    notify_changed();
    return true;
  }

  public boolean toggle_pin(ClipEntry e)
  {
    return e.pinned ? unpin(e) : pin(e);
  }

  public void remove(ClipEntry e)
  {
    if (e == null)
      return;
    synchronized (this)
    {
      _entries.remove(e);
      delete_entry_files(e);
      persist_pinned_locked();
    }
    notify_changed();
  }

  public void clear_unpinned()
  {
    synchronized (this)
    {
      clear_unpinned_locked();
    }
    notify_changed();
  }

  void clear_unpinned_locked()
  {
    Iterator<ClipEntry> it = _entries.iterator();
    while (it.hasNext())
    {
      ClipEntry e = it.next();
      if (!e.pinned)
      {
        it.remove();
        delete_entry_files(e);
      }
    }
  }

  public void clear_pinned()
  {
    synchronized (this)
    {
      Iterator<ClipEntry> it = _entries.iterator();
      while (it.hasNext())
      {
        ClipEntry e = it.next();
        if (e.pinned)
        {
          it.remove();
          delete_entry_files(e);
        }
      }
      persist_pinned_locked();
    }
    notify_changed();
  }

  public void clear_all()
  {
    synchronized (this)
    {
      for (ClipEntry e : _entries)
        delete_entry_files(e);
      _entries.clear();
      persist_pinned_locked();
    }
    notify_changed();
  }

  /** Drop expired unpinned entries. */
  public void expire()
  {
    boolean changed;
    synchronized (this)
    {
      changed = expire_locked();
    }
    if (changed)
      notify_changed();
  }

  boolean expire_locked()
  {
    int ttl = ttl_minutes();
    if (ttl < 0)
      return false;
    long cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(ttl);
    boolean changed = false;
    Iterator<ClipEntry> it = _entries.iterator();
    while (it.hasNext())
    {
      ClipEntry e = it.next();
      if (!e.pinned && e.created_at < cutoff)
      {
        it.remove();
        delete_entry_files(e);
        changed = true;
      }
    }
    return changed;
  }

  int ttl_minutes()
  {
    Config c = Config.globalConfig();
    return c == null ? 5 : c.clipboard_history_duration;
  }

  void enforce_limits_locked()
  {
    int unpinned = 0;
    for (ClipEntry e : _entries)
      if (!e.pinned)
        unpinned++;
    for (int i = _entries.size() - 1; i >= 0 && unpinned > MAX_UNPINNED; i--)
    {
      ClipEntry e = _entries.get(i);
      if (e.pinned)
        continue;
      _entries.remove(i);
      delete_entry_files(e);
      unpinned--;
    }
  }

  int pinned_count_locked()
  {
    int n = 0;
    for (ClipEntry e : _entries)
      if (e.pinned)
        n++;
    return n;
  }

  long total_pinned_bytes_locked()
  {
    long n = 0;
    for (ClipEntry e : _entries)
      if (e.pinned)
        n += e.size;
    return n;
  }

  void touch_locked(ClipEntry e)
  {
    _entries.remove(e);
    e.last_used_at = System.currentTimeMillis();
    _entries.add(0, e);
  }

  void notify_changed()
  {
    if (_handler == null)
      return;
    _handler.post(new Runnable() {
      public void run()
      {
        Listener l = _listener;
        if (l != null)
          l.on_clipboard_changed();
      }
    });
  }

  // ---------------------------------------------------------------- storage

  void persist_pinned_locked()
  {
    try
    {
      ensure_dir(_files_dir);
      JSONObject root = new JSONObject();
      root.put("version", 1);
      JSONArray arr = new JSONArray();
      for (ClipEntry e : _entries)
      {
        if (!e.pinned)
          continue;
        JSONObject o = new JSONObject();
        o.put("id", e.id);
        o.put("kind", e.is_text() ? "text" : "image");
        o.put("created_at", e.created_at);
        o.put("label", e.label == null ? "" : e.label);
        o.put("size", e.size);
        if (e.is_text())
          o.put("text", e.text);
        else
        {
          o.put("file", relative(_files_dir, e.file));
          if (e.thumb != null)
            o.put("thumb", relative(_files_dir, e.thumb));
          o.put("mime", e.mime);
          o.put("width", e.width);
          o.put("height", e.height);
          o.put("sha256", e.sha256);
        }
        arr.put(o);
      }
      root.put("entries", arr);
      write_atomic(_pinned_file, root.toString(2));
    }
    catch (Exception e)
    {
      Log.w(TAG, "Could not persist pinned entries: " + e.getClass().getSimpleName());
    }
  }

  void load_pinned()
  {
    if (!_pinned_file.exists())
      return;
    try
    {
      String raw = read_file(_pinned_file);
      JSONObject root = new JSONObject(raw);
      JSONArray arr = root.optJSONArray("entries");
      if (arr == null)
        return;
      for (int i = 0; i < arr.length(); i++)
      {
        JSONObject o = arr.optJSONObject(i);
        if (o == null)
          continue;
        String kind = o.optString("kind", "text");
        long created = o.optLong("created_at", System.currentTimeMillis());
        String label = o.optString("label", "");
        long size = o.optLong("size", 0);
        if ("text".equals(kind))
        {
          String text = o.isNull("text") ? null : o.optString("text", null);
          if (text == null || text.length() == 0)
            continue;
          _entries.add(new ClipEntry(o.optString("id", UUID.randomUUID().toString()),
                ClipEntry.Kind.TEXT, text, null, null, "text/plain",
                label, 0, 0, size, created,
                sha256(text.getBytes(StandardCharsets.UTF_8)), true));
        }
        else
        {
          File file = new File(_files_dir, o.optString("file", ""));
          if (!file.exists())
            continue;
          String thumb_path = o.optString("thumb", "");
          File thumb = thumb_path.length() == 0 ? null : new File(_files_dir, thumb_path);
          if (thumb != null && !thumb.exists())
            thumb = null;
          _entries.add(new ClipEntry(o.optString("id", UUID.randomUUID().toString()),
                ClipEntry.Kind.IMAGE, null, file, thumb,
                o.optString("mime", "image/*"), label,
                o.optInt("width", 0), o.optInt("height", 0), size, created,
                o.optString("sha256", ""), true));
        }
      }
      Collections.sort(_entries, new java.util.Comparator<ClipEntry>() {
        public int compare(ClipEntry a, ClipEntry b)
        {
          return Long.compare(b.created_at, a.created_at);
        }
      });
    }
    catch (Exception e)
    {
      Log.w(TAG, "Could not read pinned entries: " + e.getClass().getSimpleName());
    }
  }

  /** Import pinned text from the upstream preference file, once. */
  void migrate_old_pins()
  {
    if (_context == null || _pinned_file.exists())
      return;
    try
    {
      android.content.SharedPreferences prefs = _context.getSharedPreferences(
          PINNED_PREFS, Context.MODE_PRIVATE);
      String raw = prefs.getString(PINNED_PREF_KEY, null);
      if (raw == null)
        return;
      JSONArray arr = new JSONArray(raw);
      for (int i = 0; i < arr.length() && i < MAX_PINNED; i++)
        add_text(arr.getString(i), true);
    }
    catch (Exception _e)
    {
      // Old data is best-effort; never block startup.
    }
  }

  // ------------------------------------------------------------------ images

  static final class CopyResult
  {
    final String sha;
    final long size;
    CopyResult(String sha, long size) { this.sha = sha; this.size = size; }
  }

  CopyResult copy_uri(Uri uri, File dst) throws Exception
  {
    if (_context == null)
      return null;
    InputStream in = _context.getContentResolver().openInputStream(uri);
    if (in == null)
      return null;
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    long total = 0;
    FileOutputStream out = new FileOutputStream(dst);
    try
    {
      byte[] buf = new byte[64 * 1024];
      int n;
      while ((n = in.read(buf)) > 0)
      {
        total += n;
        if (total > MAX_IMAGE_BYTES)
          return null;
        digest.update(buf, 0, n);
        out.write(buf, 0, n);
      }
    }
    finally
    {
      try { in.close(); } catch (IOException _e) {}
      try { out.close(); } catch (IOException _e) {}
    }
    return new CopyResult(hex(digest.digest()), total);
  }

  BitmapFactory.Options decode_bounds(File file)
  {
    BitmapFactory.Options o = new BitmapFactory.Options();
    o.inJustDecodeBounds = true;
    BitmapFactory.decodeFile(file.getPath(), o);
    if (o.outWidth <= 0 || o.outHeight <= 0)
      return null;
    return o;
  }

  void make_thumb(File src, File dst, int width, int height)
  {
    try
    {
      int sample = 1;
      while (width / sample > THUMB_MAX || height / sample > THUMB_MAX)
        sample *= 2;
      BitmapFactory.Options o = new BitmapFactory.Options();
      o.inSampleSize = sample;
      Bitmap bmp = BitmapFactory.decodeFile(src.getPath(), o);
      if (bmp == null)
        return;
      int max = Math.max(bmp.getWidth(), bmp.getHeight());
      if (max > THUMB_MAX)
      {
        float scale = (float)THUMB_MAX / (float)max;
        Bitmap scaled = Bitmap.createScaledBitmap(bmp,
            Math.max(1, Math.round(bmp.getWidth() * scale)),
            Math.max(1, Math.round(bmp.getHeight() * scale)), true);
        bmp.recycle();
        bmp = scaled;
      }
      FileOutputStream out = new FileOutputStream(dst);
      try
      {
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out);
      }
      finally
      {
        try { out.close(); } catch (IOException _e) {}
        bmp.recycle();
      }
    }
    catch (Exception e)
    {
      dst.delete();
      Log.w(TAG, "Could not create thumbnail: " + e.getClass().getSimpleName());
    }
  }

  // ------------------------------------------------------------------ paste

  public Uri content_uri(ClipEntry e)
  {
    if (_context == null || e == null || e.file == null)
      return null;
    return FileProvider.getUriForFile(_context,
        _context.getPackageName() + ".clipboard", e.file);
  }

  public boolean copy_to_system_clipboard(ClipEntry e)
  {
    if (_context == null)
      return false;
    try
    {
      ClipboardManager cm = (ClipboardManager)
        _context.getSystemService(Context.CLIPBOARD_SERVICE);
      if (cm == null)
        return false;
      if (e.is_text())
        cm.setPrimaryClip(ClipData.newPlainText("clipboard", e.text));
      else
      {
        Uri uri = content_uri(e);
        if (uri == null)
          return false;
        cm.setPrimaryClip(ClipData.newUri(_context.getContentResolver(),
              e.label == null ? "image" : e.label, uri));
      }
      return true;
    }
    catch (Exception _e)
    {
      return false;
    }
  }

  public void share(ClipEntry e)
  {
    if (_context == null || e == null)
      return;
    Intent send;
    if (e.is_text())
    {
      send = new Intent(Intent.ACTION_SEND);
      send.setType("text/plain");
      send.putExtra(Intent.EXTRA_TEXT, e.text);
    }
    else
    {
      Uri uri = content_uri(e);
      if (uri == null)
        return;
      send = new Intent(Intent.ACTION_SEND);
      send.setType(e.mime == null ? "image/*" : e.mime);
      send.putExtra(Intent.EXTRA_STREAM, uri);
      send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
    Intent chooser = Intent.createChooser(send, "Share clipboard item");
    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    try { _context.startActivity(chooser); }
    catch (Exception _e) { /* No activity to handle it. */ }
  }

  // ------------------------------------------------------------------ helpers

  static String label_for_text(String text)
  {
    String one = text.replaceAll("\\s+", " ").trim();
    return one.length() > 80 ? one.substring(0, 80) : one;
  }

  static String relative(File base, File child)
  {
    String b = base.getAbsolutePath();
    String c = child.getAbsolutePath();
    if (c.startsWith(b + File.separator))
      return c.substring(b.length() + 1);
    return child.getName();
  }

  void delete_entry_files(ClipEntry e)
  {
    if (e.file != null)
      e.file.delete();
    if (e.thumb != null)
      e.thumb.delete();
  }

  File move_to(File from, File dir, String name)
  {
    if (from == null)
      return null;
    ensure_dir(dir);
    File to = new File(dir, name);
    if (from.getAbsolutePath().equals(to.getAbsolutePath()))
      return from;
    if (from.renameTo(to))
      return to;
    try
    {
      copy_file(from, to);
      from.delete();
      return to;
    }
    catch (Exception e)
    {
      to.delete();
      Log.w(TAG, "Could not move clipboard file: " + e.getClass().getSimpleName());
      return null;
    }
  }

  static void copy_file(File from, File to) throws IOException
  {
    FileInputStream in = new FileInputStream(from);
    FileOutputStream out = new FileOutputStream(to);
    try
    {
      byte[] buf = new byte[64 * 1024];
      int n;
      while ((n = in.read(buf)) > 0)
        out.write(buf, 0, n);
    }
    finally
    {
      try { in.close(); } catch (IOException _e) {}
      try { out.close(); } catch (IOException _e) {}
    }
  }

  static void ensure_dir(File dir)
  {
    if (!dir.exists())
      dir.mkdirs();
  }

  static void delete_tree(File f)
  {
    if (f == null || !f.exists())
      return;
    if (f.isDirectory())
    {
      File[] children = f.listFiles();
      if (children != null)
        for (File c : children)
          delete_tree(c);
    }
    f.delete();
  }

  static String read_file(File file) throws IOException
  {
    FileInputStream in = new FileInputStream(file);
    try
    {
      byte[] data = new byte[(int)file.length()];
      int off = 0;
      while (off < data.length)
      {
        int n = in.read(data, off, data.length - off);
        if (n < 0)
          break;
        off += n;
      }
      return new String(data, 0, off, StandardCharsets.UTF_8);
    }
    finally
    {
      in.close();
    }
  }

  static void write_atomic(File file, String content) throws IOException
  {
    File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
    OutputStreamWriter w = new OutputStreamWriter(
        new FileOutputStream(tmp), StandardCharsets.UTF_8);
    try { w.write(content); }
    finally { w.close(); }
    if (!tmp.renameTo(file))
    {
      FileOutputStream out = new FileOutputStream(file);
      try { out.write(content.getBytes(StandardCharsets.UTF_8)); }
      finally { out.close(); }
      tmp.delete();
    }
  }

  static String sha256(byte[] data)
  {
    try
    {
      return hex(MessageDigest.getInstance("SHA-256").digest(data));
    }
    catch (Exception _e)
    {
      return "";
    }
  }

  static String hex(byte[] data)
  {
    StringBuilder b = new StringBuilder(data.length * 2);
    for (byte x : data)
      b.append(String.format(Locale.US, "%02x", x & 0xff));
    return b.toString();
  }
}
