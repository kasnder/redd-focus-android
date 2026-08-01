package net.kollnig.greasemilkyway;

import android.content.Context;

import net.kollnig.distractionlib.FilterRule;

import java.util.Calendar;
import java.util.Collections;

public final class PauseManager {
    private PauseManager() {
    }

    public static long applyPackagePause(Context context, String packageName) {
        ServiceConfig config = new ServiceConfig(context);
        return applyPackagePause(context, packageName, config.getPauseDurationMins());
    }

    /** Applies a duration-specific package pause. */
    public static long applyPackagePause(Context context, String packageName, int minutes) {
        if (minutes <= 0) {
            throw new IllegalArgumentException("Pause duration must be positive");
        }
        return applyPackagePauseUntil(context, packageName, durationUntil(minutes));
    }

    /** Applies a pause ending at an explicit absolute timestamp. */
    public static long applyPackagePauseUntil(Context context, String packageName, long until) {
        ServiceConfig config = new ServiceConfig(context);
        config.pausePackagesUntil(Collections.singletonList(packageName), until);
        notifyService();
        return until;
    }

    /** Applies a pause that ends at the next local midnight, including across daylight changes. */
    public static long applyPackagePauseUntilLocalMidnight(Context context, String packageName) {
        return applyPackagePauseUntil(context, packageName,
                nextLocalMidnightMillis(System.currentTimeMillis()));
    }

    /** Pauses several packages together and refreshes the service once after the batch is saved. */
    public static long applyPackagePauses(Context context, Iterable<String> packageNames,
                                          long until) {
        ServiceConfig config = new ServiceConfig(context);
        config.pausePackagesUntil(packageNames, until);
        notifyService();
        return until;
    }

    /** Pauses several packages for the configured default duration with one service refresh. */
    public static long applyPackagePauses(Context context, Iterable<String> packageNames) {
        return applyPackagePauses(context, packageNames,
                new ServiceConfig(context).getPauseDurationMins());
    }

    /** Pauses several packages for one explicit duration with one service refresh. */
    public static long applyPackagePauses(Context context, Iterable<String> packageNames,
                                          int minutes) {
        if (minutes <= 0) {
            throw new IllegalArgumentException("Pause duration must be positive");
        }
        return applyPackagePauses(context, packageNames, durationUntil(minutes));
    }

    /** Temporarily switches off blocking rules while preserving their automatic return. */
    public static long applyRulePauses(Context context, Iterable<FilterRule> rules) {
        ServiceConfig config = new ServiceConfig(context);
        return applyRulePausesUntil(context, rules,
                durationUntil(config.getPauseDurationMins()));
    }

    static long applyRulePausesUntil(Context context, Iterable<FilterRule> rules, long until) {
        if (until <= System.currentTimeMillis()) {
            throw new IllegalArgumentException("Pause expiry must be in the future");
        }
        ServiceConfig config = new ServiceConfig(context);
        for (FilterRule rule : rules) {
            if (rule == null || rule.isNavigation) {
                throw new IllegalArgumentException("A blocking rule is required");
            }
            config.setRuleEnabled(rule, false);
            config.setRulePausedUntil(rule, until);
            rule.enabled = false;
            rule.isPaused = true;
            rule.pausedUntil = until;
        }
        notifyService();
        return until;
    }

    private static long durationUntil(int minutes) {
        try {
            return Math.addExact(System.currentTimeMillis(), Math.multiplyExact(minutes, 60_000L));
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Pause duration is too large", e);
        }
    }

    static long nextLocalMidnightMillis(long nowMillis) {
        Calendar midnight = Calendar.getInstance();
        midnight.setTimeInMillis(nowMillis);
        midnight.add(Calendar.DATE, 1);
        midnight.set(Calendar.HOUR_OF_DAY, 0);
        midnight.set(Calendar.MINUTE, 0);
        midnight.set(Calendar.SECOND, 0);
        midnight.set(Calendar.MILLISECOND, 0);
        return midnight.getTimeInMillis();
    }

    private static void notifyService() {
        DistractionControlService service = DistractionControlService.getInstance();
        if (service != null) {
            service.updateRules();
        }
    }
}
