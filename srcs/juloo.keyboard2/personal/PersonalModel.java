// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 franticplant

package juloo.keyboard2.personal;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.json.JSONObject;

/**
 * A small, local language model learned from committed words and accepted or
 * rejected corrections.
 *
 * It never runs on the network, never stores whole sentences and never sees
 * password fields. The cdict dictionary remains the candidate generator; this
 * model only reranks its candidates and adds personal vocabulary.
 */
public final class PersonalModel
{
  static final int MAX_UNIGRAMS = 10_000;
  static final int MAX_BIGRAMS = 25_000;
  static final int MAX_CORRECTIONS = 2_000;
  static final int MAX_WORD_LENGTH = 64;
  static final int MAX_CANDIDATES = 5;
  static final int DECAY_EVERY = 2_000;
  static final int SAVE_EVERY = 20;
  static final String FILE_NAME = "model.json";

  static PersonalModel _instance;

  final File _dir;
  final File _file;
  final ExecutorService _writer = Executors.newSingleThreadExecutor(
      new ThreadFactory() {
        public Thread newThread(Runnable r)
        {
          Thread t = new Thread(r, "personal-model-writer");
          t.setDaemon(true);
          return t;
        }
      });

  final TreeMap<String, Integer> _unigrams = new TreeMap<String, Integer>();
  final HashMap<String, Integer> _bigrams = new HashMap<String, Integer>();
  /** typed -> target -> counts. */
  final HashMap<String, HashMap<String, Correction>> _corrections =
    new HashMap<String, HashMap<String, Correction>>();

  final StringBuilder _current = new StringBuilder();
  String _previous_word = "";
  int _observations = 0;
  int _pending = 0;

  public static synchronized PersonalModel init(Context context)
  {
    if (_instance == null)
      _instance = new PersonalModel(new File(context.getFilesDir(), "personal"));
    return _instance;
  }

  /** Might be [null] before [init] or in unit tests. */
  public static PersonalModel get()
  {
    return _instance;
  }

  /** Test constructor: only needs a directory. */
  public PersonalModel(File dir)
  {
    _dir = dir;
    _file = new File(dir, FILE_NAME);
    load();
  }

  public synchronized String previous_word()
  {
    return _previous_word;
  }

  public synchronized int unigram_count(String word)
  {
    Integer n = _unigrams.get(norm(word));
    return n == null ? 0 : n;
  }

  public synchronized int bigram_count(String previous, String word)
  {
    Integer n = _bigrams.get(bigram_key(norm(previous), norm(word)));
    return n == null ? 0 : n;
  }

  /** Forget the word currently being typed; called on selection changes and
      backspace. The committed counts are kept. */
  public synchronized void reset_current()
  {
    _current.setLength(0);
  }

  /** Start a new editor context; do not carry the previous word across apps. */
  public synchronized void reset_context()
  {
    _current.setLength(0);
    _previous_word = "";
  }

  /** Remove the last code point from the word being typed. */
  public synchronized void backspace()
  {
    int len = _current.length();
    if (len == 0)
      return;
    int cp = _current.codePointBefore(len);
    _current.delete(len - Character.charCount(cp), len);
  }

  /** Feed committed text. Words are committed when a delimiter is seen. */
  public synchronized void observe_committed_text(String text)
  {
    if (text == null)
      return;
    for (int i = 0; i < text.length();)
    {
      int cp = Character.codePointAt(text, i);
      i += Character.charCount(cp);
      if (is_word_char(cp))
      {
        if (_current.length() < MAX_WORD_LENGTH)
          _current.appendCodePoint(cp);
      }
      else
        commit_current_locked();
    }
  }

  /** Commit the word currently being typed, if any. */
  public synchronized void commit_current()
  {
    commit_current_locked();
  }

  public synchronized void accepted(String typed, String target)
  {
    String t = norm(typed);
    String w = norm(target);
    if (t.length() == 0 || w.length() == 0)
      return;
    // The typed form was replaced; do not learn it as a word.
    _current.setLength(0);
    Correction c = correction_locked(t, w);
    c.accepted++;
    observe_word_locked(w);
    _previous_word = w;
    maybe_save_locked();
  }

  public synchronized void rejected(String typed, String target)
  {
    String t = norm(typed);
    String w = norm(target);
    if (t.length() == 0 || w.length() == 0)
      return;
    Correction c = correction_locked(t, w);
    c.rejected++;
    if (c.rejected >= 2 && c.rejected > c.accepted)
      remove_correction_locked(t, w);
    maybe_save_locked();
  }

