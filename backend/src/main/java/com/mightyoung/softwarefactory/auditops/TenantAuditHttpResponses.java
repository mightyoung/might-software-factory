package com.mightyoung.softwarefactory.auditops;

import com.mightyoung.softwarefactory.identity.IdentityRequestIds;
import com.mightyoung.softwarefactory.identity.SessionSensitiveException;
import com.mightyoung.softwarefactory.identity.UnauthenticatedSessionException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.json.JsonMapper;

/** Fixed error wire and owned bounded bytes; no DTO re-encoding in the transport. */
final class TenantAuditHttpResponses {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Map<String,List<String>> HEADERS=Map.of(
            HttpHeaders.CONTENT_TYPE,List.of("application/json;charset=UTF-8"),
            HttpHeaders.CACHE_CONTROL,List.of("no-store"));
    private TenantAuditHttpResponses() { }
    static void read(HttpServletRequest request,HttpServletResponse response,TenantAuditHttpTransport transport,
            TenantAuditQueryService.EncodedResult result) throws IOException {
        transport.stage(request,response,200,HEADERS,result.size(),result::writeTo);
    }
    static void error(HttpServletRequest request,HttpServletResponse response,TenantAuditHttpTransport transport,
            RuntimeException failure) throws IOException {
        int status;String code;
        if(failure instanceof TenantAuditQueryContracts.Failure rejected){status=rejected.status();code=rejected.code().name();}
        else if(failure instanceof SessionSensitiveException rejected){
            status=switch(rejected.reason()){case UNAVAILABLE->503;case CONFLICT->409;case REJECTED,NO_TRUSTED_LINEAGE->403;};
            code=status==503?"UNAVAILABLE":status==409?"CONFLICT":"FORBIDDEN";
        } else if(failure instanceof UnauthenticatedSessionException){status=401;code="UNAUTHENTICATED";}
        else if(failure instanceof AccessDeniedException){status=403;code="FORBIDDEN";}
        else {status=503;code="UNAVAILABLE";}
        var output=new TenantAuditQueryEncoder.LimitedOutput(TenantAuditQueryContracts.ERROR_BYTES);
        try { JSON.writeValue(output,new ErrorResponse(code,"请求未完成",IdentityRequestIds.get(request).traceId().toString(),false,List.of(),null)); }
        catch(IOException | tools.jackson.core.JacksonException encodingFailure){throw new TenantAuditQueryEncoder.EncodingFailure();}
        var encoded=output.seal();
        transport.stage(request,response,status,HEADERS,encoded.size(),encoded::writeTo);
    }
    private record ErrorResponse(String code,String message,String traceId,boolean retryable,List<String> fieldErrors,String reason) { }
}
