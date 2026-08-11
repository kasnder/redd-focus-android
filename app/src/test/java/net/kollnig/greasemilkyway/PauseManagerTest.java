package net.kollnig.greasemilkyway;

import android.content.Context;

import net.kollnig.distractionlib.FilterRule;
import net.kollnig.distractionlib.FilterRuleParser;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class PauseManagerTest {

    @Test
    public void absolutePauseWritesBothPackageStateValues() {
        Context context = RuntimeEnvironment.getApplication();
        long until = System.currentTimeMillis() + 60_000L;

        PauseManager.applyPackagePauseUntil(context, "com.example.app", until);

        ServiceConfig config = new ServiceConfig(context);
        assertTrue(config.isPackageDisabled("com.example.app"));
        assertEquals(until, config.getPackagePausedUntil("com.example.app"));
    }

    @Test
    public void durationPauseUsesTheRequestedDuration() {
        Context context = RuntimeEnvironment.getApplication();
        long before = System.currentTimeMillis();

        long until = PauseManager.applyPackagePause(context, "com.example.duration", 15);

        assertTrue(until >= before + 15 * 60_000L);
        assertTrue(until <= System.currentTimeMillis() + 15 * 60_000L);
    }

    @Test
    public void nextLocalMidnightIsTheFollowingLocalDayAtMidnight() {
        Calendar now = Calendar.getInstance();
        now.set(2026, Calendar.MARCH, 29, 13, 30, 0);
        now.set(Calendar.MILLISECOND, 0);

        Calendar midnight = Calendar.getInstance();
        midnight.setTimeInMillis(PauseManager.nextLocalMidnightMillis(now.getTimeInMillis()));

        assertEquals(0, midnight.get(Calendar.HOUR_OF_DAY));
        assertEquals(0, midnight.get(Calendar.MINUTE));
        assertEquals(30, midnight.get(Calendar.DAY_OF_MONTH));
    }

    @Test
    public void batchPauseWritesEveryPackage() {
        Context context = RuntimeEnvironment.getApplication();
        long until = System.currentTimeMillis() + 60_000L;

        PauseManager.applyPackagePauses(context, Arrays.asList("com.example.one", "com.example.two"),
                until);

        ServiceConfig config = new ServiceConfig(context);
        assertEquals(until, config.getPackagePausedUntil("com.example.one"));
        assertEquals(until, config.getPackagePausedUntil("com.example.two"));
    }

    @Test
    public void rulePauseTurnsOffTheRuleUntilTheRequestedDeadline() {
        Context context = RuntimeEnvironment.getApplication();
        FilterRule rule = new FilterRuleParser().parseRules(new String[]{
                "com.example.app##viewId=com.example.app:id/feed"
        }).get(0);
        ServiceConfig config = new ServiceConfig(context);
        config.setRuleEnabled(rule, true);
        long until = System.currentTimeMillis() + 60_000L;

        PauseManager.applyRulePausesUntil(context, Collections.singletonList(rule), until);

        assertEquals(until, config.getRulePausedUntil(rule));
        assertFalse(rule.enabled);
        assertFalse(RuleRows.isRowEnabled(Collections.singletonList(rule)));
        assertTrue(rule.isPaused);
        assertEquals(until, rule.pausedUntil);
    }
}