  /** The best learned correction for [typed], or [null]. */
  public synchronized String correction_for(String typed)
  {
    return correction_for_locked(norm(typed));
  }

  /**
   * Rerank [candidates] in place and return the new count. Personal vocabulary
   * and learned corrections are added when they are plausible.
   */
  public synchronized int rerank(String typed, String[] candidates, int count)
  {
    if (typed == null || typed.length() < 2)
      return count;
    String t = norm(typed);
    if (_unigrams.isEmpty() && _corrections.isEmpty())
      return count;

    LinkedHashMap<String, Double> scores = new LinkedHashMap<String, Double>();
    for (int i = 0; i < count && i < candidates.length; i++)
    {
      String c = candidates[i];
      if (c == null || c.length() == 0)
        continue;
      add_score(scores, c, 1.0 / (i + 1.0));
    }

    String correction = correction_for_locked(t);
    if (correction != null)
      add_score(scores, correction, 0.0);

    for (String personal : personal_prefix_locked(t, MAX_CANDIDATES))
      add_score(scores, personal, 0.5);

    if (scores.isEmpty())
      return count;

    ArrayList<Map.Entry<String, Double>> ranked =
      new ArrayList<Map.Entry<String, Double>>(scores.entrySet());
    Collections.sort(ranked, new Comparator<Map.Entry<String, Double>>() {
      public int compare(Map.Entry<String, Double> a, Map.Entry<String, Double> b)
      {
        int c = Double.compare(b.getValue(), a.getValue());
        if (c != 0)
          return c;
        return a.getKey().compareTo(b.getKey());
      }
    });

    int n = Math.min(Math.min(ranked.size(), candidates.length), MAX_CANDIDATES);
    for (int i = 0; i < n; i++)
      candidates[i] = ranked.get(i).getKey();
    for (int i = n; i < candidates.length; i++)
      candidates[i] = null;
    return n;
  }

  // ---------------------------------------------------------------- scoring

  void add_score(LinkedHashMap<String, Double> scores, String candidate,
      double base)
  {
    String lower = norm(candidate);
    if (lower.length() == 0)
      return;
    double score = base;
    Integer u = _unigrams.get(lower);
    if (u != null)
      score += 2.5 * Math.log(1 + u);
    Integer b = _bigrams.get(bigram_key(_previous_word, lower));
    if (b != null)
      score += 3.0 * Math.log(1 + b);
    Double conf = confidence_locked(norm(candidate), lower);
    if (conf != null)
      score += 8.0 * conf;
    Double old = scores.get(candidate);
    if (old == null || score > old)
      scores.put(candidate, score);
  }

  ArrayList<String> personal_prefix_locked(String prefix, int limit)
  {
    ArrayList<String> out = new ArrayList<String>();
    if (prefix.length() == 0)
      return out;
    NavigableMap<String, Integer> sub = _unigrams.subMap(prefix, true,
        prefix + Character.MAX_VALUE, false);
    for (Map.Entry<String, Integer> e : sub.entrySet())
    {
      if (e.getKey().length() == prefix.length())
        continue;
      out.add(e.getKey());
    }
    Collections.sort(out, new Comparator<String>() {
      public int compare(String a, String b)
      {
        int ca = unigram_count(a);
        int cb = unigram_count(b);
        if (ca != cb)
          return Integer.compare(cb, ca);
        return a.compareTo(b);
      }
    });
    if (out.size() > limit)
      return new ArrayList<String>(out.subList(0, limit));
    return out;
  }

  // ---------------------------------------------------------------- learning

  void commit_current_locked()
  {
    if (_current.length() == 0)
      return;
    String word = norm(_current.toString());
    _current.setLength(0);
    if (word.length() == 0)
      return;
    observe_word_locked(word);
    _previous_word = word;
    maybe_save_locked();
  }

  void observe_word_locked(String word)
  {
    if (word.length() == 0 || word.length() > MAX_WORD_LENGTH)
      return;
    increment(_unigrams, word);
    if (_previous_word.length() > 0)
      increment(_bigrams, bigram_key(_previous_word, word));
    _observations++;
    if (_observations >= DECAY_EVERY)
      decay_locked();
    trim_locked();
  }

  void increment(Map<String, Integer> map, String key)
  {
    Integer n = map.get(key);
    map.put(key, n == null ? 1 : n + 1);
  }

