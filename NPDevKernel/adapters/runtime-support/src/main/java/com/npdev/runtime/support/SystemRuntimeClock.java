package com.npdev.runtime.support;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public final class SystemRuntimeClock implements RuntimeClock {
    @Override
    public OffsetDateTime nowUtc() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
