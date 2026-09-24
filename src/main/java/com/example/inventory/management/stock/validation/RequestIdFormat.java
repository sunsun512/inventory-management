package com.example.inventory.management.stock.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Pattern;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * requestId must be a standard UUID string (8-4-4-4-12 hex digits, 36 chars with hyphens, any
 * version). Upper and lower case are both accepted; the request DTOs normalize the value to lower
 * case before it is validated, looked up or stored, so case variants are the same key.
 */
@Documented
@Constraint(validatedBy = {})
@Pattern(regexp = RequestIdFormat.UUID_REGEX)
@ReportAsSingleViolation
@Target({FIELD, METHOD, PARAMETER, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface RequestIdFormat {

    String UUID_REGEX = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    String message() default "requestId는 UUID 형식(8-4-4-4-12, 하이픈 포함 36자)이어야 합니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
