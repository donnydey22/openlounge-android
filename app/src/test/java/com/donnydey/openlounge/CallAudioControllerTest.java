package com.donnydey.openlounge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CallAudioControllerTest {
    @Test
    public void startsVoiceSessionOnceAndAppliesRouteChanges() {
        FakePlatform platform = new FakePlatform(true);
        CallAudioController controller = new CallAudioController(platform);

        controller.setActive(true, true);
        controller.setActive(true, false);

        assertTrue(controller.isActive());
        assertEquals(Arrays.asList(
                "mode:communication", "focus:request", "route:speaker", "route:earpiece"),
                platform.events);
    }

    @Test
    public void endsByClearingRouteAbandoningFocusAndRestoringNormalMode() {
        FakePlatform platform = new FakePlatform(true);
        CallAudioController controller = new CallAudioController(platform);

        controller.setActive(true, true);
        controller.setActive(false, true);

        assertFalse(controller.isActive());
        assertEquals(Arrays.asList(
                "mode:communication", "focus:request", "route:speaker",
                "route:clear", "focus:abandon", "mode:normal"),
                platform.events);
    }

    @Test
    public void deniedFocusDoesNotBlockRouteOrLeaveFocusToAbandon() {
        FakePlatform platform = new FakePlatform(false);
        CallAudioController controller = new CallAudioController(platform);

        controller.setActive(true, true);
        controller.setActive(false, true);

        assertFalse(controller.isActive());
        assertEquals(Arrays.asList(
                "mode:communication", "focus:request", "route:speaker",
                "route:clear", "mode:normal"),
                platform.events);
    }

    private static final class FakePlatform implements CallAudioController.Platform {
        final List<String> events = new ArrayList<>();
        final boolean grantsFocus;

        FakePlatform(boolean grantsFocus) {
            this.grantsFocus = grantsFocus;
        }

        @Override
        public void enterCommunicationMode() {
            events.add("mode:communication");
        }

        @Override
        public boolean requestVoiceFocus() {
            events.add("focus:request");
            return grantsFocus;
        }

        @Override
        public void applyRoute(boolean speakerRequested) {
            events.add(speakerRequested ? "route:speaker" : "route:earpiece");
        }

        @Override
        public void clearRoute() {
            events.add("route:clear");
        }

        @Override
        public void abandonVoiceFocus() {
            events.add("focus:abandon");
        }

        @Override
        public void restoreNormalMode() {
            events.add("mode:normal");
        }
    }
}
