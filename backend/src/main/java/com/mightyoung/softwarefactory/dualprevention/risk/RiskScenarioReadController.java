package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.dualprevention.RiskScenarioHttpTransport;
import com.mightyoung.softwarefactory.identity.IdentityRequestIds;
import com.mightyoung.softwarefactory.identity.SessionSensitiveRequests;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.web.bind.annotation.*;

/** Five literal read routes; real read graph registration and complete transport headers belong to the composition root. */
@RestController
public final class RiskScenarioReadController {
    private final RiskScenarioReadService reads;private final RiskScenarioParser parser;private final SessionSensitiveRequests requests;private final RiskScenarioHttpTransport transport;
    public RiskScenarioReadController(RiskScenarioReadService reads,RiskScenarioParser parser,SessionSensitiveRequests requests,RiskScenarioHttpTransport transport){this.reads=Objects.requireNonNull(reads);this.parser=Objects.requireNonNull(parser);this.requests=Objects.requireNonNull(requests);this.transport=Objects.requireNonNull(transport);}
    @GetMapping("/api/v1/entities/risk_scenario/{id}")
    public void detail(@PathVariable String id,HttpServletRequest request,HttpServletResponse response)throws IOException{
        var ids=IdentityRequestIds.get(request);parser.noParameters(request);var target=RiskScenarioParser.uuid(id);var capture=requests.capture(request);RiskScenarioReadService.EncodedResponse result;
        try{result=reads.detail(capture,target,ids.requestId(),ids.traceId());}finally{capture.close();}RiskScenarioHttpResponses.read(request,response,transport,result);
    }
    @PostMapping("/api/v1/entities/risk_scenario/query")
    public void query(HttpServletRequest request,HttpServletResponse response)throws IOException{
        var ids=IdentityRequestIds.get(request);var input=parser.query(request);var capture=requests.capture(request);RiskScenarioReadService.EncodedResponse result;
        try{result=reads.query(capture,input,ids.requestId(),ids.traceId());}finally{capture.close();}RiskScenarioHttpResponses.read(request,response,transport,result);
    }
    @GetMapping("/api/v1/risk-scenarios/{id}/history")
    public void history(@PathVariable String id,HttpServletRequest request,HttpServletResponse response)throws IOException{
        var ids=IdentityRequestIds.get(request);var target=RiskScenarioParser.uuid(id);var input=parser.history(request);var capture=requests.capture(request);RiskScenarioReadService.EncodedResponse result;
        try{result=reads.history(capture,target,input,ids.requestId(),ids.traceId());}finally{capture.close();}RiskScenarioHttpResponses.read(request,response,transport,result);
    }
    @GetMapping("/api/v1/risk-scenarios/{id}/history/{version}/hazards/{hazardId}")
    public void historicalSnapshot(@PathVariable String id,@PathVariable String version,@PathVariable String hazardId,HttpServletRequest request,HttpServletResponse response)throws IOException{
        var ids=IdentityRequestIds.get(request);parser.noParameters(request);var parent=RiskScenarioParser.uuid(id);var revision=RiskScenarioParser.pathVersion(version);var hazard=RiskScenarioParser.uuid(hazardId);var capture=requests.capture(request);RiskScenarioReadService.EncodedResponse result;
        try{result=reads.historicalSnapshot(capture,parent,revision,hazard,ids.requestId(),ids.traceId());}finally{capture.close();}RiskScenarioHttpResponses.read(request,response,transport,result);
    }
    @PostMapping("/api/v1/risk-scenarios/eligible-hazards")
    public void eligibleHazards(HttpServletRequest request,HttpServletResponse response)throws IOException{
        var ids=IdentityRequestIds.get(request);var input=parser.eligibleHazards(request);var capture=requests.capture(request);RiskScenarioReadService.EncodedResponse result;
        try{result=reads.eligibleHazards(capture,input,ids.requestId(),ids.traceId());}finally{capture.close();}RiskScenarioHttpResponses.read(request,response,transport,result);
    }
    @org.springframework.web.bind.annotation.ExceptionHandler({RiskScenarioContracts.Failure.class,com.mightyoung.softwarefactory.identity.SessionSensitiveException.class,
            com.mightyoung.softwarefactory.identity.UnauthenticatedSessionException.class,org.springframework.security.access.AccessDeniedException.class,
            org.springframework.dao.DataAccessException.class,org.jooq.exception.DataAccessException.class,org.springframework.transaction.TransactionException.class})
    void failure(Exception error,HttpServletRequest request,HttpServletResponse response)throws IOException{RiskScenarioHttpResponses.error(request,response,transport,error);}
}
