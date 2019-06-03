package com.ericdmartell.maga.cache;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.ericdmartell.cache.Cache;
import com.ericdmartell.maga.associations.MAGAAssociation;
import com.ericdmartell.maga.objects.MAGALoadTemplate;
import com.ericdmartell.maga.objects.MAGAObject;

import gnu.trove.map.hash.THashMap;

public class MAGACache extends Cache {
	
	private MAGACache() {
		
	}
	private Cache delegate;
	
	// key is just Classname:ID
	public <T extends MAGAObject> List<String> getKeys(Class<T> clazz, Collection<Long> ids) {
		List<String> ret = new ArrayList<>();
		for (long id : ids) {
			ret.add(getKey(clazz, id));
		}
		return ret;
	}

	public final String getKey(MAGAObject simpleORMObject) {
		return getKey((Class<MAGAObject>) simpleORMObject.getClass(), simpleORMObject.id);
	}

	public final <T extends MAGAObject> String getKey(Class<T> clazz, long id) {
		return clazz.getName() + ":" + id;
	}

	public final <T extends MAGAObject> void setObjects(List<T> simpleORMObjects,
			MAGALoadTemplate dependentTemplate) {
		for (MAGAObject toSet : simpleORMObjects) {
			setObject(toSet, dependentTemplate);
		}
	}

	public final void setObject(MAGAObject simpleORMObject, 
			MAGALoadTemplate dependentTemplate) {
		set(getKey(simpleORMObject), simpleORMObject);
		if (dependentTemplate != null) {
			addTemplateDependency(simpleORMObject, dependentTemplate);
		}
	}

	public final void addTemplateDependency(MAGAObject simpleORMObject, MAGALoadTemplate dependentTemplate) {
		String dependencyKey = getKey(simpleORMObject) + ":template_dependencies";
		List<String> existingTemplateKeys = (List<String>) get(dependencyKey);
		if (existingTemplateKeys == null) {
			existingTemplateKeys = new ArrayList<>();
		} else {
			existingTemplateKeys = new ArrayList<>(existingTemplateKeys);
		}
		if (!existingTemplateKeys.contains(dependentTemplate.getKey())) {
			existingTemplateKeys.add(dependentTemplate.getKey());
			set(dependencyKey, existingTemplateKeys);
		}
	}

	public final <T extends MAGAObject> List<T> getObjects(Class<T> clazz, Collection<Long> ids) {

		Map<String, Object> ret = getBulk(getKeys(clazz, ids));

		return new ArrayList(ret.values());
	}

	public final List<Long> getAssociatedIds(MAGAObject obj, MAGAAssociation association) {
		return (List<Long>) get(getAssocKey(obj, association));
	}

	public final String getAssocKey(MAGAObject obj, MAGAAssociation association) {
		return association.class1().getSimpleName() + ":" + association.class2().getSimpleName() + ":"
				+ obj.getClass().getSimpleName() + ":" + obj.id;
	}

	public final void setAssociatedIds(MAGAObject obj, MAGAAssociation association, List<Long> associations,
			 MAGALoadTemplate dependentTemplate) {
		set(getAssocKey(obj, association), associations);
		if (dependentTemplate != null) {
			addTemplateDependencyOnAssoc(obj, association, dependentTemplate);
		}
	}

	public final void addTemplateDependencyOnAssoc(MAGAObject simpleORMObject, MAGAAssociation association,
			MAGALoadTemplate dependentTemplate) {
		String dependencyKey = getAssocKey(simpleORMObject, association) + ":template_dependencies";
		List<String> existingTemplateKeys = (List<String>) get(dependencyKey);
		if (existingTemplateKeys == null) {
			existingTemplateKeys = new ArrayList<>();
		} else {
			existingTemplateKeys = new ArrayList<>(existingTemplateKeys);
		}
		if (!existingTemplateKeys.contains(dependentTemplate.getKey())) {
			existingTemplateKeys.add(dependentTemplate.getKey());
			set(dependencyKey, existingTemplateKeys);
		}
	}

	public final void dirtyAssoc(MAGAObject obj, MAGAAssociation association) {
		String key = getAssocKey(obj, association);
		dirty(key);
		String dependencyKey = key + ":template_dependencies";
		List<String> existingTemplateKeys = (List<String>) get(dependencyKey);
		
		if (existingTemplateKeys != null) {
			for (String existingTemplateKey : existingTemplateKeys) {
				dirty(existingTemplateKey);
			}
		}
		dirty(dependencyKey);
	}

	public final void dirtyObject(MAGAObject obj) {
		String key = getKey(obj);
		dirty(key);

		String dependencyKey = key + ":template_dependencies";
		List<String> existingTemplateKeys = (List<String>) get(dependencyKey);
		if (existingTemplateKeys != null) {
			for (String existingTemplateKey : existingTemplateKeys) {
				dirty(existingTemplateKey);
			}
		}
		
		dirty("LoadAll:" + obj.getClass().getSimpleName());

	}

	public final void cacheAssociatedObjectsForTemplate(MAGAObject baseObject, MAGAAssociation association,
			List<MAGAObject> associatedObjects) {
		if (baseObject.templateAssociations == null) {
			baseObject.templateAssociations = new THashMap<>();
		}
		baseObject.templateAssociations.put(association, associatedObjects);

	}

	public final List<MAGAObject> getAssociatedObjectsForTemplate(MAGAObject baseObject,
			MAGAAssociation association) {
		if (baseObject.templateAssociations == null) {
			return null;
		}
		Map<MAGAAssociation, List<MAGAObject>> assocToObjects = baseObject.templateAssociations;
		return assocToObjects.get(association);

	}
	
	@Override
	public void setImpl(String key, Object val) {
		delegate.set(key, val);
	}
	
	@Override
	public Map<String, Object> getBulkImpl(List<String> keys) {
		return delegate.getBulk(keys);
	}
	
	@Override
	public Object getImpl(String key) {
		return delegate.get(key);
	}
	
	@Override
	public void flushImpl() {
		delegate.flush();
		
	}
	
	@Override
	public void dirtyImpl(String key) {
		delegate.dirty(key);
		
	}
	
	public static MAGACache getInstance(Cache cache) {
		MAGACache ret = new MAGACache();
		ret.delegate = cache;
		return ret;
	}

	
}
