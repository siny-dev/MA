package com.siny.mobilebasic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public final class MainActivityLaunchTest {
    @Test public void launchesTheMinimalMaHomeAndKeepsTaxiAsASmallShortcut() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                List<View> views = new ArrayList<>();
                collect(activity.getWindow().getDecorView(), views);

                boolean hasMaTitle = false;
                boolean hasSubtitle = false;
                Button mic = null;
                Button taxi = null;
                for (View view : views) {
                    if (view instanceof TextView) {
                        String text = ((TextView) view).getText().toString();
                        if ("MA".equals(text)) hasMaTitle = true;
                        if ("Mother’s Assistant".equals(text)) hasSubtitle = true;
                    }
                    if (view instanceof Button) {
                        Button button = (Button) view;
                        if ("말하기".equals(button.getText().toString())) mic = button;
                        if ("택시 호출".contentEquals(button.getContentDescription())) taxi = button;
                    }
                }

                assertTrue("MA title is missing.", hasMaTitle);
                assertTrue("Mother’s Assistant subtitle is missing.", hasSubtitle);
                assertNotNull("Press-and-hold microphone button is missing.", mic);
                assertEquals("누르고 말하기. 손가락을 떼면 음성 전송을 마칩니다.", mic.getContentDescription());
                assertNotNull("Taxi shortcut is missing.", taxi);
                assertEquals(View.VISIBLE, taxi.getVisibility());
                assertTrue("Taxi shortcut should remain visually smaller than the microphone.",
                        taxi.getWidth() < mic.getWidth() || taxi.getHeight() < mic.getHeight());
            });
        }
    }

    private static void collect(View view, List<View> target) {
        target.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), target);
        }
    }
}
