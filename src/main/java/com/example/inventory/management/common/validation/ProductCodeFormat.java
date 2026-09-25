package com.example.inventory.management.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * productCode format shared by stock-in/stock-out requests and the product list filter: upper-case
 * letters and digits only, at most {@value #MAX_LENGTH} chars. {@code null} is valid, so required
 * fields add {@code @NotNull} separately.
 *
 * <p>No {@code @ReportAsSingleViolation}: each composing constraint reports its own message, so a
 * too-long code gets the {@code @Size} message and a malformed one the {@code @Pattern} message.
 */
@Documented
@Constraint(validatedBy = {})
@Size(max = ProductCodeFormat.MAX_LENGTH, message = "productCode는 " + ProductCodeFormat.MAX_LENGTH + "자 이하여야 합니다.")
@Pattern(regexp = ProductCodeFormat.REGEX, message = "productCode는 영문 대문자와 숫자로만 구성되어야 합니다.")
@Target({FIELD, METHOD, PARAMETER, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface ProductCodeFormat {

    int MAX_LENGTH = 64;

    String REGEX = "^[A-Z0-9]+$";

    String message() default "productCode는 영문 대문자와 숫자로만 구성되어야 합니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
