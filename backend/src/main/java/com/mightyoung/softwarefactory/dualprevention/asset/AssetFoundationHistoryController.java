package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.identity.SessionSensitiveException;
import com.mightyoung.softwarefactory.identity.SessionSensitiveRequests;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import static com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationHistoryContracts.*;

@RestController
public final class AssetFoundationHistoryController {
    private final AssetFoundationHistoryService service;
    private final AssetFoundationHistoryParser parser;
    private final SessionSensitiveRequests requests;
    public AssetFoundationHistoryController(AssetFoundationHistoryService service,ObjectMapper json,SessionSensitiveRequests requests){this.service=service;this.parser=new AssetFoundationHistoryParser(json);this.requests=requests;}
    @PostMapping("/api/v1/assets/production-lines/{lineId}/workshop-bindings/query")
    public ResponseEntity<?> lineWorkshops(@PathVariable String lineId,HttpServletRequest request){return history(request,Kind.LINE_WORKSHOP,lineId);}
    @PostMapping("/api/v1/assets/process-stages/{stageId}/equipment-bindings/query")
    public ResponseEntity<?> stageEquipment(@PathVariable String stageId,HttpServletRequest request){return history(request,Kind.STAGE_EQUIPMENT,stageId);}
    @PostMapping("/api/v1/assets/work-units/{workUnitId}/responsibility-bindings/query")
    public ResponseEntity<?> responsibilities(@PathVariable String workUnitId,HttpServletRequest request){return history(request,Kind.RESPONSIBILITY_BINDING,workUnitId);}
    private ResponseEntity<?> history(HttpServletRequest request,Kind kind,String parent){
        UUID id=AssetFoundationReadJson.uuid(parent);var body=parser.parse(request);var capture=requests.capture(request);
        try{return AssetFoundationReadResponses.success(service.page(capture,kind,id,body));}finally{capture.close();}
    }
    @ExceptionHandler(AssetFoundationFailure.class)
    ResponseEntity<?> failure(AssetFoundationFailure failure){return AssetFoundationReadResponses.failure(failure.status());}
    @ExceptionHandler(SessionSensitiveException.class)
    ResponseEntity<?> sessionFailure(SessionSensitiveException failure){return AssetFoundationReadResponses.failure(switch(failure.reason()){case UNAVAILABLE->503;case CONFLICT->409;case REJECTED,NO_TRUSTED_LINEAGE->403;});}
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ResponseEntity<?> denied(){return AssetFoundationReadResponses.failure(403);}
}
