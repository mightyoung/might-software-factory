package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationEntitySchemas.Root;
import com.mightyoung.softwarefactory.identity.SessionSensitiveException;
import com.mightyoung.softwarefactory.identity.SessionSensitiveRequests;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/** Six literal resource mappings; no resource, table or permission is supplied by the client. */
@RestController
public final class AssetFoundationQueryController {
    private final AssetFoundationQueryService service;
    private final AssetFoundationQueryParser parser;
    private final SessionSensitiveRequests requests;
    public AssetFoundationQueryController(AssetFoundationQueryService service,ObjectMapper json,SessionSensitiveRequests requests){
        this.service=service;this.parser=new AssetFoundationQueryParser(json);this.requests=requests;
    }
    @PostMapping("/api/v1/entities/workshop/query")
    public ResponseEntity<?> workshops(HttpServletRequest request){return query(request,Root.WORKSHOP);}
    @PostMapping("/api/v1/entities/production_line/query")
    public ResponseEntity<?> productionLines(HttpServletRequest request){return query(request,Root.PRODUCTION_LINE);}
    @PostMapping("/api/v1/entities/process_stage/query")
    public ResponseEntity<?> processStages(HttpServletRequest request){return query(request,Root.PROCESS_STAGE);}
    @PostMapping("/api/v1/entities/equipment/query")
    public ResponseEntity<?> equipment(HttpServletRequest request){return query(request,Root.EQUIPMENT);}
    @PostMapping("/api/v1/entities/work_unit/query")
    public ResponseEntity<?> workUnits(HttpServletRequest request){return query(request,Root.WORK_UNIT);}
    @PostMapping("/api/v1/entities/hazard_source/query")
    public ResponseEntity<?> hazardSources(HttpServletRequest request){return query(request,Root.HAZARD_SOURCE);}
    private ResponseEntity<?> query(HttpServletRequest request,Root root){
        var input=parser.parse(request);var capture=requests.capture(request);
        try{return AssetFoundationReadResponses.success(service.query(capture,root,input));}finally{capture.close();}
    }
    @ExceptionHandler(AssetFoundationFailure.class)
    ResponseEntity<?> failure(AssetFoundationFailure failure){return AssetFoundationReadResponses.failure(failure.status());}
    @ExceptionHandler(SessionSensitiveException.class)
    ResponseEntity<?> sessionFailure(SessionSensitiveException failure){return AssetFoundationReadResponses.failure(switch(failure.reason()){case UNAVAILABLE->503;case CONFLICT->409;case REJECTED,NO_TRUSTED_LINEAGE->403;});}
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ResponseEntity<?> denied(){return AssetFoundationReadResponses.failure(403);}
}
