package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.Direction;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.Metadata;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.Request;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.SortField;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryValidator;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryValidator.ValidatedQuery;
import com.mightyoung.softwarefactory.dualprevention.FindingApiModels.DetailResponse;
import com.mightyoung.softwarefactory.dualprevention.FindingApiModels.ListData;
import com.mightyoung.softwarefactory.dualprevention.FindingApiModels.ListResponse;
import com.mightyoung.softwarefactory.dualprevention.FindingApiModels.PageMetadata;
import com.mightyoung.softwarefactory.dualprevention.FindingApiModels.SchemaResponse;
import com.mightyoung.softwarefactory.dualprevention.finding.FindingReadScope;
import com.mightyoung.softwarefactory.dualprevention.finding.FindingRecord;
import com.mightyoung.softwarefactory.dualprevention.finding.FindingRepository;
import com.mightyoung.softwarefactory.dualprevention.finding.FindingRepository.PageResult;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.identity.SessionContextResolver;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.ValidationException;

@Service
public final class FindingReadService {

    private final SessionContextResolver sessionContextResolver;
    private final FindingReadAuthorizer authorizer;
    private final FindingEntitySchema entitySchema;
    private final EntityQueryValidator validator;
    private final FindingRepository repository;

    public FindingReadService(
            SessionContextResolver sessionContextResolver,
            FindingReadAuthorizer authorizer,
            FindingEntitySchema entitySchema,
            EntityQueryValidator validator,
            FindingRepository repository) {
        this.sessionContextResolver = sessionContextResolver;
        this.authorizer = authorizer;
        this.entitySchema = entitySchema;
        this.validator = validator;
        this.repository = repository;
    }

    public SchemaResponse schema() {
        authorizer.requireReadScope(sessionContextResolver.currentTenant());
        return new SchemaResponse(entitySchema.schema(), metadata());
    }

    public ListResponse query(String jsonBody) {
        CurrentTenantContext currentTenant = sessionContextResolver.currentTenant();
        FindingReadScope scope = authorizer.requireReadScope(currentTenant);
        Request request = parseRequest(jsonBody);
        Request effectiveRequest = applyFindingPaginationAndDefaults(request);
        ValidatedQuery query = validator.validate(effectiveRequest, entitySchema.schema());
        validateIntegerFilters(query);

        PageResult result = repository.findPage(scope, query);
        List<Map<String, Object>> items = result.items().stream()
                .map(record -> project(record, query.fields()))
                .toList();
        int pageNumber = effectiveRequest.page().number();
        long pageCount = result.total() == 0 ? 0 : 1 + (result.total() - 1) / query.size();
        return new ListResponse(
                new ListData(items, new PageMetadata(pageNumber, query.size(), result.total(), pageCount)),
                metadata());
    }

    public DetailResponse detail(UUID findingId) {
        FindingReadScope scope = authorizer.requireReadScope(sessionContextResolver.currentTenant());
        FindingRecord record = repository.findVisible(scope, findingId)
                .orElseThrow(FindingNotVisibleException::new);
        return new DetailResponse(record.toApiMap(), metadata());
    }

    private Request parseRequest(String jsonBody) {
        try {
            return EntityQueryContract.readRequest(jsonBody);
        } catch (IllegalArgumentException exception) {
            throw new ValidationException("request", "invalid_json", "The query body is invalid");
        }
    }

    private static Request applyFindingPaginationAndDefaults(Request request) {
        if (request == null || request.page() == null || request.page().number() == null
                || request.page().cursor() != null) {
            throw new ValidationException("page", "invalid_page_mode",
                    "Finding list queries require a one-based page number");
        }

        List<SortField> effectiveSort = request.sort();
        if (effectiveSort == null || effectiveSort.isEmpty()) {
            effectiveSort = List.of(new SortField("discoveredAt", Direction.DESC));
        }

        List<String> effectiveFields = request.fields();
        if (effectiveFields != null && !effectiveFields.isEmpty() && !effectiveFields.contains("id")) {
            List<String> withStableId = new ArrayList<>(effectiveFields.size() + 1);
            withStableId.add("id");
            withStableId.addAll(effectiveFields);
            effectiveFields = List.copyOf(withStableId);
        }

        return new Request(request.filterAST(), effectiveSort, request.page(), request.size(), effectiveFields);
    }

    private static Map<String, Object> project(FindingRecord record, List<String> fields) {
        Map<String, Object> allFields = record.toApiMap();
        Map<String, Object> selected = new LinkedHashMap<>();
        for (String field : fields) {
            selected.put(field, allFields.get(field));
        }
        return selected;
    }

    private static void validateIntegerFilters(ValidatedQuery query) {
        for (var filter : query.filters()) {
            if (!filter.field().equals("roundNo") && !filter.field().equals("version")) {
                continue;
            }
            if (filter.value() == null || filter.value().isNull()) {
                continue;
            }
            if (filter.value().isArray()) {
                for (var value : filter.value()) {
                    requireInteger(filter.field(), value.decimalValue());
                }
            } else {
                requireInteger(filter.field(), filter.value().decimalValue());
            }
        }
    }

    private static void requireInteger(String field, java.math.BigDecimal value) {
        if (value.stripTrailingZeros().scale() > 0) {
            throw new ValidationException(field, "integer_required", "This field accepts only whole numbers");
        }
    }

    private static Metadata metadata() {
        return new Metadata(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "1");
    }
}
