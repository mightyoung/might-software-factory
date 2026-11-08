package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinition.Use;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.Metadata;
import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization.ActionScope;
import com.mightyoung.softwarefactory.identity.CapturedHumanRequest;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import static com.mightyoung.softwarefactory.authorization.RegisteredEntityAction.*;
import static com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioContracts.*;

/** Current risk authority precedes contextual validators; HTTP alone interprets conditional headers and 304. */
public final class RiskScenarioMetadataService {
    private static final String POLICY_VERSION="risk-metadata-v1";
    private final RiskScenarioReadService reads;
    private final RiskScenarioDefinitionProvider provider;
    private final String schemaChecksum;
    public RiskScenarioMetadataService(RiskScenarioReadService reads,RiskScenarioDefinitionProvider provider) {
        this.reads=Objects.requireNonNull(reads);this.provider=Objects.requireNonNull(provider);
        // Immutable public content only, without identity or per-request IDs. No authority is issued here.
        schemaChecksum=checksum(encodeOwnedResponse(RiskScenarioEntitySchema.schema(),ResponseBudget.METADATA));
    }
    public MetadataOutcome schema(CapturedHumanRequest request,UUID requestId,UUID trace) {
        var meta=metadata(requestId,trace);
        return reads.inReadSnapshot(request,snapshot->{
            var scope=snapshot.authorize(RISK_SCENARIO_READ);long applicationVersion=snapshot.applicationVersion(scope);
            var schema=RiskScenarioEntitySchema.schema();var output=encodeOwnedResponse(new Response<>(schema,meta),ResponseBudget.METADATA);
            String etag=etag("SCHEMA",Use.READ,scope,applicationVersion,schema.schemaVersion(),schema.schemaVersion(),schemaChecksum);
            snapshot.connection(scope);return new MetadataOutcome(etag,output);
        });
    }
    public MetadataOutcome definition(CapturedHumanRequest request,Use use,UUID requestId,UUID trace) {
        Objects.requireNonNull(use);var meta=metadata(requestId,trace);
        return reads.inReadSnapshot(request,snapshot->{
            var scope=snapshot.authorize(use==Use.READ?RISK_SCENARIO_READ:RISK_SCENARIO_CREATE);
            long applicationVersion=snapshot.applicationVersion(scope);var publication=provider.publication(use);
            if(publication==null||publication.definition()==null||publication.definition().use()!=use
                    ||publication.checksum()==null||!publication.checksum().matches("[0-9a-f]{64}"))throw new Failure(503);
            var definition=publication.definition();var output=encodeOwnedResponse(new Response<>(definition,meta),ResponseBudget.METADATA);
            String etag=etag("DEFINITION",use,scope,applicationVersion,definition.schemaVersion(),definition.definitionVersion(),publication.checksum());
            snapshot.connection(scope);return new MetadataOutcome(etag,output);
        });
    }
    /** Final bounded JSON plus a semantic validator; it cannot bypass the next request's current authorization. */
    public static final class MetadataOutcome {
        private final String etag;private final EncodedOutput encoded;
        private MetadataOutcome(String etag,EncodedOutput encoded){this.etag=Objects.requireNonNull(etag);this.encoded=Objects.requireNonNull(encoded);}
        public String etag(){return etag;}
        public int size(){return encoded.size();}
        /** Compatibility/testing only; production HTTP should use writeTo. */
        public byte[] body(){byte[] result=new byte[encoded.size()];encoded.appendTo(result,0);return result;}
        public void writeTo(OutputStream output)throws IOException{encoded.writeTo(output);}
    }
    private static Metadata metadata(UUID requestId,UUID trace){Objects.requireNonNull(requestId);Objects.requireNonNull(trace);return new Metadata(requestId.toString(),trace.toString(),SCHEMA_VERSION);}
    private static String checksum(EncodedOutput output){
        var digest=digest();try{output.writeTo(new DigestOutputStream(OutputStream.nullOutputStream(),digest));return HexFormat.of().formatHex(digest.digest());}
        catch(IOException failure){throw new Failure(503);}
    }
    private static String etag(String kind,Use use,ActionScope scope,long applicationVersion,String schemaVersion,String publicationVersion,String checksum){
        var digest=digest();try{
            var output=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),digest));
            frame(output,POLICY_VERSION);frame(output,ENTITY_KEY);frame(output,kind);frame(output,use.name());frame(output,scope.action().permissionCode());
            frame(output,schemaVersion);frame(output,publicationVersion);frame(output,checksum);var context=scope.context();
            for(Object value:new Object[]{context.tenantId(),context.accountId(),context.memberId(),context.actorId(),context.tenantAuthEpoch(),context.memberAuthEpoch(),context.credentialVersion(),applicationVersion})frame(output,value.toString());
            var organizations=scope.organizationIds().stream().map(UUID::toString).sorted().toList();frame(output,Integer.toString(organizations.size()));for(var organization:organizations)frame(output,organization);
            return "W/\""+HexFormat.of().formatHex(digest.digest())+"\"";
        }catch(IOException failure){throw new Failure(503);}
    }
    private static void frame(DataOutputStream output,String value)throws IOException{byte[] bytes=Objects.requireNonNull(value).getBytes(StandardCharsets.UTF_8);output.writeInt(bytes.length);output.write(bytes);}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException failure){throw new Failure(503);}}
}
