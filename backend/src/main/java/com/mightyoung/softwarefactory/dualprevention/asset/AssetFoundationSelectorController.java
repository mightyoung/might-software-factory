package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.Metadata;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.ErrorResponse;
import com.mightyoung.softwarefactory.identity.SessionSensitiveException;
import com.mightyoung.softwarefactory.identity.SessionSensitiveRequests;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Seventeen literal selectors; transport never supplies a resource, permission or table. */
@RestController
public final class AssetFoundationSelectorController {
    public record Response<T>(T data, Metadata meta) { }
    private final AssetFoundationSelectorService service;
    private final AssetFoundationSelectorParser parser;
    private final SessionSensitiveRequests requests;
    public AssetFoundationSelectorController(AssetFoundationSelectorService service, AssetFoundationSelectorParser parser,
            SessionSensitiveRequests requests) { this.service = service; this.parser = parser; this.requests = requests; }

    @PostMapping("/api/v1/entities/workshop/references/create-organization/query")
    public ResponseEntity<Response<AssetFoundationSelectorContracts.Page>> workshopOrganizations(HttpServletRequest request) {
        var query = parser.reference(request);
        return page(request, AssetFoundationSelectorContracts.Selector.WORKSHOP_ORGANIZATION, null, query);
    }

    @PostMapping("/api/v1/entities/production_line/references/create-organization/query")
    public ResponseEntity<Response<AssetFoundationSelectorContracts.Page>> lineOrganizations(HttpServletRequest request) {
        var query = parser.reference(request);
        return page(request, AssetFoundationSelectorContracts.Selector.LINE_ORGANIZATION, null, query);
    }

    @PostMapping("/api/v1/entities/process_stage/references/create-organization/query")
    public ResponseEntity<Response<AssetFoundationSelectorContracts.Page>> stageOrganizations(HttpServletRequest request) {
        var query = parser.reference(request);
        return page(request, AssetFoundationSelectorContracts.Selector.STAGE_ORGANIZATION, null, query);
    }

    @PostMapping("/api/v1/entities/equipment/references/create-organization/query")
    public ResponseEntity<Response<AssetFoundationSelectorContracts.Page>> equipmentOrganizations(HttpServletRequest request) {
        var query = parser.reference(request);
        return page(request, AssetFoundationSelectorContracts.Selector.EQUIPMENT_ORGANIZATION, null, query);
    }

    @PostMapping("/api/v1/entities/work_unit/references/create-organization/query")
    public ResponseEntity<Response<AssetFoundationSelectorContracts.Page>> workUnitOrganizations(HttpServletRequest request) {
        var query = parser.reference(request);
        return page(request, AssetFoundationSelectorContracts.Selector.WORK_UNIT_ORGANIZATION, null, query);
    }

    @PostMapping("/api/v1/entities/hazard_source/references/create-organization/query")
    public ResponseEntity<Response<AssetFoundationSelectorContracts.Page>> hazardOrganizations(HttpServletRequest request) {
        var query = parser.reference(request);
        return page(request, AssetFoundationSelectorContracts.Selector.HAZARD_ORGANIZATION, null, query);
    }

    @PostMapping("/api/v1/entities/production_line/references/parent-workshop/query")
    public ResponseEntity<Response<AssetFoundationSelectorContracts.Page>> lineParentWorkshop(HttpServletRequest request) {
        var query = parser.anchor(request);
        return page(request, AssetFoundationSelectorContracts.Selector.LINE_WORKSHOP, null, query);
    }

    @PostMapping("/api/v1/entities/process_stage/references/parent-line/query")
    public ResponseEntity<Response<AssetFoundationSelectorContracts.Page>> stageParentLine(HttpServletRequest request) {
        var query = parser.anchor(request);
        return page(request, AssetFoundationSelectorContracts.Selector.STAGE_LINE, null, query);
    }

    @PostMapping("/api/v1/entities/hazard_source/references/work-unit/query")
    public ResponseEntity<Response<AssetFoundationSelectorContracts.Page>> hazardWorkUnit(HttpServletRequest request) {
        var query = parser.anchor(request);
        return page(request, AssetFoundationSelectorContracts.Selector.HAZARD_WORK_UNIT, null, query);
    }

    @PostMapping("/api/v1/entities/work_unit/references/source-workshop/query")
    public ResponseEntity<Response<AssetFoundationSelectorContracts.Page>> workUnitWorkshop(HttpServletRequest request) {
        var query = parser.anchor(request);
        return page(request, AssetFoundationSelectorContracts.Selector.WORK_UNIT_WORKSHOP, null, query);
    }

    @PostMapping("/api/v1/entities/work_unit/references/source-line/query")
    public ResponseEntity<Response<AssetFoundationSelectorContracts.Page>> workUnitLine(HttpServletRequest request) {
        var query = parser.anchor(request);
        return page(request, AssetFoundationSelectorContracts.Selector.WORK_UNIT_LINE, null, query);
    }

