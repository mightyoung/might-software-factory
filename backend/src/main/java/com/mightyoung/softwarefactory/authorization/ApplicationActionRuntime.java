package com.mightyoung.softwarefactory.authorization;

import com.mightyoung.softwarefactory.identity.HumanSessionAuthorityTransaction;

/** The neutral graph shares one actual borrowed-boundary owner and guard with its action issuer. */
public record ApplicationActionRuntime(HumanSessionAuthorityTransaction callers, AuthorizationWriteGuard guard,
                                       ApplicationActionAuthorization authorization) { }
