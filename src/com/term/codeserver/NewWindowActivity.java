package com.term.codeserver;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

// Trampoline: launcher long-press shortcut hits this, which opens a fresh,
// independent MainActivity instance/window, then disappears.
public class NewWindowActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        startActivity(i);
        finish();
    }
}
