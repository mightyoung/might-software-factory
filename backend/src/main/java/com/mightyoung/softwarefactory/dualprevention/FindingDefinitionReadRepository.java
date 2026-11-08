package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinition.Use;
import com.mightyoung.softwarefactory.applicationregistry.reference.ReferenceQueryContract.Page;
import com.mightyoung.softwarefactory.applicationregistry.reference.ReferenceQueryContract.Request;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Finding-owned current authorization and candidate data share one managed read snapshot. */
public interface FindingDefinitionReadRepository {
    <T> T inSnapshot(CurrentTenantContext context, Function<Snapshot, T> work);

    interface Snapshot {
        Authority authorize(Use use);
        Page organizations(Authority authority, Request request);
        void requireActive();
    }

    /** Values are useful only with the identical token retained by its issuing snapshot. */
    record Authority(Use use, CurrentTenantContext context, Set<UUID> organizationIds, long applicationVersion) {
        public Authority {
            Objects.requireNonNull(use); Objects.requireNonNull(context);
            organizationIds = Set.copyOf(organizationIds);
            if (organizationIds.isEmpty() || applicationVersion < 1) throw new IllegalArgumentException("Invalid authority");
        }
    }
}