    @PostMapping("/api/v1/entities/work_unit/references/source-stage/query")
    public ResponseEntity<Response<AssetFoundationSelectorContracts.Page>> workUnitStage(HttpServletRequest request) {
        var query = parser.anchor(request);
        return page(request, AssetFoundationSelectorContracts.Selector.WORK_UNIT_STAGE, null, query);
    }

    @PostMapping("/api/v1/entities/work_unit/references/source-equipment/query")
    public ResponseEntity<Response<AssetFoundationSelectorContracts.Page>> workUnitEquipment(HttpServletRequest request) {
        var query = parser.anchor(request);
        return page(request, AssetFoundationSelectorContracts.Selector.WORK_UNIT_EQUIPMENT, null, query);
    }

    @PostMapping("/api/v1/assets/production-lines/{lineId}/workshop-bindings/references/workshop/query")
    public ResponseEntity<Response<AssetFoundationSelectorContracts.Page>> bindingWorkshop(@PathVariable String lineId, HttpServletRequest request) {
        var query = parser.reference(request);
        return page(request, AssetFoundationSelectorContracts.Selector.BIND_WORKSHOP, AssetFoundationRequestParser.uuid(lineId), query);
    }

    @PostMapping("/api/v1/assets/process-stages/{stageId}/equipment-bindings/references/equipment/query")
    public ResponseEntity<Response<AssetFoundationSelectorContracts.Page>> bindingEquipment(@PathVariable String stageId, HttpServletRequest request) {
        var query = parser.reference(request);
        return page(request, AssetFoundationSelectorContracts.Selector.BIND_EQUIPMENT, AssetFoundationRequestParser.uuid(stageId), query);
    }

    @PostMapping("/api/v1/assets/work-units/{workUnitId}/responsibility-bindings/references/organization/query")
    public ResponseEntity<Response<AssetFoundationSelectorContracts.Page>> bindingOrganization(@PathVariable String workUnitId, HttpServletRequest request) {
        var query = parser.reference(request);
        return page(request, AssetFoundationSelectorContracts.Selector.BIND_ORGANIZATION, AssetFoundationRequestParser.uuid(workUnitId), query);
    }

    @PostMapping("/api/v1/assets/work-units/{workUnitId}/responsibility-bindings/references/position/query")
    public ResponseEntity<Response<AssetFoundationSelectorContracts.Page>> bindingPosition(@PathVariable String workUnitId, HttpServletRequest request) {
        var query = parser.position(request);
        return page(request, AssetFoundationSelectorContracts.Selector.BIND_POSITION, AssetFoundationRequestParser.uuid(workUnitId), query);
    }

    private ResponseEntity<Response<AssetFoundationSelectorContracts.Page>> page(HttpServletRequest request,
            AssetFoundationSelectorContracts.Selector selector, UUID parent, AssetFoundationSelectorContracts.Request query) {
        var captured = requests.capture(request);
        try { return response(200, AssetFoundationHttpResponses.trace(request), service.query(captured, selector, parent, query)); }
        finally { captured.close(); }
    }
    private static <T> ResponseEntity<Response<T>> response(int status, UUID trace, T value) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new Response<>(
                value, AssetFoundationHttpResponses.metadata(trace)));
    }
    @ExceptionHandler(AssetFoundationFailure.class)
    ResponseEntity<ErrorResponse> failure(AssetFoundationFailure error, HttpServletRequest request) { return AssetFoundationHttpResponses.error(error.status(), AssetFoundationHttpResponses.trace(request)); }
    @ExceptionHandler(SessionSensitiveException.class)
    ResponseEntity<ErrorResponse> sessionFailure(SessionSensitiveException error, HttpServletRequest request) {
        return AssetFoundationHttpResponses.error(switch (error.reason()) { case UNAVAILABLE -> 503; case CONFLICT -> 409; case REJECTED, NO_TRUSTED_LINEAGE -> 403; }, AssetFoundationHttpResponses.trace(request));
    }
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ResponseEntity<ErrorResponse> denied(HttpServletRequest request) { return AssetFoundationHttpResponses.error(403, AssetFoundationHttpResponses.trace(request)); }
    @ExceptionHandler(com.mightyoung.softwarefactory.identity.UnauthenticatedSessionException.class)
    ResponseEntity<ErrorResponse> unauthenticated(HttpServletRequest request) { return AssetFoundationHttpResponses.error(401, AssetFoundationHttpResponses.trace(request)); }
    @ExceptionHandler({org.jooq.exception.DataAccessException.class, org.springframework.dao.DataAccessException.class})
    ResponseEntity<ErrorResponse> unavailable(HttpServletRequest request) { return AssetFoundationHttpResponses.error(503, AssetFoundationHttpResponses.trace(request)); }
}

