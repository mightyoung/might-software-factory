package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.applicationregistry.definition.EffectiveEntityDefinition;
import com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinition.Use;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.Metadata;
import com.mightyoung.softwarefactory.applicationregistry.reference.ReferenceQueryContract.Page;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.ServletWebRequest;

/** Dedicated Finding routes: no arbitrary entity, permission or command dispatch input. */
@RestController
public final class FindingDefinitionController {
    private final FindingDefinitionService service;
    private final FindingOrganizationReferenceRequestParser parser;
    public FindingDefinitionController(FindingDefinitionService service, FindingOrganizationReferenceRequestParser parser) {
        this.service = service; this.parser = parser;
    }

    @GetMapping("/api/v1/entities/finding/definition")
    public ResponseEntity<DefinitionResponse> definition(HttpServletRequest request, HttpServletResponse response) {
        var parameters = request.getParameterMap();
        String[] projections = parameters.get("use");
        if (!parameters.keySet().equals(Set.of("use")) || projections == null || projections.length != 1 || projections[0] == null)
            throw new IllegalArgumentException("Invalid definition projection");
        Use use = Use.valueOf(projections[0]);
        // Resolve current authority even for wildcard/list validators and warm browser caches.
        var result = service.definition(use);
        HttpHeaders headers = new HttpHeaders();
        headers.setETag(result.etag());
        headers.setCacheControl(CacheControl.noCache().cachePrivate());
        headers.setVary(List.of(HttpHeaders.COOKIE));
        headers.forEach((key, values) -> response.setHeader(key, String.join(", ", values)));
        if (new ServletWebRequest(request, response).checkNotModified(result.etag())) {
            return ResponseEntity.status(response.getStatus()).headers(headers).build();
        }
        // Spring 7's ServletWebRequest wildcard branch only covers unsafe methods.
        // Preserve its decisions first, then match a sole wildcard against the
        // existing representation whose current authority was resolved above.
        var validators = request.getHeaders(HttpHeaders.IF_NONE_MATCH);
        String validator = validators.hasMoreElements() ? validators.nextElement() : null;
        if (validator != null && "*".equals(validator.trim()) && !validators.hasMoreElements()) {
            return ResponseEntity.status(304).headers(headers).build();
        }
        return ResponseEntity.ok().headers(headers).body(new DefinitionResponse(result.data(), metadata()));
    }

    @PostMapping(FindingDefinitionProvider.REFERENCE_PATH)
    public ResponseEntity<ReferenceResponse> organizations(HttpServletRequest request) {
        var result = service.organizations(parser.parse(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ReferenceResponse(result, metadata()));
    }
    private static Metadata metadata() {
        return new Metadata(UUID.randomUUID().toString(), UUID.randomUUID().toString(), FindingDefinitionProvider.DEFINITION_VERSION);
    }
    public record DefinitionResponse(EffectiveEntityDefinition data, Metadata meta) { }
    public record ReferenceResponse(Page data, Metadata meta) { }
}
