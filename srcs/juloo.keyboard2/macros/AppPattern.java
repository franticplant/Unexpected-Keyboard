// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 franticplant
package juloo.keyboard2.macros;

import java.util.regex.Pattern;

/** Match package names against simple glob patterns. */
public final class AppPattern
{
  /** [pattern] supports [*] (any sequence) and [?] (any single character).
      An empty pattern or [*] matches every package. */
  public static boolean matches(String pattern, String package_name)
  {
    if (pattern == null || pattern.length() == 0 || pattern.equals("*"))
      return true;
    if (package_name == null)
      package_name = "";
    return compile(pattern).matcher(package_name).matches();
  }

  public static Pattern compile(String pattern)
  {
    StringBuilder b = new StringBuilder(pattern.length() + 8);
    b.append('^');
    for (int i = 0; i < pattern.length(); i++)
    {
      char c = pattern.charAt(i);
      switch (c)
      {
        case '*': b.append(".*"); break;
        case '?': b.append('.'); break;
        default:
          if ("\\.[]{}()+-^$|".indexOf(c) >= 0)
            b.append('\\');
          b.append(c);
          break;
      }
    }
    b.append('$');
    return Pattern.compile(b.toString());
  }
}
