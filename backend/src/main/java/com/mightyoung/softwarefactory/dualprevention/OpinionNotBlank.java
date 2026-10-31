package com.mightyoung.softwarefactory.dualprevention;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Requires a confirmation opinion to contain a code point outside Unicode White_Space. */
@Documented
@Constraint(validatedBy = OpinionNotBlankValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER,
        ElementType.ANNOTATION_TYPE, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface OpinionNotBlank {

    String message() default "must contain non-whitespace text";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
