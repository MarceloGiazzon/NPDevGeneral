package com.finalexec.npdev.model;

public record RuntimeRefreshStatus(
        boolean refreshSupported,
        boolean restartRequired,
        String mode,
        String status,
        String message
) {
}
