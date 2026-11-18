package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.dualprevention.RiskScenarioHttpTransport;
import com.mightyoung.softwarefactory.identity.IdentityRequestIds;
import com.mightyoung.softwarefactory.identity.SessionSensitiveRequests;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.web.bind.annotation.*;

/** Current authorized outcome always precedes conditional-cache parsing; no shared catalog registration. */
@RestController
public final class RiskScenarioMetadataController {
    private final RiskScenarioMetadataService service;private final RiskScenarioParser parser;private final SessionSensitiveRequests requests;private final RiskScenarioHttpTransport transport;
    public RiskScenarioMetadataController(RiskScenarioMetadataService service,RiskScenarioParser parser,SessionSensitiveRequests requests,RiskScenarioHttpTransport transport){this.service=Objects.requireNonNull(service);this.parser=Objects.requireNonNull(parser);this.requests=Objects.requireNonNull(requests);this.transport=Objects.requireNonNull(transport);}
    @GetMapping("/api/v1/entities/risk_scenario/schema")
    public void schema(HttpServletRequest request,HttpServletResponse response)throws IOException{
        var ids=IdentityRequestIds.get(request);parser.noParameters(request);var capture=requests.capture(request);RiskScenarioMetadataService.MetadataOutcome result;
        try{result=service.schema(capture,ids.requestId(),ids.traceId());}finally{capture.close();}RiskScenarioHttpResponses.metadata(request,response,transport,result);
    }
    @GetMapping("/api/v1/entities/risk_scenario/definition")
    public void definition(HttpServletRequest request,HttpServletResponse response)throws IOException{
        var ids=IdentityRequestIds.get(request);var use=parser.definitionUse(request);var capture=requests.capture(request);RiskScenarioMetadataService.MetadataOutcome result;
        try{result=service.definition(capture,use,ids.requestId(),ids.traceId());}finally{capture.close();}RiskScenarioHttpResponses.metadata(request,response,transport,result);
    }
    @org.springframework.web.bind.annotation.ExceptionHandler({RiskScenarioContracts.Failure.class,com.mightyoung.softwarefactory.identity.SessionSensitiveException.class,
            com.mightyoung.softwarefactory.identity.UnauthenticatedSessionException.class,org.springframework.security.access.AccessDeniedException.class,
            org.springframework.dao.DataAccessException.class,org.jooq.exception.DataAccessException.class,org.springframework.transaction.TransactionException.class})
    void failure(Exception error,HttpServletRequest request,HttpServletResponse response)throws IOException{RiskScenarioHttpResponses.error(request,response,transport,error);}
}
