package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.identity.CapturedHumanRequest;
import java.util.Objects;
import java.util.UUID;
import static com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationHistoryContracts.*;

public final class AssetFoundationHistoryService {
    private final AssetFoundationReadService reads;
    private final AssetFoundationHistoryRepository repository;
    public AssetFoundationHistoryService(AssetFoundationReadService reads,AssetFoundationHistoryRepository repository){this.reads=Objects.requireNonNull(reads);this.repository=Objects.requireNonNull(repository);}
    Page page(CapturedHumanRequest request,Kind kind,UUID parent,Request body){return reads.inReadSnapshot(request,snapshot->{
        var scope=snapshot.authorize(kind.read);var result=repository.page(kind,parent,body,scope);snapshot.requireActive();return result;
    });}
}