  Correction correction_locked(String typed, String target)
  {
    HashMap<String, Correction> m = _corrections.get(typed);
    if (m == null)
    {
      m = new HashMap<String, Correction>();
      _corrections.put(typed, m);
    }
    Correction c = m.get(target);
    if (c == null)
    {
      c = new Correction();
      m.put(target, c);
    }
    return c;
  }

  void remove_correction_locked(String typed, String target)
  {
    HashMap<String, Correction> m = _corrections.get(typed);
    if (m == null)
      return;
    m.remove(target);
    if (m.isEmpty())
      _corrections.remove(typed);
  }

  String correction_for_locked(String typed)
  {
    HashMap<String, Correction> m = _corrections.get(typed);
    if (m == null)
      return null;
    String best = null;
    double best_conf = 0;
    for (Map.Entry<String, Correction> e : m.entrySet())
    {
      Correction c = e.getValue();
      if (c.accepted <= c.rejected || c.accepted == 0)
        continue;
      double conf = c.accepted / (c.accepted + c.rejected + 1.0);
      if (conf > best_conf)
      {
        best_conf = conf;
        best = e.getKey();
      }
    }
    return best_conf >= 0.5 ? best : null;
  }

  Double confidence_locked(String typed, String candidate)
  {
    HashMap<String, Correction> m = _corrections.get(typed);
    if (m == null)
      return null;
    Correction c = m.get(candidate);
    if (c == null || c.accepted == 0 || c.accepted <= c.rejected)
      return null;
    return c.accepted / (c.accepted + c.rejected + 1.0);
  }

  void decay_locked()
  {
    decay_map(_unigrams);
    decay_map(_bigrams);
    for (Iterator<HashMap<String, Correction>> it =
        _corrections.values().iterator(); it.hasNext();)
    {
      HashMap<String, Correction> m = it.next();
      for (Iterator<Correction> ci = m.values().iterator(); ci.hasNext();)
      {
        Correction c = ci.next();
        c.accepted = (int)Math.round(c.accepted * 0.9);
        c.rejected = (int)Math.round(c.rejected * 0.9);
        if (c.accepted == 0 && c.rejected == 0)
          ci.remove();
      }
      if (m.isEmpty())
        it.remove();
    }
    _observations = 0;
  }

  static void decay_map(Map<String, Integer> map)
  {
    for (Iterator<Map.Entry<String, Integer>> it = map.entrySet().iterator();
        it.hasNext();)
    {
      Map.Entry<String, Integer> e = it.next();
      int v = (int)Math.round(e.getValue() * 0.9);
      if (v <= 0)
        it.remove();
      else
        e.setValue(v);
    }
  }

  void trim_locked()
  {
    if (_unigrams.size() > MAX_UNIGRAMS + 1000)
      trim_map(_unigrams, MAX_UNIGRAMS);
    if (_bigrams.size() > MAX_BIGRAMS + 2000)
      trim_map(_bigrams, MAX_BIGRAMS);
    if (_corrections.size() > MAX_CORRECTIONS) {
      // Drop the least-confident typed entries.
      ArrayList<String> keys = new ArrayList<String>(_corrections.keySet());
      Collections.sort(keys, new Comparator<String>() {
        public int compare(String a, String b)
        {
          return Integer.compare(correction_strength(b), correction_strength(a));
        }
      });
      while (keys.size() > MAX_CORRECTIONS)
        _corrections.remove(keys.remove(keys.size() - 1));
    }
  }

  int correction_strength(String typed)
  {
    HashMap<String, Correction> m = _corrections.get(typed);
    if (m == null)
      return 0;
    int n = 0;
    for (Correction c : m.values())
      n += c.accepted;
    return n;
  }

  static void trim_map(Map<String, Integer> map, int max)
  {
    ArrayList<Map.Entry<String, Integer>> entries =
      new ArrayList<Map.Entry<String, Integer>>(map.entrySet());
    Collections.sort(entries, new Comparator<Map.Entry<String, Integer>>() {
      public int compare(Map.Entry<String, Integer> a,
          Map.Entry<String, Integer> b)
      {
        return Integer.compare(a.getValue(), b.getValue());
      }
    });
    int remove = entries.size() - max;
    for (int i = 0; i < remove; i++)
      map.remove(entries.get(i).getKey());
  }

  // ------------------------------------------------------------- persistence

  void maybe_save_locked()
  {
    _pending++;
    if (_pending < SAVE_EVERY)
      return;
    _pending = 0;
    save_async_locked();
  }

  void save_async_locked()
  {
    final String json = to_json_locked();
    _writer.execute(new Runnable() {
      public void run() { write_file(json); }
    });
  }

