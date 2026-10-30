// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 franticplant
package juloo.keyboard2.macros;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public class MacroMatcherTest
{
  static MacroEntry entry(String trigger, String text, String... apps)
  {
    return new MacroEntry(trigger, trigger, text, null,
        Arrays.asList(apps), Collections.emptyList(), true, true);
  }

  @Test
  public void longestTriggerWins()
  {
    MacroEntry short_ = entry(";s", "short");
    MacroEntry long_ = entry(";sig", "long");
    MacroEntry found = MacroMatcher.find(
        Arrays.asList(short_, long_), "com.example", "hello ;sig");
    assertEquals(long_, found);
  }

  @Test
  public void triggerMustBeAtTheEnd()
  {
    MacroEntry e = entry(";sig", "signature");
    assertNull(MacroMatcher.find(
          Collections.singletonList(e), "com.example", ";sig hello"));
  }

  @Test
  public void appRestrictionIsHonored()
  {
    MacroEntry e = entry(";sig", "signature", "com.whatsapp");
    assertEquals(e, MacroMatcher.find(
          Collections.singletonList(e), "com.whatsapp", ";sig"));
    assertNull(MacroMatcher.find(
          Collections.singletonList(e), "com.telegram", ";sig"));
  }

  @Test
  public void appGlobMatches()
  {
    assertTrue(AppPattern.matches("org.telegram.*", "org.telegram.messenger"));
    assertTrue(AppPattern.matches("com.*.chat", "com.example.chat"));
    assertTrue(AppPattern.matches("*", "anything"));
    assertFalse(AppPattern.matches("org.telegram.*", "org.telegram"));
  }

  @Test
  public void disabledEntryNeverMatches()
  {
    MacroEntry e = new MacroEntry("x", ";x", "x", null,
        Collections.emptyList(), Collections.emptyList(), false, true);
    assertNull(MacroMatcher.find(
          Collections.singletonList(e), "com.example", ";x"));
  }

  @Test
  public void excludedAppIsHonored()
  {
    MacroEntry e = new MacroEntry("x", ";x", "x", null,
        Collections.emptyList(), Collections.singletonList("com.bank.*"),
        true, true);
    assertNull(MacroMatcher.find(
          Collections.singletonList(e), "com.bank.app", ";x"));
    assertEquals(e, MacroMatcher.find(
          Collections.singletonList(e), "com.other", ";x"));
  }
}
