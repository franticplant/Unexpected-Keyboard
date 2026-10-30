// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 franticplant
package juloo.keyboard2.macros;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import juloo.keyboard2.R;
import org.json.JSONException;

/** A tiny JSON editor for [macros.json]. */
public class MacroEditorActivity extends Activity
{
  EditText _text;
  MacroStore _store;

  @Override
  public void onCreate(Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    _store = MacroStore.init(this);
    setContentView(R.layout.macro_editor);
    _text = (EditText)findViewById(R.id.macro_editor_text);
    _text.setText(_store.read_raw());

    findViewById(R.id.macro_editor_save).setOnClickListener(
        new View.OnClickListener() {
          public void onClick(View v) { save(); }
        });
    findViewById(R.id.macro_editor_example).setOnClickListener(
        new View.OnClickListener() {
          public void onClick(View v) { load_example(); }
        });
    findViewById(R.id.macro_editor_reset).setOnClickListener(
        new View.OnClickListener() {
          public void onClick(View v) { confirm_reset(); }
        });
  }

  void save()
  {
    try
    {
      _store.write_raw(_text.getText().toString());
      Toast.makeText(this, R.string.macro_editor_saved, Toast.LENGTH_SHORT).show();
      finish();
    }
    catch (JSONException e)
    {
      show_error(e.getMessage());
    }
  }

  void load_example()
  {
    try
    {
      BufferedReader r = new BufferedReader(new InputStreamReader(
            getAssets().open("macros.example.json"), "UTF-8"));
      StringBuilder b = new StringBuilder();
      try
      {
        char[] buf = new char[4096];
        int n;
        while ((n = r.read(buf)) > 0)
          b.append(buf, 0, n);
      }
      finally
      {
        r.close();
      }
      _text.setText(b.toString());
      Toast.makeText(this, R.string.macro_editor_example_loaded,
          Toast.LENGTH_SHORT).show();
    }
    catch (Exception e)
    {
      show_error("Could not read the example: " + e.getMessage());
    }
  }

  void confirm_reset()
  {
    new AlertDialog.Builder(this)
      .setTitle(R.string.macro_editor_reset)
      .setMessage(R.string.macro_editor_reset_confirm)
      .setNegativeButton(android.R.string.cancel, null)
      .setPositiveButton(android.R.string.ok,
          new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which)
            {
              _text.setText(MacroStore.DEFAULT_JSON);
              Toast.makeText(MacroEditorActivity.this,
                  R.string.macro_editor_reset_loaded, Toast.LENGTH_SHORT).show();
            }
          })
      .show();
  }

  void show_error(String message)
  {
    new AlertDialog.Builder(this)
      .setTitle(R.string.macro_editor_invalid)
      .setMessage(message == null ? "" : message)
      .setPositiveButton(android.R.string.ok, null)
      .show();
  }
}
