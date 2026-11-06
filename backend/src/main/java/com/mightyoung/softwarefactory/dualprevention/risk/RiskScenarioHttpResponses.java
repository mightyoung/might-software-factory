package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.ErrorResponse;
import com.mightyoung.softwarefactory.dualprevention.RiskScenarioHttpTransport;
import com.mightyoung.softwarefactory.identity.IdentityRequestIds;
import com.mightyoung.softwarefactory.identity.SessionSensitiveException;
import com.mightyoung.softwarefactory.identity.UnauthenticatedSessionException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.context.request.ServletWebRequest;
import static com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioContracts.*;

/** Stages final bounded bytes only. Late security/session headers and actual sending belong to the transport. */
final class RiskScenarioHttpResponses {
    private RiskScenarioHttpResponses() { }
    static void read(HttpServletRequest request,HttpServletResponse response,RiskScenarioHttpTransport transport,RiskScenarioReadService.EncodedResponse result)throws IOException{
        transport.stage(request,response,200,jsonHeaders("no-store"),result.size(),result::writeTo);
    }
    static void metadata(HttpServletRequest request,HttpServletResponse response,RiskScenarioHttpTransport transport,RiskScenarioMetadataService.MetadataOutcome result)throws IOException{
        boolean unchanged=notModified(request,result.etag());var headers=new LinkedHashMap<String,List<String>>();
        if(!unchanged)headers.put(HttpHeaders.CONTENT_TYPE,List.of("application/json;charset=UTF-8"));
        headers.put(HttpHeaders.ETAG,List.of(result.etag()));headers.put(HttpHeaders.CACHE_CONTROL,List.of("no-cache, private"));headers.put(HttpHeaders.VARY,List.of(HttpHeaders.COOKIE));
        transport.stage(request,response,unchanged?304:200,Collections.unmodifiableMap(headers),unchanged?0:result.size(),unchanged?output->{}:result::writeTo);
    }
    static void error(HttpServletRequest request,HttpServletResponse response,RiskScenarioHttpTransport transport,Exception error)throws IOException{
        int status;String code;boolean retryable=false;
        if(error instanceof Failure failure){status=failure.status();code=failure.code();retryable=status==503&&!"RESPONSE_BUDGET_EXCEEDED".equals(code);}
        else if(error instanceof SessionSensitiveException failure){status=switch(failure.reason()){case UNAVAILABLE->503;case CONFLICT->409;case REJECTED,NO_TRUSTED_LINEAGE->403;};code=status==503?"UNAVAILABLE":status==409?"CONFLICT":"FORBIDDEN";}
        else if(error instanceof UnauthenticatedSessionException){status=401;code="UNAUTHENTICATED";}
        else if(error instanceof AccessDeniedException){status=403;code="FORBIDDEN";}
        else{status=503;code="UNAVAILABLE";}
        var trace=IdentityRequestIds.get(request).traceId();var encoded=encodeOwnedResponse(new ErrorResponse(code,"请求未完成",trace.toString(),retryable,List.of(),null),ResponseBudget.ERROR);
        transport.stage(request,response,status,jsonHeaders("no-store"),encoded.size(),encoded::writeTo);
    }
    private static Map<String,List<String>> jsonHeaders(String cache){return Map.of(HttpHeaders.CONTENT_TYPE,List.of("application/json;charset=UTF-8"),HttpHeaders.CACHE_CONTROL,List.of(cache));}
    /** Only called after the metadata service has returned current authorized output. */
    private static boolean notModified(HttpServletRequest request,String etag){
        var values=new ArrayList<String>();var headers=request.getHeaders(HttpHeaders.IF_NONE_MATCH);int bytes=0;
        if(headers!=null)while(headers.hasMoreElements()){String value=headers.nextElement();if(value==null||value.length()>8192)throw invalid();bytes+=value.getBytes(StandardCharsets.UTF_8).length+(values.isEmpty()?0:1);if(bytes>8192)throw invalid();values.add(value);}
        if(values.isEmpty())return false;String combined=String.join(",",values);int start=ows(combined,0);
        if(start<combined.length()&&combined.charAt(start)=='*'){if(values.size()!=1||ows(combined,start+1)!=combined.length())throw invalid();return true;}
        validateTags(combined);
        // The evaluation view exposes only the contracted conditional header and has no response to mutate.
        var view=new HttpServletRequestWrapper(request){
            @Override public String getHeader(String name){return HttpHeaders.IF_NONE_MATCH.equalsIgnoreCase(name)?combined:null;}
            @Override public Enumeration<String> getHeaders(String name){return HttpHeaders.IF_NONE_MATCH.equalsIgnoreCase(name)?Collections.enumeration(List.of(combined)):Collections.emptyEnumeration();}
            @Override public long getDateHeader(String name){return -1;}
        };
        return new ServletWebRequest(view).checkNotModified(etag);
    }
    private static int ows(String value,int offset){while(offset<value.length()&&(value.charAt(offset)==' '||value.charAt(offset)=='\t'))offset++;return offset;}
    private static void validateTags(String value){int at=ows(value,0);if(at==value.length())throw invalid();
        while(at<value.length()){
            if(value.startsWith("W/",at))at+=2;if(at==value.length()||value.charAt(at++)!='\"')throw invalid();boolean closed=false;
            while(at<value.length()){char c=value.charAt(at++);if(c=='\"'){closed=true;break;}if(c<0x21||c==0x7f||c>0xff)throw invalid();}
            if(!closed)throw invalid();at=ows(value,at);if(at==value.length())return;if(value.charAt(at++)!=',')throw invalid();at=ows(value,at);if(at==value.length())throw invalid();
        }
    }
}
