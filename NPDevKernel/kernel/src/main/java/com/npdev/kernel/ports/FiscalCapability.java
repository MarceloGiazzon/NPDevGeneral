package com.npdev.kernel.ports;

public interface FiscalCapability {
    Object generateXml(Object document);

    Object sign(Object xmlArtifact);

    Object transmit(Object signedArtifact);

    Object queryStatus(Object transmissionRef);
}

