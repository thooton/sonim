package com.hello.xp3;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

/** The simplest possible app: one screen that says hello. */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setText("Hello, world!");
        text.setTextSize(28);
        text.setGravity(Gravity.CENTER);
        setContentView(text);
    }
}
