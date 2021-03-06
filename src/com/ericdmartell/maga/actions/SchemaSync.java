package com.ericdmartell.maga.actions;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import com.ericdmartell.maga.cache.Cache;
import com.ericdmartell.maga.MAGA;
import com.ericdmartell.maga.utils.ParallelLoader;
import org.apache.commons.lang.StringUtils;
import org.reflections.Reflections;

import com.ericdmartell.maga.annotations.MAGANoHistory;
import com.ericdmartell.maga.annotations.MAGAORMField;
import com.ericdmartell.maga.annotations.MAGATimestampID;
import com.ericdmartell.maga.associations.MAGAAssociation;
import com.ericdmartell.maga.objects.MAGAObject;
import com.ericdmartell.maga.utils.JDBCUtil;
import com.ericdmartell.maga.utils.MAGAException;
import com.ericdmartell.maga.utils.ReflectionUtils;

import gnu.trove.map.hash.THashMap;
import org.reflections.scanners.Scanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;

public class SchemaSync extends MAGAAwareContext {

    Reflections reflections;

    public SchemaSync(MAGA maga, Reflections reflections) {
        super(maga);
        this.reflections = reflections;
    }

    public void go() {
        final AtomicBoolean changes = new AtomicBoolean();
        changes.set(false);
        final DataSource dataSource = getDataSourceWrite();
        final String schema = JDBCUtil.executeQueryAndReturnSingleString(dataSource, "select database()");
        try {
            // The framework includes some objects needed to be synced too
            Reflections frameworkReflections = new Reflections(
                    new ConfigurationBuilder()
                            .setUrls(ClasspathHelper.forPackage("com.ericdmartell.maga")));

            Reflections userReflections = reflections != null ? reflections : new Reflections("", new Scanner[0]);

            Set<Class<MAGAObject>> classes = ConcurrentHashMap.newKeySet();
            classes.addAll(new ArrayList(frameworkReflections.getSubTypesOf(MAGAObject.class)));
            classes.addAll(new ArrayList(userReflections.getSubTypesOf(MAGAObject.class)));

            ParallelLoader.process(classes, clazz -> {
                Connection connection = JDBCUtil.getConnection(dataSource);
                if (Modifier.isAbstract(clazz.getModifiers())) {
                    return;
                }
                String tableName = clazz.getSimpleName();

                boolean tableExists = JDBCUtil.executeQueryAndReturnSingleLong(dataSource,
                        "SELECT count(*) FROM information_schema.TABLES WHERE  (TABLE_NAME = ?) and (TABLE_SCHEMA = ?)",
                        tableName, schema) == 1;
                if (!tableExists) {
                    changes.set(true);
                    String sql = "create table `" + tableName + "`(id bigint not null AUTO_INCREMENT, primary key(id)) ENGINE=InnoDB";
                    String defaultCharset = getMAGA().getDefaultCharacterSet();
                    String defaultCollate = getMAGA().getDefaultCollate();
                    if (defaultCharset != null) {
                        sql += " DEFAULT CHARSET=utf8mb4";
                        if (defaultCollate != null) {
                            sql += " COLLATE utf8mb4_unicode_ci";
                        }
                    }
                    JDBCUtil.executeUpdate(sql, dataSource);
                    System.out.println("Creating table " + tableName + ". (" + sql + ")");
                }

                boolean historyTableExists = JDBCUtil.executeQueryAndReturnSingleLong(dataSource,
                        "SELECT count(*) FROM information_schema.TABLES WHERE  (TABLE_NAME = ?) and (TABLE_SCHEMA = ?)",
                        tableName + "_history", schema) == 1;
                if (!historyTableExists && !clazz.isAnnotationPresent(MAGANoHistory.class)) {
                    JDBCUtil.executeUpdate("create table " + tableName + "_history"
                            + "(id bigint, date datetime, changes longtext, stack longtext)", dataSource);
                    JDBCUtil.executeUpdate("alter table " + tableName + "_history" + " add index id(id)", dataSource);
                    JDBCUtil.executeUpdate("alter table " + tableName + "_history" + " add index date(date)",
                            dataSource);
                    System.out.println("Creating history table " + tableName + "_history");
                }
                ResultSet rst = JDBCUtil.executeQuery(connection, "describe `" + tableName + "`");
                Map<String, String> columnsToTypes = new ConcurrentHashMap<>();
                List<String> indexes = new ArrayList<>();
                try {
                    while (rst.next()) {
                        columnsToTypes.put(rst.getString("Field"), rst.getString("Type"));
                        if (!StringUtils.isBlank(rst.getString("Key"))) {
                            indexes.add(rst.getString("Field"));
                        }
                    }
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
                Collection<String> fieldNames = new ArrayList<String>(ReflectionUtils.getFieldNames(clazz));
                fieldNames.add("id");
                for (String columnName : fieldNames) {
                    Class fieldType;
                    if (columnName.equals("id")) {
                        fieldType = long.class;
                    } else {
                        fieldType = ReflectionUtils.getFieldType(clazz, columnName);
                    }
                    String columnType;
                    String nullable = null;
                    String defaultValue = null;
                    Field field;
                    try {
                        field = clazz.getField(columnName);
                    } catch (NoSuchFieldException e) {
                        try {
                            field = clazz.getDeclaredField(columnName);
                        } catch (NoSuchFieldException ex) {
                            throw new RuntimeException(e);
                        }
                    }

                    boolean isId = columnName.equals("id");
                    if (field.getAnnotation(MAGAORMField.class) != null
                            && !field.getAnnotation(MAGAORMField.class).dataType().equals("")) {
                        columnType = field.getAnnotation(MAGAORMField.class).dataType();
                    } else if (fieldType == long.class || fieldType == int.class || fieldType == Integer.class
                            || fieldType == Long.class) {
                        columnType = "bigint(20)";
                    } else if (fieldType == BigDecimal.class) {
                        columnType = "decimal(10,2)";
                    } else if (fieldType == Date.class) {
                        columnType = "timestamp";
                        nullable = "null";
                        defaultValue = "null";
                    } else if (isId) {
                        columnType = "bigint";
                    } else {
                        columnType = "varchar(500)";
                    }

                    String columnDefinition = columnType
                            + ((nullable != null) ? " " + nullable : "")
                            + ((defaultValue != null) ? " default " + defaultValue : "");
                    if (!columnsToTypes.containsKey(columnName)) {
                        changes.set(true);
                        System.out.println("Adding column " + columnName + ":" + columnDefinition + " to table " + tableName);

                        // Column doesnt exist
                        JDBCUtil.executeUpdate(
                                "alter table `" + tableName + "` add column `" + columnName + "` " + columnDefinition,
                                dataSource);
                        if (fieldType.equals(Boolean.class) || fieldType.equals(boolean.class)) {
                            JDBCUtil.executeUpdate(
                                    "update `" + tableName + "` set `" + columnName + "` = 0 ",
                                    dataSource);
                        } else if (fieldType == long.class || fieldType == int.class || fieldType == Integer.class
                                || fieldType == Long.class) {
                            JDBCUtil.executeUpdate(
                                    "update `" + tableName + "` set `" + columnName + "` = 0 ",
                                    dataSource);
                        }
                    } else if (!columnsToTypes.get(columnName).toLowerCase().contains(columnType.toLowerCase())
                            && ((fieldType != String.class && ReflectionUtils.standardClasses.contains(fieldType))
                            || !columnsToTypes.get(columnName).toLowerCase().contains("text"))) {
                        changes.set(true);

                        System.out.println(
                                "Modifying column " + columnName + ":" + columnDefinition + " on table " + tableName + " (originally was " + columnsToTypes.get(columnName).toLowerCase() + ")");
                        JDBCUtil.executeUpdate(
                                "alter table `" + tableName + "` modify column `" + columnName + "` " + columnDefinition + (isId ? " AUTO_INCREMENT" : ""),
                                dataSource);
                    }
                }
                for (String indexedColumn : ReflectionUtils.getSQLIndexedColumns(clazz)) {
                    if (!indexes.contains(indexedColumn)) {
                        try {
                            System.out.println("Adding index " + indexedColumn + " to table " + tableName);
                            JDBCUtil.executeUpdate("alter table `" + tableName + "` add index `" + indexedColumn + "`(`"
                                    + indexedColumn + "`)", dataSource);
                        } catch (Exception e) {
                            e.printStackTrace();
                            System.err.println("Index may be too wide. Attempting with fixed width.");
                            JDBCUtil.executeUpdate("alter table `" + tableName + "` add index `" + indexedColumn + "`(`"
                                    + indexedColumn + "`(76))", dataSource);
                        }
                    }
                }
                JDBCUtil.closeConnection(connection);
            });

            List<Class<MAGAAssociation>> associationsClasses = Collections.synchronizedList(new ArrayList(
                    userReflections.getSubTypesOf(MAGAAssociation.class)));
            List<MAGAAssociation> associations = Collections.synchronizedList(new ArrayList<>());
            for (Class<MAGAAssociation> clazz : associationsClasses) {
                associations.add(clazz.newInstance());
            }
            ParallelLoader.process(associations, association -> {
				Connection connection = JDBCUtil.getConnection(dataSource);
				try {
                    if (association.type() == MAGAAssociation.ONE_TO_MANY) {
                        String tableName = association.class2().getSimpleName();
                        ResultSet rst = JDBCUtil.executeQuery(connection, "describe `" + tableName + "`");
                        Map<String, String> columnsToTypes = new ConcurrentHashMap<>();
                        List<String> indexes = new ArrayList<>();

                        while (rst.next()) {
                            columnsToTypes.put(rst.getString("Field"), rst.getString("Type"));
                            if (!rst.getString("Key").trim().isEmpty()) {
                                indexes.add(rst.getString("Field"));
                            }

                        }
                        String columnName = association.class2Column();
                        if (!columnsToTypes.containsKey(columnName)) {
                            changes.set(true);
                            System.out.println("Adding join column " + columnName + " on " + tableName);
                            if (association.class1().isAnnotationPresent(MAGATimestampID.class)) {
                                JDBCUtil.executeUpdate(
                                        "alter table `" + tableName + " `add column " + columnName + " varchar(255)",
                                        dataSource);
                            } else {
                                JDBCUtil.executeUpdate(
                                        "alter table `" + tableName + "` add column " + columnName + " bigint(18)",
                                        dataSource);
                            }

                        }
                        if (!indexes.contains(columnName)) {
                            System.out.println("Adding index to join column " + columnName + " on " + tableName);
                            JDBCUtil.executeUpdate(
                                    "alter table `" + tableName + "` add index " + columnName + "(" + columnName + ")",
                                    dataSource);
                        }
                    } else {
                        String tableName = association.class1().getSimpleName() + "_to_"
                                + association.class2().getSimpleName();
                        boolean tableExists = JDBCUtil.executeQueryAndReturnSingleLong(dataSource,
                                "SELECT count(*) FROM information_schema.TABLES WHERE  (TABLE_NAME = ?) and (TABLE_SCHEMA = ?)",
                                tableName, schema) == 1;
                        if (!tableExists) {
                            String col1 = association.class1().getSimpleName();
                            String col2 = association.class2().getSimpleName();
                            String type1 = association.class1().isAnnotationPresent(MAGATimestampID.class) ? "varchar(255)"
                                    : "bigint(18)";
                            String type2 = association.class2().isAnnotationPresent(MAGATimestampID.class) ? "varchar(255)"
                                    : "bigint(18)";
                            changes.set(true);
                            JDBCUtil.executeUpdate("create table `" + tableName + "`(" + col1 + " " + type1 + ", " + col2
                                    + "  " + type2 + ", dateAssociated timestamp null default null, firstAssoc varchar(1))", dataSource);
                            JDBCUtil.executeUpdate(
                                    "alter table `" + tableName + "` add index " + col1 + "(" + col1 + "," + col2 + ")",
                                    dataSource);
                            JDBCUtil.executeUpdate(
                                    "alter table `" + tableName + "` add index " + col2 + "(" + col2 + "," + col1 + ")",
                                    dataSource);
                            JDBCUtil.executeUpdate(
                                    "alter table `" + tableName + "` add index dateAssociated(dateAssociated)",
                                    dataSource);
                            JDBCUtil.executeUpdate(
                                    "alter table `" + tableName + "` add index firstAssoc(firstAssoc)",
                                    dataSource);
                            System.out.println("Creating join table " + tableName);
                        } else {
                            try {
                                JDBCUtil.executeQueryAndReturnStrings(dataSource, "select dateAssociated from " + tableName);
                            } catch (Exception e) {
                                System.out.println("Adding date to " + tableName);
                                JDBCUtil.executeUpdate(
                                        "alter table `" + tableName + "` add column dateAssociated timestamp null default null",
                                        dataSource);
                                JDBCUtil.executeUpdate(
                                        "update `" + tableName + "` set dateAssociated = now()",
                                        dataSource);
                                JDBCUtil.executeUpdate(
                                        "alter table `" + tableName + "` add index dateAssociated(dateAssociated)",
                                        dataSource);
                            }

                            try {
                                JDBCUtil.executeQueryAndReturnStrings(dataSource, "select firstAssoc from " + tableName);
                            } catch (Exception e) {
                                System.out.println("Adding firstAssoc to " + tableName);
                                JDBCUtil.executeUpdate(
                                        "alter table `" + tableName + "` add column firstAssoc varchar(1)",
                                        dataSource);
                                JDBCUtil.executeUpdate(
                                        "update `" + tableName + "` set dateAssociated = now()",
                                        dataSource);
                                JDBCUtil.executeUpdate(
                                        "alter table `" + tableName + "` add index firstAssoc(firstAssoc)",
                                        dataSource);
                            }
                        }
                    }
                } catch (Exception e) {
                    throw new MAGAException(e);
                } finally {
                    JDBCUtil.closeConnection(connection);
                }
            });
        } catch (Exception e) {
            throw new MAGAException(e);
        }
        if (changes.get()) {
            Cache cache = getCache();
            if (cache != null) {
                cache.flush();
            }
        }
    }
}