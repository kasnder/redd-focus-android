package net.kollnig.distractionlib;

import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages overlay views for blocking content.
 *
 * All methods must be called on the main thread (the accessibility service
 * already dispatches events there). Operations on WindowManager and the
 * tracking list are synchronous so callers can rely on consistent state
 * immediately after each call — this avoids races where a queued addView
 * runs after a clear, leaving an orphan view attached but untracked.
 */
public class OverlayManager {
    private static final String TAG = "OverlayManager";
    private final List<View> overlays = new ArrayList<>();

    public int getOverlayCount() {
        return overlays.size();
    }

    public void addOverlay(View overlay, WindowManager.LayoutParams params, WindowManager windowManager) {
        try {
            windowManager.addView(overlay, params);
            overlays.add(overlay);
        } catch (Exception e) {
            Log.e(TAG, "Error adding overlay", e);
        }
    }

    public void updateOverlay(View overlay, WindowManager.LayoutParams params,
                              WindowManager windowManager) {
        try {
            if (overlay.getParent() != null) {
                windowManager.updateViewLayout(overlay, params);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating overlay", e);
        }
    }

    public void removeOverlay(View overlay, WindowManager windowManager) {
        try {
            if (overlay.getParent() != null) {
                windowManager.removeView(overlay);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error removing overlay", e);
        }
        overlays.remove(overlay);
    }

    public void clearOverlays(WindowManager windowManager) {
        if (overlays.isEmpty()) return;
        for (View v : new ArrayList<>(overlays)) {
            try {
                if (v.getParent() != null) {
                    windowManager.removeView(v);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error removing overlay", e);
            }
        }
        overlays.clear();
    }
}
