package com.mightyoung.softwarefactory.dualprevention.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public final class CodePointSizeValidator implements ConstraintValidator<CodePointSize, CharSequence> {

    private int max;

    @Override
    public void initialize(CodePointSize constraintAnnotation) {
        this.max = constraintAnnotation.max();
    }

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        return value == null || value.toString().codePointCount(0, value.length()) <= max;
    }
}
