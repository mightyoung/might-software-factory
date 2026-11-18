package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization.ActionScope;
import com.mightyoung.softwarefactory.directory.DirectoryReferenceReadPort;
import com.mightyoung.softwarefactory.identity.CapturedHumanRequest;
import java.util.Objects;
import java.util.UUID;
import static com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationSelectorContracts.*;

/** Selectors use the same callback for owner action, reference action, directory and page SQL. */
public final class AssetFoundationSelectorService {
    private final AssetFoundationReadService reads;
    private final AssetFoundationSelectorRepository repository;
    private final DirectoryReferenceReadPort directory;
    public AssetFoundationSelectorService(AssetFoundationReadService reads, AssetFoundationSelectorRepository repository,
            DirectoryReferenceReadPort directory) {
        this.reads = Objects.requireNonNull(reads); this.repository = Objects.requireNonNull(repository);
        this.directory = Objects.requireNonNull(directory);
    }

    Page query(CapturedHumanRequest request, Selector selector, UUID parentId, Request query) {
        return reads.inReadSnapshot(request, snapshot -> {
            ActionScope owner = snapshot.authorize(selector.owner);
            AssetFoundationSelectorRepository.Parent parent = null;
            if (selector.bind()) {
                if (parentId == null) throw new AssetFoundationFailure(400);
                Candidate root = switch (selector) {
                    case BIND_WORKSHOP -> Candidate.LINE;
                    case BIND_EQUIPMENT -> Candidate.STAGE;
                    case BIND_ORGANIZATION, BIND_POSITION -> Candidate.WORK_UNIT;
                    default -> throw new AssetFoundationFailure(503);
                };
                parent = repository.parent(root, owner, parentId);
            }
            if (selector.anchoredCreate()) activeOrganization(owner, query.organizationId());
            Page result;
            if (selector.candidate != null) {
                ActionScope reference = snapshot.authorize(selector.referencedRead);
                UUID excluded = selector == Selector.BIND_WORKSHOP ? parent.primaryWorkshopId() : null;
                result = repository.candidates(selector.candidate, reference, query, excluded);
            } else if (selector == Selector.BIND_POSITION) {
                activeOrganization(owner, query.organizationId());
                result = Page.directory(directory.queryPositionsAt(owner.context().tenantId(), query.organizationId(),
                        owner.organizationIds(), query.directoryQuery(), owner.observedAt()));
            } else {
                result = Page.directory(directory.queryOrganizationsAt(owner.context().tenantId(), owner.organizationIds(),
                        query.directoryQuery(), owner.observedAt()));
            }
            snapshot.requireActive();
            return result;
        });
    }

    private void activeOrganization(ActionScope owner, UUID id) {
        if (id == null || !owner.organizationIds().contains(id)
                || directory.findOrganizationAt(owner.context().tenantId(), id, owner.observedAt())
                        .filter(value -> value.active()).isEmpty()) throw new AssetFoundationFailure(403);
    }
}
