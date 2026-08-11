package net.kollnig.greasemilkyway;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.Intent;

import com.google.android.material.button.MaterialButton;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class AppDetailActivityTest {

    @Test
    public void configuredPauseDurationIsRenderedAsTheMiddleChip() {
        Context context = RuntimeEnvironment.getApplication();
        new ServiceConfig(context).setPauseDurationMins(5);
        Intent intent = new Intent(context, AppDetailActivity.class)
                .putExtra(AppDetailActivity.EXTRA_PACKAGE_NAME, "com.example.app");

        AppDetailActivity activity = Robolectric.buildActivity(AppDetailActivity.class, intent)
                .create().start().resume().visible().get();

        assertEquals("2 min", ((MaterialButton) activity.findViewById(R.id.pause_shorter))
                .getText().toString());
        assertEquals("5 min · default",
                ((MaterialButton) activity.findViewById(R.id.pause_default)).getText().toString());
        assertEquals("10 min", ((MaterialButton) activity.findViewById(R.id.pause_longer))
                .getText().toString());
    }
}
