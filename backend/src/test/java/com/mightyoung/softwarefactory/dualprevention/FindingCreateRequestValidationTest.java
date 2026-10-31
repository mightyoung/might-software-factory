package com.mightyoung.softwarefactory.dualprevention;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FindingCreateRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void reporterCanSubmitOnlyUnknownGrade() {
        var allowed = new FindingWorkflowApiModels.CreateRequest(
                UUID.randomUUID(), "Synthetic description", OffsetDateTime.parse("2026-10-31T10:00:00Z"),
                null, null, "UNKNOWN");
        var general = new FindingWorkflowApiModels.CreateRequest(
                UUID.randomUUID(), "Synthetic description", OffsetDateTime.parse("2026-10-31T10:00:00Z"),
                null, null, "GENERAL");
        var major = new FindingWorkflowApiModels.CreateRequest(
                UUID.randomUUID(), "Synthetic description", OffsetDateTime.parse("2026-10-31T10:00:00Z"),
                null, null, "MAJOR");

        assertThat(validator.validate(allowed)).isEmpty();
        assertThat(validator.validate(general)).isNotEmpty();
        assertThat(validator.validate(major)).isNotEmpty();
    }
}
