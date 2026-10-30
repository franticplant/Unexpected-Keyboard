// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 franticplant
package juloo.keyboard2.macros;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import juloo.keyboard2.KeyValue;
import juloo.keyboard2.KeyValueParser;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * User-defined snippets and named macros.
 *
 * The configuration lives in [files/macros.json] so it can be edited in the
 * app or pulled/pushed with adb. See doc/Macros.md for the schema.
 */
public final class MacroStore
{
  static final String FILE_NAME = "macros.json";

  static final String DEFAULT_JSON =
    "{\n" +
    "  \"version\": 1,\n" +
    "  \"snippets\": [\n" +
    "    {\"name\": \"date\", \"trigger\": \";date\", \"text\": \"{date}\", \"enabled\": false},\n" +
    "    {\"name\": \"signature\", \"trigger\": \";sig\", \"text\": \"Best,\\nYour Name\", \"enabled\": false}\n" +
    "  ],\n" +
    "  \"macros\": [\n" +
    "    {\"name\": \"select_all\", \"trigger\": \";selall\", \"keys\": \"ctrl,a\", \"enabled\": false}\n" +
    "  ]\n" +
    "}\n";

  static MacroStore _instance;

  final Context _context;
  final File _file;
  List<MacroEntry> _entries = Collections.emptyList();
  int _max_trigger_length = 0;

  MacroStore(Context context)
  {
    _context = context.getApplicationContext();
    _file = new File(_context.getFilesDir(), FILE_NAME);
    if (!_file.exists())
    {
      try
      {
        write_file(_file, DEFAULT_JSON);
      }
      catch (Exception _e)
      {
        // The editor can still create the file later.
      }
    }
    reload();
  }

  /** Initialize the store. Safe to call more than once. */
  public static synchronized MacroStore init(Context context)
  {
    if (_instance == null)
      _instance = new MacroStore(context);
    return _instance;
  }

  /** Might be [null] before [init] or in unit tests. */
  public static MacroStore get()
  {
    return _instance;
  }

  public boolean has_entries()
  {
    return !_entries.isEmpty();
  }

  public int max_trigger_length()
  {
    return _max_trigger_length;
  }

  public List<MacroEntry> entries()
  {
    return _entries;
  }

  public MacroEntry match(String package_name, String before_cursor)
  {
    return MacroMatcher.find(_entries, package_name, before_cursor);
  }

  public String read_raw()
  {
    try
    {
      return read_file(_file);
    }
    catch (Exception e)
    {
      return DEFAULT_JSON;
    }
  }

  /** Validate [raw] before replacing the configuration. */
  public void write_raw(String raw) throws JSONException
  {
    List<MacroEntry> parsed = parse(raw);
    try
    {
      write_file(_file, raw);
    }
    catch (Exception e)
    {
      throw new JSONException("Could not write " + FILE_NAME + ": " + e.getMessage());
    }
    _entries = parsed;
    _max_trigger_length = compute_max_trigger(parsed);
  }

  public void reset_to_defaults() throws JSONException
  {
    write_raw(DEFAULT_JSON);
  }

  void reload()
  {
    String raw;
    try
    {
      raw = read_file(_file);
    }
    catch (Exception e)
    {
      return;
    }
    try
    {
      _entries = parse(raw);
      _max_trigger_length = compute_max_trigger(_entries);
    }
    catch (JSONException e)
    {
      // Keep the previous configuration when the file is broken.
    }
  }

  static int compute_max_trigger(List<MacroEntry> entries)
  {
    int max = 0;
    for (MacroEntry e : entries)
      max = Math.max(max, e.trigger.length());
    return max;
  }

  static List<MacroEntry> parse(String raw) throws JSONException
  {
    JSONObject root = new JSONObject(raw);
    ArrayList<MacroEntry> out = new ArrayList<MacroEntry>();
    parse_list(root.optJSONArray("snippets"), out, false);
    parse_list(root.optJSONArray("macros"), out, true);
    return Collections.unmodifiableList(out);
  }

  static void parse_list(JSONArray array, List<MacroEntry> out, boolean keys_only)
      throws JSONException
  {
    if (array == null)
      return;
    for (int i = 0; i < array.length(); i++)
    {
      JSONObject obj = array.optJSONObject(i);
      if (obj == null)
        throw new JSONException("Entry " + i + " is not an object");
      String trigger = obj.optString("trigger", "");
      if (trigger.length() == 0)
        throw new JSONException("Entry " + i + ": 'trigger' is required");
      String name = obj.optString("name", trigger);
      String text = obj.isNull("text") ? null : obj.optString("text", null);
      String keys_str = obj.isNull("keys") ? null : obj.optString("keys", null);
      KeyValue[] keys = null;
      if (keys_str != null)
        keys = parse_keys(i, keys_str);
      if (text == null && keys == null)
        throw new JSONException("Entry " + i + " needs 'text' or 'keys'");
      if (keys_only && text != null)
        throw new JSONException("Entry " + i + " is in 'macros' but has 'text'");
      out.add(new MacroEntry(name, trigger, text, keys,
            string_list(obj, "apps"), string_list(obj, "exclude_apps"),
            obj.optBoolean("enabled", true),
            obj.optBoolean("append_delimiter", true)));
    }
  }

