// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 franticplant

package juloo.keyboard2.personal;

import java.io.File;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class PersonalModelTest
{
  File _root;
  PersonalModel _model;

  @Before
  public void setUp() throws Exception
  {
    _root = File.createTempFile("personal-model-test", "");
    _root.delete();
    _root.mkdirs();
    _model = new PersonalModel(_root);
  }

  @After
  public void tearDown()
  {
    delete(_root);
  }

  PersonalModel reload()
  {
    return new PersonalModel(_root);
  }

  @Test
  public void learnsWordsAndBigrams()
  {
    _model.observe_committed_text("hello ");
    _model.observe_committed_text("world ");
    assertEquals(1, _model.unigram_count("hello"));
    assertEquals(1, _model.unigram_count("world"));
    assertEquals("world", _model.previous_word());
  }

  @Test
  public void personalVocabularyReranks()
  {
    _model.observe_committed_text("hello ");
    String[] candidates = new String[] {"help", "hello", null};
    int n = _model.rerank("hel", candidates, 2);
    assertEquals(2, n);
    assertEquals("hello", candidates[0]);
  }

  @Test
  public void personalVocabularyCanBeAdded()
  {
    _model.observe_committed_text("frankenstein ");
    String[] candidates = new String[3];
    int n = _model.rerank("fran", candidates, 0);
    assertEquals(1, n);
    assertEquals("frankenstein", candidates[0]);
  }

  @Test
  public void acceptedCorrectionIsLearned()
  {
    _model.accepted("teh", "the");
    assertEquals("the", _model.correction_for("teh"));
    String[] candidates = new String[] {"ten", "the", null};
    _model.rerank("teh", candidates, 2);
    assertEquals("the", candidates[0]);
  }

  @Test
  public void rejectedCorrectionIsRemoved()
  {
    _model.accepted("teh", "the");
    _model.rejected("teh", "the");
    _model.rejected("teh", "the");
    assertNull(_model.correction_for("teh"));
  }

  @Test
  public void modelPersists()
  {
    _model.observe_committed_text("hello ");
    _model.observe_committed_text("world ");
    _model.flush();
    PersonalModel loaded = reload();
    assertEquals(1, loaded.unigram_count("hello"));
    assertEquals(1, loaded.unigram_count("world"));
    assertEquals(1, loaded.bigram_count("hello", "world"));
  }

  @Test
  public void correctionPersists()
  {
    _model.accepted("teh", "the");
    _model.flush();
    PersonalModel loaded = reload();
    assertEquals("the", loaded.correction_for("teh"));
  }

  @Test
  public void backspaceEditsCurrentWord()
  {
    _model.observe_committed_text("hell");
    _model.backspace();
    _model.observe_committed_text("lo ");
    assertEquals(1, _model.unigram_count("hello"));
  }

  @Test
  public void newContextDropsPreviousWord()
  {
    _model.observe_committed_text("hello ");
    assertEquals("hello", _model.previous_word());
    _model.reset_context();
    assertEquals("", _model.previous_word());
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
