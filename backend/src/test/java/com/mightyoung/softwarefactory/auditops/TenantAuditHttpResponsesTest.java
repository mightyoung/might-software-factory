package com.mightyoung.softwarefactory.auditops;

import com.mightyoung.softwarefactory.identity.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Fixed error wire and owned byte proof only; actual HTTP delivery belongs to the composition root. */
class TenantAuditHttpResponsesTest {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    @Test void knownFailuresHaveFixedBoundedRedactedWireAndServerTrace() throws Exception {
        var failures=List.<RuntimeException>of(
                new TenantAuditQueryContracts.Failure(TenantAuditQueryContracts.Code.BAD_REQUEST),
                new TenantAuditQueryContracts.Failure(TenantAuditQueryContracts.Code.REQUEST_TOO_LARGE),
                new TenantAuditQueryContracts.Failure(TenantAuditQueryContracts.Code.UNSUPPORTED_MEDIA_TYPE),
                new UnauthenticatedSessionException(),new AccessDeniedException("PRIVATE SQL"),
                new SessionSensitiveException(SessionSensitiveException.Reason.CONFLICT),
                new SessionSensitiveException(SessionSensitiveException.Reason.REJECTED),
                new SessionSensitiveException(SessionSensitiveException.Reason.NO_TRUSTED_LINEAGE),
                new SessionSensitiveException(SessionSensitiveException.Reason.UNAVAILABLE),
                new TenantAuditQueryService.Unavailable(),new org.springframework.dao.DataRetrievalFailureException("PRIVATE SQL"),
                new org.jooq.exception.DataAccessException("PRIVATE SQL"),new org.springframework.transaction.TransactionSystemException("PRIVATE COMMIT"));
        int[] statuses={400,413,415,401,403,409,403,403,503,503,503,503,503};
        String[] codes={"BAD_REQUEST","REQUEST_TOO_LARGE","UNSUPPORTED_MEDIA_TYPE","UNAUTHENTICATED","FORBIDDEN","CONFLICT","FORBIDDEN","FORBIDDEN","UNAVAILABLE","UNAVAILABLE","UNAVAILABLE","UNAVAILABLE","UNAVAILABLE"};
        for(int i=0;i<failures.size();i++){
            var request=new MockHttpServletRequest();request.addHeader("X-Trace-Id","untrusted");var response=new MockHttpServletResponse();var status=new int[1];var bytes=new ByteArrayOutputStream();
            TenantAuditHttpResponses.error(request,response,(req,res,s,headers,length,writer)->{
                status[0]=s;assertEquals(Set.of("Content-Type","Cache-Control"),headers.keySet());assertEquals(List.of("no-store"),headers.get("Cache-Control"));assertEquals(List.of("application/json;charset=UTF-8"),headers.get("Content-Type"));
                assertTrue(length<=8192);writer.writeTo(bytes);assertEquals(length,bytes.size());
            },failures.get(i));
            assertEquals(statuses[i],status[0]);var tree=JSON.readTree(bytes.toByteArray());var fields=new HashSet<String>();tree.properties().forEach(entry->fields.add(entry.getKey()));
            assertEquals(Set.of("code","message","traceId","retryable","fieldErrors","reason"),fields);assertEquals(codes[i],tree.get("code").stringValue());assertEquals("请求未完成",tree.get("message").stringValue());
            assertEquals(IdentityRequestIds.get(request).traceId().toString(),tree.get("traceId").stringValue());assertFalse(tree.get("retryable").booleanValue());assertTrue(tree.get("fieldErrors").isArray());assertEquals(0,tree.get("fieldErrors").size());assertTrue(tree.get("reason").isNull());
            assertFalse(bytes.toString(java.nio.charset.StandardCharsets.UTF_8).contains("PRIVATE"));assertEquals(200,response.getStatus());assertEquals(0,response.getContentAsByteArray().length);
        }
    }
    @Test void hostileSinkCannotChangeOwnedErrorBytesAndDeliveryExceptionsStayOriginal() throws Exception {
        var writer=new AtomicReference<TenantAuditHttpTransport.BodyWriter>();TenantAuditHttpResponses.error(new MockHttpServletRequest(),new MockHttpServletResponse(),(a,b,c,d,e,f)->writer.set(f),new AccessDeniedException("private"));
        var original=new ByteArrayOutputStream();writer.get().writeTo(original);
        writer.get().writeTo(new OutputStream(){@Override public void write(int value){fail("bulk expected");}@Override public void write(byte[] value,int offset,int length){Arrays.fill(value,(byte)0);}});
        var after=new ByteArrayOutputStream();writer.get().writeTo(after);assertArrayEquals(original.toByteArray(),after.toByteArray());
        var io=new IOException("delivery");assertSame(io,assertThrows(IOException.class,()->writer.get().writeTo(new OutputStream(){@Override public void write(int value)throws IOException{throw io;}})));
        var runtime=new IllegalStateException("delivery");assertSame(runtime,assertThrows(IllegalStateException.class,()->writer.get().writeTo(new OutputStream(){@Override public void write(int value){throw runtime;}})));
    }
    @Test void errorTransportKnownRuntimeFailureIsNotCaughtOrRestaged() {
        var calls=new int[1];var failure=new AccessDeniedException("transport");
        assertSame(failure,assertThrows(AccessDeniedException.class,()->TenantAuditHttpResponses.error(new MockHttpServletRequest(),new MockHttpServletResponse(),(a,b,c,d,e,f)->{calls[0]++;throw failure;},new TenantAuditQueryService.Unavailable())));assertEquals(1,calls[0]);
    }
}
