package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.dualprevention.finding.workflow.ConfirmationOpinionText;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public final class OpinionNotBlankValidator implements ConstraintValidator<OpinionNotBlank, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return ConfirmationOpinionText.hasContent(value);
    }
}
