package net.kollnig.greasemilkyway;

import android.content.Context;

import androidx.appcompat.app.AlertDialog;

/** Keeps temporary pause visually and semantically ahead of permanent disable. */
final class PauseOrDisableDialog {
    private PauseOrDisableDialog() {
    }

    static void show(Context context, ServiceConfig config, Runnable pause,
                     Runnable disablePermanently, Runnable cancel) {
        int minutes = config.getPauseDurationMins();
        String message = context.getResources().getQuantityString(
                R.plurals.pause_recommended_message, minutes, minutes);
        String pauseLabel = context.getResources().getQuantityString(
                R.plurals.pause_for_minutes, minutes, minutes);

        new AlertDialog.Builder(context)
                .setTitle(R.string.pause_or_disable_title)
                .setMessage(message)
                .setPositiveButton(pauseLabel, (dialog, which) -> pause.run())
                .setNeutralButton(R.string.disable_permanently_action,
                        (dialog, which) -> disablePermanently.run())
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> cancel.run())
                .setOnCancelListener(dialog -> cancel.run())
                .show();
    }
}
