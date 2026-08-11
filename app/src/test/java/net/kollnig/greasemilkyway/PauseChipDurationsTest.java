package net.kollnig.greasemilkyway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PauseChipDurationsTest {

    @Test
    public void configuredDefaultIsAlwaysTheMiddleOfIncreasingChoices() {
        for (int defaultMinutes = 1; defaultMinutes <= 120; defaultMinutes++) {
            long[] durations = PauseChipDurations.aroundDefault(defaultMinutes);

            assertEquals(3, durations.length);
            assertTrue(durations[0] > 0);
            assertTrue(durations[0] < durations[1]);
            assertTrue(durations[1] < durations[2]);
            assertEquals(defaultMinutes * 60_000L, durations[1]);
        }
    }
}
