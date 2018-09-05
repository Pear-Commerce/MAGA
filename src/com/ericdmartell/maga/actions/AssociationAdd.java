package com.ericdmartell.maga.actions;

import java.util.List;

import javax.sql.DataSource;

import com.ericdmartell.maga.MAGA;
import com.ericdmartell.maga.associations.MAGAAssociation;
import com.ericdmartell.maga.cache.MAGACache;
import com.ericdmartell.maga.objects.MAGAObject;
import com.ericdmartell.maga.utils.HistoryUtil;
import com.ericdmartell.maga.utils.JDBCUtil;
import com.ericdmartell.maga.utils.ReflectionUtils;

public class AssociationAdd {

	private DataSource dataSource;
	private MAGACache cache;
	private MAGA maga;

	public AssociationAdd(DataSource dataSource, MAGACache cache, MAGA maga) {
		this.dataSource = dataSource;
		this.maga = maga;
		this.cache = cache;
	}

	public void add(MAGAObject obj, MAGAObject obj2, MAGAAssociation association) {
		if (association.type() == MAGAAssociation.MANY_TO_MANY) {
			manyToMany(obj, obj2, association);
		} else {
			if (obj.getClass() == association.class1()) {
				oneToMany(obj, obj2, association);
			} else {
				oneToMany(obj2, obj, association);
			}
		}
	}

	private void manyToMany(MAGAObject obj, MAGAObject obj2, MAGAAssociation association) {
		
		
		// DB Part
		JDBCUtil.executeUpdate("insert into `" + association.class1().getSimpleName() + "_to_"
				+ association.class2().getSimpleName() + "`(`" + obj.getClass().getSimpleName() + "`,`"
				+ obj2.getClass().getSimpleName() + "`, dateAssociated) values(?,?, now())", dataSource, obj.id, obj2.id);

		// Cache Part
		cache.dirtyAssoc(obj, association);
		cache.dirtyAssoc(obj2, association);
	}

	private void oneToMany(MAGAObject objOfClass1, MAGAObject objOfClass2, MAGAAssociation association) {
		
		// We need this because if we're adding an assoc for a one-many, we might be switching the assoc of the old one.
		MAGAObject oldOneOfTheOneToMany = null;
		List<MAGAObject> listOfOldOneOfTheOneToMany = maga.loadAssociatedObjects(objOfClass2, association);
		if (!listOfOldOneOfTheOneToMany.isEmpty()) {
			oldOneOfTheOneToMany = listOfOldOneOfTheOneToMany.get(0);
		}
		
		
		// For historical changes.
		MAGAObject oldObject = maga.load(objOfClass2.getClass(), objOfClass2.id);

		// DB/Live object field swap Part.
		JDBCUtil.executeUpdate("update `" + objOfClass2.getClass().getSimpleName() + "` set `" + association.class2Column()
				+ "` = ? where id = ?", dataSource, objOfClass1.id, objOfClass2.id);
		ReflectionUtils.setFieldValue(objOfClass2, association.class2Column(), objOfClass1.id);

		// Cache Part.
		cache.dirtyObject(objOfClass2);
		cache.dirtyAssoc(objOfClass2, association);
		cache.dirtyAssoc(objOfClass1, association);
		if (oldOneOfTheOneToMany != null) {
			cache.dirtyAssoc(oldOneOfTheOneToMany, association);
		}

		// Since we changed an actual object, we record the change.
		HistoryUtil.recordHistory(oldObject, objOfClass2, maga, dataSource);
	}
}
