package com.ainq.izgateway.extract.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@Inherited
public @interface FHIRField {
    String value();
    SystemValueType systemValueType() default SystemValueType.FIXED;
    String system() default "";
    String code() default "";
    String[] map() default {};
}
