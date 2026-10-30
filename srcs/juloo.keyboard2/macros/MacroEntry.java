package juloo.keyboard2.macros;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import juloo.keyboard2.KeyValue;

/** One snippet or named macro from [macros.json]. */
public final class MacroEntry
{
  /** Optional name, used by the future macro picker. */
  public final String name;
  /** Text that triggers this entry, for example [;sig]. */
  public final String trigger;
  /** Text to insert. [null] when this is a key macro. */
  public final String text;
  /** Key sequence to evaluate. [null] when this is a text snippet. */
  public final KeyValue[] keys;
  /** Package patterns this entry is restricted to. Empty means every app. */
  final List<Pattern> apps;
  /** Package patterns this entry is hidden from. */
  final List<Pattern> exclude_apps;
  public final boolean enabled;
  /** Whether the delimiter (space, enter, tab) is kept after the expansion. */
  public final boolean append_delimiter;

  public MacroEntry(String name, String trigger, String text, KeyValue[] keys,
      List<String> apps, List<String> exclude_apps, boolean enabled,
      boolean append_delimiter)
  {
    this.name = name;
    this.trigger = trigger;
    this.text = text;
    this.keys = keys;
    this.apps = compile_all(apps);
    this.exclude_apps = compile_all(exclude_apps);
    this.enabled = enabled;
    this.append_delimiter = append_delimiter;
  }

  static List<Pattern> compile_all(List<String> globs)
  {
    if (globs == null || globs.isEmpty())
      return Collections.emptyList();
    ArrayList<Pattern> out = new ArrayList<Pattern>(globs.size());
    for (String g : globs)
      if (g != null && g.length() > 0)
        out.add(AppPattern.compile(g));
    return Collections.unmodifiableList(out);
  }

  public boolean is_text()
  {
    return text != null;
  }

  public boolean applies_to(String package_name)
  {
    if (!enabled)
      return false;
    if (package_name == null)
      package_name = "";
    if (!apps.isEmpty())
    {
      boolean any = false;
      for (Pattern p : apps)
        if (p.matcher(package_name).matches())
        {
          any = true;
          break;
        }
      if (!any)
        return false;
    }
    for (Pattern p : exclude_apps)
      if (p.matcher(package_name).matches())
        return false;
    return true;
  }
}
