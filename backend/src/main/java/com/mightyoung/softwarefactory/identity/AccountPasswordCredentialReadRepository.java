package com.mightyoung.softwarefactory.identity;

import java.util.Optional;

/** Read-only lookup for the local password authentication flow. */
public interface AccountPasswordCredentialReadRepository {

    Optional<LocalPasswordCredential> findByLoginKey(String normalizedLoginKey);
}
