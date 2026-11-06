package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.Metadata;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

final class AssetFoundationReadResponses {
    private AssetFoundationReadResponses() {}
    record Response<T>(T data,Metadata meta) {}
    static ResponseEntity<?> success(Object data){UUID trace=UUID.randomUUID();return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new Response<>(data,AssetFoundationHttpResponses.metadata(trace)));}
    static ResponseEntity<?> failure(int status){return AssetFoundationHttpResponses.error(status,UUID.randomUUID());}
}
