package com.mightyoung.softwarefactory.dualprevention.hazard;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import static com.mightyoung.softwarefactory.dualprevention.asset.AssetContracts.*;

/** Existence is an observation, never a professional risk grade. */
public final class HazardSourceContracts {
    private HazardSourceContracts() {}
    public enum ExistenceState { UNKNOWN, PRESENT, NOT_PRESENT }
    public record Create(String code, UUID organizationId, UUID workUnitId, String description,
                         Instant identifiedAt, ExistenceState existenceState) {
        public Create { code=com.mightyoung.softwarefactory.dualprevention.asset.AssetContracts.code(code); Objects.requireNonNull(organizationId); Objects.requireNonNull(workUnitId);
            text(description,20000,false); instant(identifiedAt); Objects.requireNonNull(existenceState); }
    }
    public record Update(long expectedVersion, String description, Instant identifiedAt, ExistenceState existenceState) {
        public Update { version(expectedVersion); text(description,20000,false); instant(identifiedAt); Objects.requireNonNull(existenceState); }
    }
    public record Detail(UUID id, UUID organizationId, long version, String code, UUID workUnitId,
                         String description, Instant identifiedAt, ExistenceState existenceState,Instant createdAt,Instant updatedAt,
                         String factorCodeRef,String factorVersion) {}
}
