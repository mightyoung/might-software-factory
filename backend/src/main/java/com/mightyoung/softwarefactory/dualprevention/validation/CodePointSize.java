package com.mightyoung.softwarefactory.dualprevention.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Maximum Unicode code-point length, matching OpenAPI maxLength and PostgreSQL char_length. */
@Documented
@Constraint(validatedBy = CodePointSizeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface CodePointSize {

    String message() default "must not exceed the maximum Unicode code-point length";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    int max();
}
