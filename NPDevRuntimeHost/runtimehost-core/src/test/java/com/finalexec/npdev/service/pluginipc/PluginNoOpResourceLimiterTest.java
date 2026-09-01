package com.finalexec.npdev.service.pluginipc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * SEC-3 Model B step 4 (design doc section 3): the degrade-to-timeout-only posture when no real per-OS
 * resource-limiting mechanism is available. Deliberately constructs {@link PluginNoOpResourceLimiter} directly
 * rather than going through {@link PluginProcessResourceLimiter#forCurrentOs()} -- the real factory's
 * answer depends on the actual host OS/environment (e.g. it legitimately returns a real, available Linux
 * limiter on a Linux CI box), which would make this test's outcome host-dependent instead of a
 * deterministic proof of the degrade path itself.
 */
class PluginNoOpResourceLimiterTest {

    private final List<LogRecord> captured = new ArrayList<>();
    private Handler captureHandler;

    @BeforeEach
    void attachLogCapture() {
        captureHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                captured.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger.getLogger(PluginNoOpResourceLimiter.class.getName()).addHandler(captureHandler);
    }

    @AfterEach
    void detachLogCapture() {
        Logger.getLogger(PluginNoOpResourceLimiter.class.getName()).removeHandler(captureHandler);
    }

    @Test
    void isNeverAvailable() {
        assertFalse(new PluginNoOpResourceLimiter().isAvailable());
    }

    @Test
    void passesTheCommandThroughUnchanged() {
        List<String> command = List.of("java", "-cp", "x", "Main");
        assertSame(command, new PluginNoOpResourceLimiter().wrapCommand(command, new PluginProcessResourceLimits(64, 50)));
    }

    @Test
    void neverWarnsWhenNoLimitsWereActuallyRequested() {
        PluginNoOpResourceLimiter limiter = new PluginNoOpResourceLimiter();
        limiter.wrapCommand(List.of("java"), PluginProcessResourceLimits.NONE);
        assertEquals(0, captured.size(), "no requested limits is today's status quo, not news");
    }

    @Test
    void warnsExactlyOnceEvenAcrossMultipleRealLimitRequests() {
        PluginNoOpResourceLimiter limiter = new PluginNoOpResourceLimiter();
        PluginProcessResourceLimits limits = new PluginProcessResourceLimits(64, null);

        limiter.wrapCommand(List.of("java"), limits);
        limiter.wrapCommand(List.of("java"), limits);

        long warnings = captured.stream().filter(record -> record.getLevel() == Level.WARNING).count();
        assertEquals(1, warnings, "must warn loudly at least once, but must not spam on every subsequent invocation");
    }
}
