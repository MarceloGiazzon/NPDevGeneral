package com.npdev.kernel.ports;

public interface SignatureCapability {
    Object sign(Object document);

    Object verify(Object signedDocument);
}

