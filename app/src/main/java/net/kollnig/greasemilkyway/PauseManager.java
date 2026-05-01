package net.kollnig.greasemilkyway;

import android.content.Context;

public final class PauseManager {
    private PauseManager() {
    }

    public static long applyPackagePause(Context context, String packageName) {
        ServiceConfig config = new ServiceConfig(context);
        long until = System.currentTimeMillis() + (config.getPauseDurationMins() * 60_000L);

        config.setPackageDisabled(packageName, true);
        config.setPackagePausedUntil(packageName, until);
        notifyService();

        return until;
    }

    private static void notifyService() {
        DistractionControlService service = DistractionControlService.getInstance();
        if (service != null) {
            service.updateRules();
        }
    }
}
