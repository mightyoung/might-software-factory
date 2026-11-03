package com.mightyoung.softwarefactory.identity;

import static com.mightyoung.softwarefactory.identity.SessionSensitiveContracts.Enforcement;

/** Missing state, storage or required retained keys are unavailable, never OFF. */
public interface SessionAuthorityControlReader {
    Enforcement read();
}
