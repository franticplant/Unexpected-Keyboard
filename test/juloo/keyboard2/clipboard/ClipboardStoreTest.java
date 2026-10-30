// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 franticplant

package juloo.keyboard2.clipboard;

import java.io.File;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class ClipboardStoreTest
{
  File _root;
  File _files;
  File _cache;
  ClipboardStore _store;

  @Before
  public void setUp() throws Exception
  {
    _root = File.createTempFile("clipboard-test", "");
    _root.delete();
    _root.mkdirs();
    _files = new File(_root, "files");
    _cache = new File(_root, "cache");
    _store = new ClipboardStore(_files, _cache);
  }

  @After
  public void tearDown()
  {
    delete(_root);
  }

  ClipboardStore reload()
  {
    return new ClipboardStore(_files, _cache);
  }

  @Test
  public void textIsDedupedAndMovedToFront()
  {
    _store.add_text("hello", false);
    _store.add_text("world", false);
    _store.add_text("hello", false);
    List<ClipEntry> entries = _store.entries();
    assertEquals(2, entries.size());
    assertEquals("hello", entries.get(0).text);
  }

  @Test
  public void emptyTextIsIgnored()
  {
    _store.add_text("", false);
    _store.add_text("   ", false);
    assertEquals(0, _store.entries().size());
  }

  @Test
  public void pinnedTextSurvivesReload()
  {
    _store.add_text("keep me", true);
    _store.add_text("temporary", false);
    List<ClipEntry> entries = reload().entries();
    assertEquals(1, entries.size());
    assertEquals("keep me", entries.get(0).text);
    assertTrue(entries.get(0).pinned);
  }

  @Test
  public void clearUnpinnedKeepsPinned()
  {
    _store.add_text("keep me", true);
    _store.add_text("temporary", false);
    _store.clear_unpinned();
    List<ClipEntry> entries = _store.entries();
    assertEquals(1, entries.size());
    assertEquals("keep me", entries.get(0).text);
  }

  @Test
  public void pinAndUnpinText()
  {
    _store.add_text("item", false);
    ClipEntry e = _store.entries().get(0);
    assertTrue(_store.pin(e));
    assertTrue(e.pinned);
    assertEquals(1, reload().entries().size());
    assertTrue(_store.unpin(e));
    assertFalse(e.pinned);
    assertEquals(0, reload().entries().size());
  }

  @Test
  public void removeDeletesEntry()
  {
    _store.add_text("item", false);
    ClipEntry e = _store.entries().get(0);
    _store.remove(e);
    assertEquals(0, _store.entries().size());
    assertNotNull(e);
  }

  @Test
  public void queryFiltersAndSearches()
  {
    _store.add_text("alpha", false);
    _store.add_text("beta", true);
    assertEquals(2, _store.query(ClipboardStore.Filter.ALL, null).size());
    assertEquals(1, _store.query(ClipboardStore.Filter.TEXT, "alp").size());
    assertEquals(1, _store.query(ClipboardStore.Filter.PINNED, null).size());
    assertEquals(0, _store.query(ClipboardStore.Filter.IMAGES, null).size());
  }

  @Test
  public void pinnedEntriesSortFirst()
  {
    _store.add_text("older pinned", true);
    _store.add_text("newer unpinned", false);
    List<ClipEntry> entries = _store.query(ClipboardStore.Filter.ALL, null);
    assertEquals("older pinned", entries.get(0).text);
  }

  static void delete(File f)
  {
    if (f == null || !f.exists())
      return;
    if (f.isDirectory())
    {
      File[] children = f.listFiles();
      if (children != null)
        for (File c : children)
          delete(c);
    }
    f.delete();
  }
}
