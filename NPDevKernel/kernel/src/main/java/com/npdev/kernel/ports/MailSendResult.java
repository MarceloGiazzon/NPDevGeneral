package com.npdev.kernel.ports;

/** LNCH-11: outcome of a {@link EmailCapability#send(MailMessage)} call. */
public record MailSendResult(String deliveryId, String status, String provider) {
}
