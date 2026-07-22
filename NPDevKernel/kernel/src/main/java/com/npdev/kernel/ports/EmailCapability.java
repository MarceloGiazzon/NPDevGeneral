package com.npdev.kernel.ports;

/**
 * LNCH-11: typed kernel port for the "EmailCapability" contract (see
 * {@code CapabilityContractCatalog}). Adapters (mail-inproc, mail-smtp) implement this in
 * addition to the generic {@link CapabilityAdapter} dispatch contract, so callers with a direct
 * reference to the bean (rather than going through a flow's capability-call step) get a typed
 * send() instead of an untyped payload map.
 */
public interface EmailCapability {
    MailSendResult send(MailMessage message);
}
