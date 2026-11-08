package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationEntitySchemas.Root;
import com.mightyoung.softwarefactory.identity.CapturedHumanRequest;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

public final class AssetFoundationQueryService {
    private final AssetFoundationReadService reads;
    private final AssetFoundationQueryRepository repository;
    private final AssetFoundationQueryValidator validator=new AssetFoundationQueryValidator();
    public AssetFoundationQueryService(AssetFoundationReadService reads,AssetFoundationQueryRepository repository){
        this.reads=Objects.requireNonNull(reads);this.repository=Objects.requireNonNull(repository);
    }
    AssetFoundationQueryContracts.Page query(CapturedHumanRequest request,Root root,JsonNode input){
        return reads.inReadSnapshot(request,snapshot->{
            var scope=snapshot.authorize(root.readAction());
            var plan=validator.validate(root,input);
            var result=repository.query(root,plan,scope);
            snapshot.requireActive();return result;
        });
    }
}