  /** Persist synchronously. Call on IME shutdown; cheap for normal sizes. */
  public synchronized void flush()
  {
    write_file(to_json_locked());
  }

  String to_json_locked()
  {
    try
    {
      JSONObject root = new JSONObject();
      root.put("version", 1);
      root.put("observations", _observations);
      JSONObject u = new JSONObject();
      for (Map.Entry<String, Integer> e : _unigrams.entrySet())
        u.put(e.getKey(), e.getValue());
      root.put("unigrams", u);
      JSONObject b = new JSONObject();
      for (Map.Entry<String, Integer> e : _bigrams.entrySet())
        b.put(e.getKey(), e.getValue());
      root.put("bigrams", b);
      JSONObject c = new JSONObject();
      for (Map.Entry<String, HashMap<String, Correction>> e :
          _corrections.entrySet())
      {
        JSONObject targets = new JSONObject();
        for (Map.Entry<String, Correction> t : e.getValue().entrySet())
        {
          JSONObject counts = new JSONObject();
          counts.put("a", t.getValue().accepted);
          counts.put("r", t.getValue().rejected);
          targets.put(t.getKey(), counts);
        }
        c.put(e.getKey(), targets);
      }
      root.put("corrections", c);
      return root.toString();
    }
    catch (Exception _e)
    {
      return "{\"version\":1}";
    }
  }

  void load()
  {
    if (!_file.exists())
      return;
    try
    {
      String raw = read_file(_file);
      JSONObject root = new JSONObject(raw);
      _observations = root.optInt("observations", 0);
      JSONObject u = root.optJSONObject("unigrams");
      if (u != null)
      {
        Iterator<String> keys = u.keys();
        while (keys.hasNext())
        {
          String k = keys.next();
          int v = u.optInt(k, 0);
          if (v > 0)
            _unigrams.put(k, v);
        }
      }
      JSONObject b = root.optJSONObject("bigrams");
      if (b != null)
      {
        Iterator<String> keys = b.keys();
        while (keys.hasNext())
        {
          String k = keys.next();
          int v = b.optInt(k, 0);
          if (v > 0)
            _bigrams.put(k, v);
        }
      }
      JSONObject c = root.optJSONObject("corrections");
      if (c != null)
      {
        Iterator<String> typed = c.keys();
        while (typed.hasNext())
        {
          String t = typed.next();
          JSONObject targets = c.optJSONObject(t);
          if (targets == null)
            continue;
          HashMap<String, Correction> m = new HashMap<String, Correction>();
          Iterator<String> ti = targets.keys();
          while (ti.hasNext())
          {
            String target = ti.next();
            JSONObject counts = targets.optJSONObject(target);
            if (counts == null)
              continue;
            Correction corr = new Correction();
            corr.accepted = counts.optInt("a", 0);
            corr.rejected = counts.optInt("r", 0);
            if (corr.accepted > 0 || corr.rejected > 0)
              m.put(target, corr);
          }
          if (!m.isEmpty())
            _corrections.put(t, m);
        }
      }
      trim_locked();
    }
    catch (Exception _e)
    {
      // A broken model must not break typing; start from the current state.
    }
  }

  void write_file(String content)
  {
    try
    {
      if (!_dir.exists())
        _dir.mkdirs();
      File tmp = new File(_dir, FILE_NAME + ".tmp");
      OutputStreamWriter w = new OutputStreamWriter(
          new FileOutputStream(tmp), StandardCharsets.UTF_8);
      try { w.write(content); }
      finally { w.close(); }
      if (!tmp.renameTo(_file))
      {
        FileOutputStream out = new FileOutputStream(_file);
        try { out.write(content.getBytes(StandardCharsets.UTF_8)); }
        finally { out.close(); }
        tmp.delete();
      }
    }
    catch (Exception _e)
    {
      // Keep typing even if the model cannot be saved.
    }
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

  // ----------------------------------------------------------------- helpers

  static boolean is_word_char(int cp)
  {
    return Character.isLetterOrDigit(cp) || cp == '\'' || cp == '\u2019';
  }

  static String norm(String s)
  {
    if (s == null)
      return "";
    String lower = s.toLowerCase(Locale.ROOT);
    return lower.length() > MAX_WORD_LENGTH ? lower.substring(0, MAX_WORD_LENGTH) : lower;
  }

  static String bigram_key(String previous, String word)
  {
    return previous + "\u0000" + word;
  }

  static final class Correction
  {
    int accepted;
    int rejected;
  }
}