  static KeyValue[] parse_keys(int index, String keys) throws JSONException
  {
    try
    {
      KeyValue kv = KeyValueParser.parse("k:" + keys);
      if (kv.getKind() == KeyValue.Kind.Macro)
        return kv.getMacro();
      return new KeyValue[]{ kv };
    }
    catch (KeyValueParser.ParseError e)
    {
      throw new JSONException("Entry " + index + ": bad 'keys': " + e.getMessage());
    }
  }

  static List<String> string_list(JSONObject obj, String key)
      throws JSONException
  {
    if (!obj.has(key) || obj.isNull(key))
      return Collections.emptyList();
    Object value = obj.get(key);
    ArrayList<String> out = new ArrayList<String>();
    if (value instanceof JSONArray)
    {
      JSONArray array = (JSONArray)value;
      for (int i = 0; i < array.length(); i++)
        out.add(array.getString(i));
    }
    else
      out.add(value.toString());
    return out;
  }

  /** Replace {date}, {time}, {datetime}, {clipboard}, {app}, {newline} and
      {tab} in [template]. Unknown variables are left untouched. */
  public String expand(String template, String package_name)
  {
    StringBuilder out = new StringBuilder(template.length() + 16);
    int i = 0;
    while (i < template.length())
    {
      char c = template.charAt(i);
      if (c != '{')
      {
        out.append(c);
        i++;
        continue;
      }
      int end = template.indexOf('}', i + 1);
      if (end < 0)
      {
        out.append(template.substring(i));
        break;
      }
      String var = template.substring(i + 1, end);
      String value = variable(var, package_name);
      if (value == null)
        out.append(template, i, end + 1);
      else
        out.append(value);
      i = end + 1;
    }
    return out.toString();
  }

  String variable(String name, String package_name)
  {
    switch (name)
    {
      case "date":
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
          .format(new Date());
      case "time":
        return new SimpleDateFormat("HH:mm", Locale.getDefault())
          .format(new Date());
      case "datetime":
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
          .format(new Date());
      case "app":
        return package_name == null ? "" : package_name;
      case "newline":
        return "\n";
      case "tab":
        return "\t";
      case "clipboard":
        return clipboard_text();
      default:
        return null;
    }
  }

  String clipboard_text()
  {
    try
    {
      ClipboardManager cm = (ClipboardManager)
        _context.getSystemService(Context.CLIPBOARD_SERVICE);
      if (cm == null || !cm.hasPrimaryClip())
        return "";
      ClipData clip = cm.getPrimaryClip();
      if (clip == null || clip.getItemCount() == 0)
        return "";
      CharSequence text = clip.getItemAt(0).coerceToText(_context);
      if (text == null)
        return "";
      String s = text.toString();
      // Avoid committing a huge clipboard by accident.
      return s.length() > 8192 ? s.substring(0, 8192) : s;
    }
    catch (Exception _e)
    {
      // Clipboard access can be denied when the IME is not focused.
      return "";
    }
  }

  static String read_file(File file) throws Exception
  {
    if (!file.exists())
      throw new java.io.FileNotFoundException(file.getPath());
    StringBuilder out = new StringBuilder((int)file.length());
    BufferedReader r = new BufferedReader(new InputStreamReader(
          new FileInputStream(file), StandardCharsets.UTF_8));
    try
    {
      char[] buf = new char[4096];
      int n;
      while ((n = r.read(buf)) > 0)
        out.append(buf, 0, n);
    }
    finally
    {
      r.close();
    }
    return out.toString();
  }

  static void write_file(File file, String content) throws Exception
  {
    File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
    OutputStreamWriter w = new OutputStreamWriter(
        new FileOutputStream(tmp), StandardCharsets.UTF_8);
    try
    {
      w.write(content);
    }
    finally
    {
      w.close();
    }
    if (!tmp.renameTo(file))
    {
      // Fall back to a direct write on filesystems that dislike rename.
      OutputStreamWriter w2 = new OutputStreamWriter(
          new FileOutputStream(file), StandardCharsets.UTF_8);
      try
      {
        w2.write(content);
      }
      finally
      {
        w2.close();
      }
      tmp.delete();
    }
  }
}
