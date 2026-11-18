package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.applicationregistry.definition.EffectiveEntityDefinition;
import com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinition.Use;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.EntitySchema;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.ErrorResponse;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.Metadata;
import com.mightyoung.softwarefactory.identity.SessionSensitiveException;
import com.mightyoung.softwarefactory.identity.SessionSensitiveRequests;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationEntitySchemas.Root;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.ServletWebRequest;

/** Twelve literal metadata mappings, with current authority resolved before conditional cache handling. */
@RestController
public final class AssetFoundationDefinitionController {
    public record Response<T>(T data, Metadata meta) { }
    private final AssetFoundationDefinitionService service;
    private final SessionSensitiveRequests requests;
    public AssetFoundationDefinitionController(AssetFoundationDefinitionService service, SessionSensitiveRequests requests) {
        this.service = service; this.requests = requests;
    }

    @GetMapping("/api/v1/entities/workshop/schema")
    public ResponseEntity<Response<EntitySchema>> workshopSchema(HttpServletRequest request) {
        noBody(request);
        if (request.getQueryString() != null) throw new AssetFoundationFailure(400);
        var captured = requests.capture(request);
        try { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Response<>(service.schema(captured, Root.WORKSHOP), metadata(request))); }
        finally { captured.close(); }
    }
    @GetMapping("/api/v1/entities/workshop/definition")
    public ResponseEntity<Response<EffectiveEntityDefinition>> workshopDefinition(HttpServletRequest request, HttpServletResponse response) {
        return definition(request, response, Root.WORKSHOP);
    }

    @GetMapping("/api/v1/entities/production_line/schema")
    public ResponseEntity<Response<EntitySchema>> production_lineSchema(HttpServletRequest request) {
        noBody(request);
        if (request.getQueryString() != null) throw new AssetFoundationFailure(400);
        var captured = requests.capture(request);
        try { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Response<>(service.schema(captured, Root.PRODUCTION_LINE), metadata(request))); }
        finally { captured.close(); }
    }
    @GetMapping("/api/v1/entities/production_line/definition")
    public ResponseEntity<Response<EffectiveEntityDefinition>> production_lineDefinition(HttpServletRequest request, HttpServletResponse response) {
        return definition(request, response, Root.PRODUCTION_LINE);
    }

    @GetMapping("/api/v1/entities/process_stage/schema")
    public ResponseEntity<Response<EntitySchema>> process_stageSchema(HttpServletRequest request) {
        noBody(request);
        if (request.getQueryString() != null) throw new AssetFoundationFailure(400);
        var captured = requests.capture(request);
        try { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Response<>(service.schema(captured, Root.PROCESS_STAGE), metadata(request))); }
        finally { captured.close(); }
    }
    @GetMapping("/api/v1/entities/process_stage/definition")
    public ResponseEntity<Response<EffectiveEntityDefinition>> process_stageDefinition(HttpServletRequest request, HttpServletResponse response) {
        return definition(request, response, Root.PROCESS_STAGE);
    }

    @GetMapping("/api/v1/entities/equipment/schema")
    public ResponseEntity<Response<EntitySchema>> equipmentSchema(HttpServletRequest request) {
        noBody(request);
        if (request.getQueryString() != null) throw new AssetFoundationFailure(400);
        var captured = requests.capture(request);
        try { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Response<>(service.schema(captured, Root.EQUIPMENT), metadata(request))); }
        finally { captured.close(); }
    }
    @GetMapping("/api/v1/entities/equipment/definition")
    public ResponseEntity<Response<EffectiveEntityDefinition>> equipmentDefinition(HttpServletRequest request, HttpServletResponse response) {
        return definition(request, response, Root.EQUIPMENT);
    }

    @GetMapping("/api/v1/entities/work_unit/schema")
    public ResponseEntity<Response<EntitySchema>> work_unitSchema(HttpServletRequest request) {
        noBody(request);
        if (request.getQueryString() != null) throw new AssetFoundationFailure(400);
        var captured = requests.capture(request);
        try { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Response<>(service.schema(captured, Root.WORK_UNIT), metadata(request))); }
        finally { captured.close(); }
    }
    @GetMapping("/api/v1/entities/work_unit/definition")
    public ResponseEntity<Response<EffectiveEntityDefinition>> work_unitDefinition(HttpServletRequest request, HttpServletResponse response) {
        return definition(request, response, Root.WORK_UNIT);
    }

    @GetMapping("/api/v1/entities/hazard_source/schema")
    public ResponseEntity<Response<EntitySchema>> hazard_sourceSchema(HttpServletRequest request) {
        noBody(request);
        if (request.getQueryString() != null) throw new AssetFoundationFailure(400);
        var captured = requests.capture(request);
        try { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Response<>(service.schema(captured, Root.HAZARD_SOURCE), metadata(request))); }
        finally { captured.close(); }
    }
    @GetMapping("/api/v1/entities/hazard_source/definition")
    public ResponseEntity<Response<EffectiveEntityDefinition>> hazard_sourceDefinition(HttpServletRequest request, HttpServletResponse response) {
        return definition(request, response, Root.HAZARD_SOURCE);
    }

    private ResponseEntity<Response<EffectiveEntityDefinition>> definition(HttpServletRequest request, HttpServletResponse response, Root root) {
        noBody(request);
        var params = request.getParameterMap();
        String[] values = params.get("use");
        if (!params.keySet().equals(Set.of("use")) || values == null || values.length != 1 || values[0] == null
                || !Set.of("READ", "CREATE").contains(values[0])) throw new AssetFoundationFailure(400);
        var captured = requests.capture(request);
        AssetFoundationDefinitionService.DefinitionResult result;
        try { result = service.definition(captured, root, Use.valueOf(values[0])); }
        finally { captured.close(); }
        HttpHeaders headers = new HttpHeaders();
        headers.setETag(result.etag()); headers.setCacheControl(CacheControl.noCache().cachePrivate());
        headers.setVary(List.of(HttpHeaders.COOKIE));
        headers.forEach((key, entries) -> response.setHeader(key, String.join(", ", entries)));
        if (new ServletWebRequest(request, response).checkNotModified(result.etag()))
            return ResponseEntity.status(response.getStatus()).headers(headers).build();
        var validators = request.getHeaders(HttpHeaders.IF_NONE_MATCH);
        String validator = validators.hasMoreElements() ? validators.nextElement() : null;
        if (validator != null && "*".equals(validator.trim()) && !validators.hasMoreElements())
            return ResponseEntity.status(304).headers(headers).build();
        return ResponseEntity.ok().headers(headers).body(new Response<>(result.data(), metadata(request)));
    }
    private static Metadata metadata(HttpServletRequest request) { return AssetFoundationHttpResponses.metadata(AssetFoundationHttpResponses.trace(request)); }
    private static void noBody(HttpServletRequest request) {
        try { if (request.getInputStream().read() != -1) throw new AssetFoundationFailure(400); }
        catch (java.io.IOException error) { throw new AssetFoundationFailure(400); }
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

