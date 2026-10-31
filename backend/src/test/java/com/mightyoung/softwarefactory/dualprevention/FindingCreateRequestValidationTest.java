package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingSourceCategory;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingUrgency;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class FindingCreateRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void requiresApprovedSourceUrgencyAndLocationWithoutTreatingUrgencyAsGrade() {
        var allowed = request("FIELD_OBSERVATION", "NORMAL", "Dock 4", null, "UNKNOWN");
        var inspection = request("INSPECTION", "URGENT", "Line A", "Close the work area", "UNKNOWN");
        var report = request("PERSON_REPORT", "NORMAL", "Warehouse", null, "UNKNOWN");
        var invalidSource = request("INTERNAL", "NORMAL", "Dock 4", null, "UNKNOWN");
        var invalidUrgency = request("FIELD_OBSERVATION", "CRITICAL", "Dock 4", null, "UNKNOWN");
        var missingSource = request(null, "NORMAL", "Dock 4", null, "UNKNOWN");
        var missingUrgency = request("FIELD_OBSERVATION", null, "Dock 4", null, "UNKNOWN");
        var missingLocation = request("FIELD_OBSERVATION", "NORMAL", null, null, "UNKNOWN");
        var blankLocation = request("FIELD_OBSERVATION", "NORMAL", " \t", null, "UNKNOWN");

        assertThat(validator.validate(allowed)).isEmpty();
        assertThat(validator.validate(inspection)).isEmpty();
        assertThat(validator.validate(report)).isEmpty();
        assertThat(properties(validator.validate(invalidSource))).contains("sourceCategory");
        assertThat(properties(validator.validate(invalidUrgency))).contains("urgency");
        assertThat(properties(validator.validate(missingSource))).contains("sourceCategory");
        assertThat(properties(validator.validate(missingUrgency))).contains("urgency");
        assertThat(properties(validator.validate(missingLocation))).contains("location");
        assertThat(properties(validator.validate(blankLocation))).contains("location");

        assertThat(validator.validate(request("FIELD_OBSERVATION", "URGENT", "Dock 4", null, "GENERAL")))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("grade");
    }

    @Test
    void appliesLocationAndInterimMeasureLimitsByUnicodeCodePoint() {
        assertThat(validator.validate(request("FIELD_OBSERVATION", "NORMAL", "场".repeat(200),
                "措".repeat(2000), "UNKNOWN"))).isEmpty();
        assertThat(validator.validate(request("FIELD_OBSERVATION", "NORMAL", "场".repeat(201),
                null, "UNKNOWN"))).extracting(violation -> violation.getPropertyPath().toString())
                .contains("location");
        assertThat(validator.validate(request("FIELD_OBSERVATION", "NORMAL", "😀".repeat(200),
                "😀".repeat(2000), "UNKNOWN"))).isEmpty();
        assertThat(validator.validate(request("FIELD_OBSERVATION", "NORMAL", "Dock 4",
                "😀".repeat(2001), "UNKNOWN"))).extracting(violation -> violation.getPropertyPath().toString())
                .contains("interimMeasure");
    }

    @Test
    void locationUsesTheExplicitUnicodeWhiteSpaceSet() {
        for (String whitespaceOnly : new String[] {" ", "\t", "\u00A0", " \t\u00A0\n"}) {
            assertThat(properties(validator.validate(request(
                    "FIELD_OBSERVATION", "NORMAL", whitespaceOnly, null, "UNKNOWN"))))
                    .as("whitespace-only location %s", whitespaceOnly)
                    .contains("location");
        }

        assertThat(validator.validate(request(
                "FIELD_OBSERVATION", "NORMAL", "\u00A0Dock\t", null, "UNKNOWN"))).isEmpty();
    }

    @Test
    void keepsClientInputSeparateFromServerOwnedBackfillFlag() {
        var request = request("FIELD_OBSERVATION", "NORMAL", "Dock 4", null, "UNKNOWN");
        assertThat(request.toCommand().sourceCategory()).isEqualTo(FindingSourceCategory.FIELD_OBSERVATION);
        assertThat(request.toCommand().urgency()).isEqualTo(FindingUrgency.NORMAL);
        assertThat(java.util.Arrays.stream(FindingWorkflowApiModels.CreateRequest.class.getRecordComponents())
                .map(component -> component.getName())).doesNotContain("backfill");
    }

    private static Set<String> properties(Set<jakarta.validation.ConstraintViolation<FindingWorkflowApiModels.CreateRequest>> violations) {
        return violations.stream().map(violation -> violation.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    private static FindingWorkflowApiModels.CreateRequest request(
            String sourceCategory, String urgency, String location, String interimMeasure, String grade) {
        return new FindingWorkflowApiModels.CreateRequest(
                UUID.randomUUID(), "Synthetic description", OffsetDateTime.parse("2026-10-31T10:00:00Z"),
                null, null, grade, sourceCategory, urgency, location, interimMeasure);
    }
}
