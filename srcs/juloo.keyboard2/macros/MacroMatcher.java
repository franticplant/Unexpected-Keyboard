package juloo.keyboard2.macros;

import java.util.List;

/** Find the snippet that matches the text before the cursor. */
public final class MacroMatcher
{
  /** Return the entry with the longest matching trigger, or [null]. */
  public static MacroEntry find(List<MacroEntry> entries, String package_name,
      String before_cursor)
  {
    MacroEntry best = null;
    for (int i = 0; i < entries.size(); i++)
    {
      MacroEntry e = entries.get(i);
      if (!e.applies_to(package_name))
        continue;
      if (!before_cursor.endsWith(e.trigger))
        continue;
      if (best == null || e.trigger.length() > best.trigger.length())
        best = e;
    }
    return best;
  }
}
