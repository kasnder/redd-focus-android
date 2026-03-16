package net.kollnig.distractionlib;

import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages overlay views for blocking content.
 */
public class OverlayManager {
    private static final String TAG = "OverlayManager";
    private final List<View> overlays = new CopyOnWriteArrayList<>();

    public int getOverlayCount() {
        return overlays.size();
    }

    public void addOverlay(View overlay, WindowManager.LayoutParams params, WindowManager windowManager,
                           Handler ui) {
        ui.post(() -> {
            try {
                windowManager.addView(overlay, params);
                overlays.add(overlay);
            } catch (Exception e) {
                Log.e(TAG, "Error adding overlay", e);
            }
        });
    }

    public void updateOverlay(View overlay, WindowManager.LayoutParams params,
                              WindowManager windowManager, Handler ui) {
        ui.post(() -> {
            try {
                if (overlay.getParent() != null) {
                    windowManager.updateViewLayout(overlay, params);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error updating overlay", e);
            }
        });
    }

    public void removeOverlay(View overlay, WindowManager windowManager, Handler ui) {
        ui.post(() -> {
            try {
                if (overlay.getParent() != null) {
                    windowManager.removeView(overlay);
                }
                overlays.remove(overlay);
            } catch (Exception e) {
                Log.e(TAG, "Error removing overlay", e);
            }
        });
    }

    public void clearOverlays(WindowManager windowManager, Handler ui) {
        if (overlays.isEmpty()) return;
        for (View v : new ArrayList<>(overlays)) {
            ui.post(() -> {
                try {
                    windowManager.removeView(v);
                } catch (Exception e) {
                    Log.e(TAG, "Error removing overlay", e);
                }
            });
            overlays.remove(v);
        }
    }

    public void forceClearOverlays(WindowManager windowManager) {
        if (overlays.isEmpty()) return;

        for (View v : new ArrayList<>(overlays)) {
            try {
                if (v.getParent() != null) {
                    windowManager.removeView(v);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error removing overlay", e);
            }
            overlays.remove(v);
        }
    }
}
