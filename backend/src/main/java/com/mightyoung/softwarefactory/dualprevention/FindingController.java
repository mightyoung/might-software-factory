package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.dualprevention.FindingApiModels.DetailResponse;
import com.mightyoung.softwarefactory.dualprevention.FindingApiModels.ListResponse;
import com.mightyoung.softwarefactory.dualprevention.FindingApiModels.SchemaResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.ValidationException;

import java.util.UUID;

@RestController
public final class FindingController {

    private final FindingReadService service;

    public FindingController(FindingReadService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/entities/finding/schema")
    public SchemaResponse schema() {
        return service.schema();
    }

    @PostMapping("/api/v1/entities/finding/query")
    public ListResponse query(@RequestBody String jsonBody) {
        return service.query(jsonBody);
    }

    @GetMapping("/api/v1/entities/finding/{id}")
    public DetailResponse detail(@PathVariable String id) {
        UUID parsed;
        try {
            parsed = UUID.fromString(id);
            if (!parsed.toString().equalsIgnoreCase(id)) {
                throw new IllegalArgumentException("Non-canonical UUID");
            }
        } catch (IllegalArgumentException exception) {
            throw new ValidationException("id", "invalid_uuid", "The finding identifier is invalid");
        }
        return service.detail(parsed);
    }
}
