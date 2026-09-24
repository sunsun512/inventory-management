package com.example.inventory.management.inventory.stock.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Max;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Business limit on the quantity of a single inbound/outbound request. Composed over @Max
 * (rather than using @Max directly) so the violation carries its own constraint code
 * ("QuantityLimit"), which lets GlobalExceptionHandler map it to 409 QUANTITY_LIMIT_EXCEEDED
 * instead of the generic 400 VALIDATION_FAILED without catching unrelated @Max usages.
 */
@Documented
@Constraint(validatedBy = {})
@Max(QuantityLimit.MAX_QUANTITY_PER_REQUEST)
@ReportAsSingleViolation
@Target({FIELD, METHOD, PARAMETER, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface QuantityLimit {

    long MAX_QUANTITY_PER_REQUEST = 10_000L;

    String message() default "1회 요청 수량은 " + MAX_QUANTITY_PER_REQUEST + "개를 초과할 수 없습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
