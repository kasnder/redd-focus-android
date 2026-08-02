package net.kollnig.distractionlib;

import static org.junit.Assert.assertEquals;

import android.view.Gravity;

import org.junit.Test;

public class ElementPickerOverlayTest {

    @Test
    public void controlAndUndoBarsAlwaysUseOppositeEdges() {
        assertEquals(Gravity.BOTTOM | Gravity.START, ElementPickerOverlay.controlBarGravity(true));
        assertEquals(Gravity.TOP | Gravity.START, ElementPickerOverlay.undoBarGravity(true));
        assertEquals(Gravity.TOP | Gravity.START, ElementPickerOverlay.controlBarGravity(false));
        assertEquals(Gravity.BOTTOM | Gravity.START, ElementPickerOverlay.undoBarGravity(false));
    }
}
