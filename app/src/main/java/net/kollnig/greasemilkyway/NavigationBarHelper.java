package net.kollnig.greasemilkyway;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Build;
import android.view.View;
import android.view.WindowInsetsController;

/**
 * Shared utility for configuring navigation bar color and icon appearance
 * to match the app background across all activities.
 */
final class NavigationBarHelper {
    private NavigationBarHelper() {}

    static void setup(Activity activity) {
        int backgroundColor = activity.getResources().getColor(R.color.background_main, activity.getTheme());
        activity.getWindow().setNavigationBarColor(backgroundColor);

        boolean isLightMode = (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = activity.getWindow().getInsetsController();
            if (controller != null) {
                int appearance = isLightMode ? WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS : 0;
                controller.setSystemBarsAppearance(appearance,
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            View decorView = activity.getWindow().getDecorView();
            int flags = decorView.getSystemUiVisibility();
            if (isLightMode) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            decorView.setSystemUiVisibility(flags);
        }
    }
}
