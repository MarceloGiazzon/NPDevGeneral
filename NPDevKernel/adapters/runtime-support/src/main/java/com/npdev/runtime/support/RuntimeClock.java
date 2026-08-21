package com.npdev.runtime.support;

import java.time.OffsetDateTime;

public interface RuntimeClock {
    OffsetDateTime nowUtc();
}
