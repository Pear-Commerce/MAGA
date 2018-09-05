package com.ericdmartell.maga.actions;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.sql.DataSource;

import com.ericdmartell.maga.MAGA;
import com.ericdmartell.maga.cache.MAGACache;
import com.ericdmartell.maga.objects.MAGALoadTemplate;
import com.ericdmartell.maga.objects.MAGAObject;
import com.ericdmartell.maga.utils.JDBCUtil;
import com.ericdmartell.maga.utils.MAGAException;
import com.ericdmartell.maga.utils.ReflectionUtils;

public class ObjectLoad {

	private DataSource dataSource;
	private MAGALoadTemplate template;
	private MAGACache cache;

	public ObjectLoad(DataSource dataSource, MAGACache cache, MAGA maga,
			MAGALoadTemplate template) {
		this.dataSource = dataSource;
		this.cache = cache;
		this.template = template;
	}

	public List<MAGAObject> loadTemplate(MAGALoadTemplate template) {
		List<MAGAObject> ret = (List<MAGAObject>) cache.get(template.getKey());
		if (ret != null) {
			return ret;
		} else {
			ret = template.run(new MAGA(dataSource, cache, template));
			// save our result for next fetch.
			cache.set(template.getKey(), ret);
			return ret;
		}
	}

	public List<MAGAObject> loadAll(Class clazz) {
		return loadWhereExtra(clazz, "1", "");
	}

	public List<MAGAObject> loadWhereExtra(Class clazz, String where, String extra, Object... params) {
		Connection connection = JDBCUtil.getConnection(dataSource);
		try {
			ResultSet rst = JDBCUtil.executeQuery(connection, "select id from `" + clazz.getSimpleName() + String.format("` where %s %s", where, extra), params);
			List<Long> ids = new ArrayList<>();
			while (rst.next()) {
				ids.add(rst.getLong(1));
			}
			List<MAGAObject> ret = load(clazz, ids);
			return ret;
		} catch (SQLException e) {
			throw new MAGAException(e);
		} finally {
			JDBCUtil.closeConnection(connection);
		}
	}

	public MAGAObject load(Class clazz, long id) {
		//Just a wrapper on the load collection of ids.
		List<Long> ids = new ArrayList<>();
		ids.add(id);
		List<MAGAObject> retList = load(clazz, ids);
		if (retList.isEmpty()) {
			return null;
		} else {
			return retList.get(0);
		}
	}

	public List<MAGAObject> load(Class clazz, Collection<Long> ids) {

		// A running list of ids to load
		List<Long> toLoad = new ArrayList<>(ids);

		//Remove 0's
		List<String> zeroList = new ArrayList<>();
		zeroList.add("");
		zeroList.add(null);
		toLoad.removeAll(zeroList);
		
		//Don't make trips anywhere if the list is empty
		if (toLoad.isEmpty()) {
			return new ArrayList<>();
		}
		
		
		// Try getting them from memcached
		List<MAGAObject> ret = cache.getObjects(clazz, toLoad);

		// Remove the ids we got from memcached before going to the database.
		for (MAGAObject gotFromMemcached : ret) {
			toLoad.remove(gotFromMemcached.id);
		}

		// We still have ids that aren't in memcached, fetch from the database.
		if (!toLoad.isEmpty()) {
			List<MAGAObject> dbObjects = loadFromDB(clazz, toLoad);
			for (MAGAObject gotFromDB : dbObjects) {
				toLoad.remove(gotFromDB.id);
			}
			
			//We'll have them in the cache next time.
			cache.setObjects(dbObjects, template);

			ret.addAll(dbObjects);
		}

		// We went to memcached, we went to the db, and we still have ids left
		// over?
		if (!toLoad.isEmpty()) {
			// System.out.println("DB Misses for " + toLoad);
		}
		
		if (this.template != null) {
			for (MAGAObject object : ret) {
				this.cache.addTemplateDependency(object, this.template);
			}
		}
		return ret;
	}

	private List<MAGAObject> loadFromDB(Class<MAGAObject> clazz, Collection<Long> ids) {
		List<MAGAObject> ret = new ArrayList<>();

		// Fields with annotations
		List<String> fieldNames = new ArrayList<>(ReflectionUtils.getFieldNames(clazz));

		// Sql to bulk fetch all ids.
		String sql = getSQL(clazz, fieldNames, ids);
		Connection connection = JDBCUtil.getConnection(this.dataSource);

		try {
			ResultSet rst = JDBCUtil.executeQuery(connection, sql, ids);

			// Rather than repeatedly instantiating, we'll keep cloning this
			// guy. TODO: is this actually a perf gain?
			MAGAObject emptyObject = clazz.newInstance();

			while (rst.next()) {
				MAGAObject toFill = emptyObject.clone();
				// Fill those objects
				for (String fieldName : fieldNames) {
					ReflectionUtils.setFieldValue(toFill, fieldName, rst.getObject(fieldName));
				}

				ret.add(toFill);
			}
		} catch (SQLException | IllegalAccessException | InstantiationException e) {
			throw new MAGAException(e);
		} finally {
			JDBCUtil.closeConnection(connection);
		}
		return ret;

	}

	private String getSQL(Class<MAGAObject> clazz, Collection<String> fieldNames, Collection<Long> ids) {
		String sql = "select ";
		for (String fieldName : fieldNames) {
			sql += "`" + fieldName + "`,";
		}
		sql = sql.substring(0, sql.length() - 1);
		sql += " from `" + clazz.getSimpleName();
		if (ids.size() == 1) {
			sql += "` where id = ?";
		} else {
			sql += "` where id in (";
			for (long id : ids) {
				sql +=  "?,";
			}
			sql = sql.substring(0, sql.length() - 1);
			sql += ")";
		}
		return sql;
	}

}
