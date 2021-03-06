package com.ericdmartell.maga.actions;

import com.ericdmartell.maga.MAGA;
import com.ericdmartell.maga.annotations.MAGADataMigration;
import com.ericdmartell.maga.objects.DataMigrationRecord;
import com.ericdmartell.maga.objects.MAGAObject;
import org.reflections.Reflections;
import org.reflections.scanners.MethodAnnotationsScanner;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.scanners.TypeAnnotationsScanner;
import org.reflections.scanners.TypeElementsScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Runs migration methods annotated with @MAGADataMigration("lexicographical ordering string").  SchemaSync must be
 * run prior to this action.
 */
public class DataMigrate {

    MAGA maga;
    Reflections reflections;

    public DataMigrate(MAGA maga, Reflections reflections) {
        this.maga = maga;
        this.reflections = reflections;
    }

    public DataMigrate(MAGA maga) {
        this.maga = maga;
    }

    public void go() {
        Reflections  reflections = this.reflections != null ? this.reflections : (new Reflections(
                new ConfigurationBuilder()
                        .setUrls(ClasspathHelper.forPackage(""))
                        .setScanners(new MethodAnnotationsScanner(), new TypeElementsScanner(), new TypeAnnotationsScanner(), new
                                SubTypesScanner())));
        List<Method> methods     = new ArrayList<>();
        methods.addAll(reflections.getMethodsAnnotatedWith(MAGADataMigration.class));
        methods.sort(Comparator.comparing(this::getOrder));

        for (Method m : methods) {
            List<DataMigrationRecord> records = maga.loadWhere(DataMigrationRecord.class, "name = ?", m.getName());
            DataMigrationRecord record;
            if (!records.isEmpty()) {
                record = records.get(0);
                if (record.end != null) {
                    continue;
                }
                System.out.println("Re-starting migration " + m.getName());
            } else {
                record = new DataMigrationRecord();
                record.name = m.getName();
                record.order = getOrder(m);
                System.out.println("Starting migration " + m.getName());
            }
            record.start = new Date();
            maga.save(record);
            m.setAccessible(true);
            Object invokeOn = null;
            try {
                if (!Modifier.isStatic(m.getModifiers())) {
                    invokeOn = m.getDeclaringClass().newInstance();
                }
                m.invoke(invokeOn);
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
            record.end = new Date();
            maga.save(record);
            System.out.println("Completed migration " + m.getName());
        }
    }

    public String getOrder(Method m) {
        MAGADataMigration anno = m.getAnnotation(MAGADataMigration.class);
        String order = anno.order();
        if (order.isEmpty()) {
            order = m.getName();
        }
        return order;
    }
}
