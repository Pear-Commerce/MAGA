package com.ericdmartell.maga;

import java.math.BigDecimal;
import java.util.Date;

import com.ericdmartell.maga.annotations.MAGAORMField;
import com.ericdmartell.maga.objects.MAGAObject;

public class Obj3 extends MAGAObject {
	@MAGAORMField
	public String field3;

	@MAGAORMField
	public BigDecimal val;

	public enum EnumTest {
		one,
		two
	}

	@MAGAORMField
	public Date date;

	@MAGAORMField
	public EnumTest enumTest;

	@MAGAORMField
	public Class<? extends MAGAObject> classTest;

	@Override
	public String toString() {
		return "Obj3 [field3=" + field3 + ", id=" + id + "]";
	}
	
}
