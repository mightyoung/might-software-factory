package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.Metadata;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.ErrorResponse;
import com.mightyoung.softwarefactory.identity.CapturedHumanRequest;
import com.mightyoung.softwarefactory.identity.SessionSensitiveRequests;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationRequestParser.uuid;

/** Literal routes and typed commands. Existing session/CSRF filters remain the transport boundary. */
@RestController
public final class AssetFoundationController {
    private final AssetFoundationService commands;
    private final AssetFoundationReadService reads;
    private final AssetFoundationRequestParser parser;
    private final SessionSensitiveRequests requests;
    public AssetFoundationController(AssetFoundationService commands,AssetFoundationReadService reads,AssetFoundationRequestParser parser,SessionSensitiveRequests requests) {
        this.commands=commands;this.reads=reads;this.parser=parser;this.requests=requests;
    }
    public record Response<T>(T data,Metadata meta) {}
    @PostMapping("/api/v1/assets/workshops")
    public ResponseEntity<Response<AssetContracts.CommandResult>> createWorkshop(HttpServletRequest request) {
        var body=parser.workshopCreate(request);var key=parser.idempotencyKey(request);var trace=AssetFoundationHttpResponses.trace(request);
        return response(201,trace,call(request,capture->commands.createWorkshop(capture,key,trace,body)));
    }
    @PostMapping("/api/v1/assets/workshops/{id}/update")
    public ResponseEntity<Response<AssetContracts.CommandResult>> updateWorkshop(@PathVariable String id,HttpServletRequest request) {
        var target=uuid(id);var body=parser.workshopUpdate(request);var key=parser.idempotencyKey(request);var trace=AssetFoundationHttpResponses.trace(request);
        return response(200,trace,call(request,capture->commands.updateWorkshop(capture,key,trace,target,body)));
    }
    @PostMapping("/api/v1/assets/workshops/{id}/archive")
    public ResponseEntity<Response<AssetContracts.CommandResult>> archiveWorkshop(@PathVariable String id,HttpServletRequest request) {
        var target=uuid(id);var body=parser.archive(request);var key=parser.idempotencyKey(request);var trace=AssetFoundationHttpResponses.trace(request);
        return response(200,trace,call(request,capture->commands.archiveWorkshop(capture,key,trace,target,body)));
    }
    @PostMapping("/api/v1/assets/production-lines")
    public ResponseEntity<Response<AssetContracts.CommandResult>> createProductionLine(HttpServletRequest request) {
        var body=parser.productionLineCreate(request);var key=parser.idempotencyKey(request);var trace=AssetFoundationHttpResponses.trace(request);
        return response(201,trace,call(request,capture->commands.createProductionLine(capture,key,trace,body)));
    }
    @PostMapping("/api/v1/assets/production-lines/{id}/update")
    public ResponseEntity<Response<AssetContracts.CommandResult>> updateProductionLine(@PathVariable String id,HttpServletRequest request) {
        var target=uuid(id);var body=parser.productionLineUpdate(request);var key=parser.idempotencyKey(request);var trace=AssetFoundationHttpResponses.trace(request);
        return response(200,trace,call(request,capture->commands.updateProductionLine(capture,key,trace,target,body)));
    }
    @PostMapping("/api/v1/assets/production-lines/{id}/archive")
    public ResponseEntity<Response<AssetContracts.CommandResult>> archiveProductionLine(@PathVariable String id,HttpServletRequest request) {
        var target=uuid(id);var body=parser.archive(request);var key=parser.idempotencyKey(request);var trace=AssetFoundationHttpResponses.trace(request);
        return response(200,trace,call(request,capture->commands.archiveProductionLine(capture,key,trace,target,body)));
    }
    @GetMapping("/api/v1/entities/production_line/{id}")
    public ResponseEntity<Response<AssetContracts.ProductionLine>> productionLine(@PathVariable String id,HttpServletRequest request) {
        noBody(request);var target=uuid(id);var trace=AssetFoundationHttpResponses.trace(request);
        return response(200,trace,call(request,capture->reads.productionLine(capture,target)));
    }
    @PostMapping("/api/v1/assets/process-stages")
    public ResponseEntity<Response<AssetContracts.CommandResult>> createProcessStage(HttpServletRequest request) {
        var body=parser.processStageCreate(request);var key=parser.idempotencyKey(request);var trace=AssetFoundationHttpResponses.trace(request);
        return response(201,trace,call(request,capture->commands.createProcessStage(capture,key,trace,body)));
    }
    @PostMapping("/api/v1/assets/process-stages/{id}/update")
    public ResponseEntity<Response<AssetContracts.CommandResult>> updateProcessStage(@PathVariable String id,HttpServletRequest request) {
        var target=uuid(id);var body=parser.processStageUpdate(request);var key=parser.idempotencyKey(request);var trace=AssetFoundationHttpResponses.trace(request);
        return response(200,trace,call(request,capture->commands.updateProcessStage(capture,key,trace,target,body)));
    }
    @PostMapping("/api/v1/assets/process-stages/{id}/archive")
    public ResponseEntity<Response<AssetContracts.CommandResult>> archiveProcessStage(@PathVariable String id,HttpServletRequest request) {
        var target=uuid(id);var body=parser.archive(request);var key=parser.idempotencyKey(request);var trace=AssetFoundationHttpResponses.trace(request);
        return response(200,trace,call(request,capture->commands.archiveProcessStage(capture,key,trace,target,body)));
    }
    @GetMapping("/api/v1/entities/process_stage/{id}")
    public ResponseEntity<Response<AssetContracts.ProcessStage>> processStage(@PathVariable String id,HttpServletRequest request) {
        noBody(request);var target=uuid(id);var trace=AssetFoundationHttpResponses.trace(request);
        return response(200,trace,call(request,capture->reads.processStage(capture,target)));
    }
    @PostMapping("/api/v1/assets/equipment")
    public ResponseEntity<Response<AssetContracts.CommandResult>> createEquipment(HttpServletRequest request) {
        var body=parser.equipmentCreate(request);var key=parser.idempotencyKey(request);var trace=AssetFoundationHttpResponses.trace(request);
        return response(201,trace,call(request,capture->commands.createEquipment(capture,key,trace,body)));
    }
    @PostMapping("/api/v1/assets/equipment/{id}/update")
    public ResponseEntity<Response<AssetContracts.CommandResult>> updateEquipment(@PathVariable String id,HttpServletRequest request) {
        var target=uuid(id);var body=parser.equipmentUpdate(request);var key=parser.idempotencyKey(request);var trace=AssetFoundationHttpResponses.trace(request);
        return response(200,trace,call(request,capture->commands.updateEquipment(capture,key,trace,target,body)));
    }
    @PostMapping("/api/v1/assets/equipment/{id}/archive")
    public ResponseEntity<Response<AssetContracts.CommandResult>> archiveEquipment(@PathVariable String id,HttpServletRequest request) {
        var target=uuid(id);var body=parser.archive(request);var key=parser.idempotencyKey(request);var trace=AssetFoundationHttpResponses.trace(request);
        return response(200,trace,call(request,capture->commands.archiveEquipment(capture,key,trace,target,body)));
    }
    @GetMapping("/api/v1/entities/equipment/{id}")
    public ResponseEntity<Response<AssetContracts.Equipment>> equipment(@PathVariable String id,HttpServletRequest request) {
        noBody(request);var target=uuid(id);var trace=AssetFoundationHttpResponses.trace(request);
        return response(200,trace,call(request,capture->reads.equipment(capture,target)));
    }
    @PostMapping("/api/v1/assets/work-units")
    public ResponseEntity<Response<AssetContracts.CommandResult>> createWorkUnit(HttpServletRequest request) {
        var body=parser.workUnitCreate(request);var key=parser.idempotencyKey(request);var trace=AssetFoundationHttpResponses.trace(request);
        return response(201,trace,call(request,capture->commands.createWorkUnit(capture,key,trace,body)));
    }
    @PostMapping("/api/v1/assets/work-units/{id}/update")
    public ResponseEntity<Response<AssetContracts.CommandResult>> updateWorkUnit(@PathVariable String id,HttpServletRequest request) {
        var target=uuid(id);var body=parser.workUnitUpdate(request);var key=parser.idempotencyKey(request);var trace=AssetFoundationHttpResponses.trace(request);
        return response(200,trace,call(request,capture->commands.updateWorkUnit(capture,key,trace,target,body)));
    }
    @PostMapping("/api/v1/assets/work-units/{id}/archive")
    public ResponseEntity<Response<AssetContracts.CommandResult>> archiveWorkUnit(@PathVariable String id,HttpServletRequest request) {
        var target=uuid(id);var body=parser.archive(request);var key=parser.idempotencyKey(request);var trace=AssetFoundationHttpResponses.trace(request);
        return response(200,trace,call(request,capture->commands.archiveWorkUnit(capture,key,trace,target,body)));
    }
    @PostMapping("/api/v1/hazard-sources")
    public ResponseEntity<Response<AssetContracts.CommandResult>> createHazard(HttpServletRequest request) {
        var body=parser.hazardCreate(request);var key=parser.idempotencyKey(request);var trace=AssetFoundationHttpResponses.trace(request);
        return response(201,trace,call(request,capture->commands.createHazard(capture,key,trace,body)));
    }
    @PostMapping("/api/v1/hazard-sources/{id}/update")
    public ResponseEntity<Response<AssetContracts.CommandResult>> updateHazard(@PathVariable String id,HttpServletRequest request) {
        var target=uuid(id);var body=parser.hazardUpdate(request);var key=parser.idempotencyKey(request);var trace=AssetFoundationHttpResponses.trace(request);
        return response(200,trace,call(request,capture->commands.updateHazard(capture,key,trace,target,body)));
    }
    @GetMapping("/api/v1/entities/workshop/{id}")
    public ResponseEntity<Response<AssetContracts.Workshop>> workshop(@PathVariable String id,HttpServletRequest request) {
        noBody(request);var target=uuid(id);return response(200,UUID.randomUUID(),call(request,capture->reads.workshop(capture,target)));
    }
    @GetMapping("/api/v1/entities/work_unit/{id}")
    public ResponseEntity<Response<AssetContracts.WorkUnit>> workUnit(@PathVariable String id,HttpServletRequest request) {
        noBody(request);var target=uuid(id);return response(200,UUID.randomUUID(),call(request,capture->reads.workUnit(capture,target)));
    }
    @GetMapping("/api/v1/entities/hazard_source/{id}")
    public ResponseEntity<Response<com.mightyoung.softwarefactory.dualprevention.hazard.HazardSourceContracts.Detail>> hazard(@PathVariable String id,HttpServletRequest request) {
        noBody(request);var target=uuid(id);return response(200,UUID.randomUUID(),call(request,capture->reads.hazard(capture,target)));
    }
    @PostMapping("/api/v1/assets/production-lines/{lineId}/workshop-bindings")
    public ResponseEntity<Response<AssetContracts.CommandResult>> bindLineWorkshop(@PathVariable String lineId,HttpServletRequest request) {
        var parent=uuid(lineId);var body=parser.lineWorkshopBind(request);var key=parser.idempotencyKey(request);var trace=AssetFoundationHttpResponses.trace(request);
        return response(201,trace,call(request,capture->commands.bindLineWorkshop(capture,key,trace,parent,body)));
    }
    @PostMapping("/api/v1/assets/production-lines/{lineId}/workshop-bindings/{bindingId}/end")
    public ResponseEntity<Response<AssetContracts.CommandResult>> endLineWorkshop(@PathVariable String lineId,@PathVariable String bindingId,HttpServletRequest request) {
        var parent=uuid(lineId);var id=uuid(bindingId);var body=parser.endBinding(request);var key=parser.idempotencyKey(request);var trace=AssetFoundationHttpResponses.trace(request);
        return response(200,trace,call(request,capture->commands.endLineWorkshop(capture,key,trace,parent,id,body)));
    }
    @PostMapping("/api/v1/assets/process-stages/{stageId}/equipment-bindings")
    public ResponseEntity<Response<AssetContracts.CommandResult>> bindStageEquipment(@PathVariable String stageId,HttpServletRequest request) {
        var parent=uuid(stageId);var body=parser.stageEquipmentBind(request);var key=parser.idempotencyKey(request);var trace=AssetFoundationHttpResponses.trace(request);
        return response(201,trace,call(request,capture->commands.bindStageEquipment(capture,key,trace,parent,body)));
    }
    @PostMapping("/api/v1/assets/process-stages/{stageId}/equipment-bindings/{bindingId}/end")
    public ResponseEntity<Response<AssetContracts.CommandResult>> endStageEquipment(@PathVariable String stageId,@PathVariable String bindingId,HttpServletRequest request) {
        var parent=uuid(stageId);var id=uuid(bindingId);var body=parser.endBinding(request);var key=parser.idempotencyKey(request);var trace=AssetFoundationHttpResponses.trace(request);
        return response(200,trace,call(request,capture->commands.endStageEquipment(capture,key,trace,parent,id,body)));
    }
    @PostMapping("/api/v1/assets/work-units/{workUnitId}/responsibility-bindings")
    public ResponseEntity<Response<AssetContracts.CommandResult>> bindResponsibility(@PathVariable String workUnitId,HttpServletRequest request) {
        var parent=uuid(workUnitId);var body=parser.responsibilityBind(request);var key=parser.idempotencyKey(request);var trace=AssetFoundationHttpResponses.trace(request);
        return response(201,trace,call(request,capture->commands.bindResponsibility(capture,key,trace,parent,body)));
    }
    @PostMapping("/api/v1/assets/work-units/{workUnitId}/responsibility-bindings/{bindingId}/end")
    public ResponseEntity<Response<AssetContracts.CommandResult>> endResponsibility(@PathVariable String workUnitId,@PathVariable String bindingId,HttpServletRequest request) {
        var parent=uuid(workUnitId);var id=uuid(bindingId);var body=parser.endBinding(request);var key=parser.idempotencyKey(request);var trace=AssetFoundationHttpResponses.trace(request);
        return response(200,trace,call(request,capture->commands.endResponsibility(capture,key,trace,parent,id,body)));
    }
    private <T> T call(HttpServletRequest request,Function<CapturedHumanRequest,T> work) {
        var capture=requests.capture(request);try{return work.apply(capture);}finally{capture.close();}
    }
    private static <T> ResponseEntity<Response<T>> response(int status,UUID trace,T data) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new Response<>(data,AssetFoundationHttpResponses.metadata(trace)));
    }
    private static void noBody(HttpServletRequest request) {
        if(request.getQueryString()!=null)throw new AssetFoundationFailure(400);
        try { if(request.getInputStream().read()!=-1)throw new AssetFoundationFailure(400); }
        catch(java.io.IOException e) { throw new AssetFoundationFailure(400); }
    }
    @ExceptionHandler(com.mightyoung.softwarefactory.identity.SessionSensitiveException.class)
    ResponseEntity<ErrorResponse> sessionSensitiveFailure(com.mightyoung.softwarefactory.identity.SessionSensitiveException error,HttpServletRequest request) {
        int status=switch(error.reason()) {
            case UNAVAILABLE -> 503;
            case CONFLICT -> 409;
            case REJECTED,NO_TRUSTED_LINEAGE -> 403;
        };
        return AssetFoundationHttpResponses.error(status,AssetFoundationHttpResponses.trace(request));
    }
    @ExceptionHandler(com.mightyoung.softwarefactory.support.G014JdbcTransactionManager.LifecycleException.class)
    ResponseEntity<ErrorResponse> commandLifecycleFailure(
            com.mightyoung.softwarefactory.support.G014JdbcTransactionManager.LifecycleException error,HttpServletRequest request) {
        return AssetFoundationHttpResponses.lifecycleFailure(error,AssetFoundationHttpResponses.trace(request));
    }
    @ExceptionHandler(AssetFoundationFailure.class)
    ResponseEntity<ErrorResponse> failure(AssetFoundationFailure error,HttpServletRequest request) { return AssetFoundationHttpResponses.error(error.status(),AssetFoundationHttpResponses.trace(request)); }
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ResponseEntity<ErrorResponse> denied(HttpServletRequest request) { return AssetFoundationHttpResponses.error(403,AssetFoundationHttpResponses.trace(request)); }
    @ExceptionHandler(com.mightyoung.softwarefactory.identity.UnauthenticatedSessionException.class)
    ResponseEntity<ErrorResponse> unauthenticated(HttpServletRequest request) { return AssetFoundationHttpResponses.error(401,AssetFoundationHttpResponses.trace(request)); }
}
