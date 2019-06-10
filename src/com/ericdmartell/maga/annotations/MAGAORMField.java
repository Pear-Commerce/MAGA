package com.ericdmartell.maga.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface MAGAORMField {
	public boolean isSQLIndex() default false;
	public boolean isCacheIndex() default false;
	public String dataType() default "";
}
