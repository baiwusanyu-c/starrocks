// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.connector.hive;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.HiveTable;
import com.starrocks.catalog.PartitionKey;
import com.starrocks.catalog.Table;
import com.starrocks.common.DdlException;
import com.starrocks.common.MetaNotFoundException;
import com.starrocks.connector.ConnectorTableId;
import com.starrocks.connector.MetastoreType;
import com.starrocks.connector.PartitionUtil;
import com.starrocks.connector.exception.StarRocksConnectorException;
import com.starrocks.sql.ast.CreateTableLikeStmt;
import com.starrocks.sql.ast.CreateTableStmt;
import com.starrocks.sql.ast.ListPartitionDesc;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkState;
import static com.starrocks.connector.hive.HiveWriteUtils.checkLocationProperties;
import static com.starrocks.connector.hive.HiveWriteUtils.createDirectory;
import static com.starrocks.connector.hive.HiveWriteUtils.isDirectory;
import static com.starrocks.connector.hive.HiveWriteUtils.isEmpty;
import static com.starrocks.connector.hive.HiveWriteUtils.pathExists;
import static com.starrocks.server.CatalogMgr.ResourceMappingCatalog.toResourceName;

public class HiveMetastoreOperations {
    private static final Logger LOG = LogManager.getLogger(HiveMetastoreOperations.class);
    public static String BACKGROUND_THREAD_NAME_PREFIX = "background-get-partitions-statistics-";
    public static final String LOCATION_PROPERTY = "location";
    public static final String EXTERNAL_LOCATION_PROPERTY = "external_location";
    public static final String FILE_FORMAT = "file_format";
    private final CachingHiveMetastore metastore;
    private final boolean enableCatalogLevelCache;
    private final Configuration hadoopConf;
    private final MetastoreType metastoreType;
    private final String catalogName;

    public HiveMetastoreOperations(CachingHiveMetastore cachingHiveMetastore,
                                   boolean enableCatalogLevelCache,
                                   Configuration hadoopConf,
                                   MetastoreType metastoreType,
                                   String catalogName) {
        this.metastore = cachingHiveMetastore;
        this.enableCatalogLevelCache = enableCatalogLevelCache;
        this.hadoopConf = hadoopConf;
        this.metastoreType = metastoreType;
        this.catalogName = catalogName;
    }

    public List<String> getAllDatabaseNames() {
        return metastore.getAllDatabaseNames();
    }

    public void createDb(String dbName, Map<String, String> properties) {
        properties = properties == null ? new HashMap<>() : properties;
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key.equalsIgnoreCase(LOCATION_PROPERTY)) {
                try {
                    URI uri = new Path(value).toUri();
                    FileSystem fileSystem = FileSystem.get(uri, hadoopConf);
                    fileSystem.exists(new Path(value));
                } catch (Exception e) {
                    LOG.error("Invalid location URI: {}", value, e);
                    throw new StarRocksConnectorException("Invalid location URI: %s. msg: %s", value, e.getMessage());
                }
            } else {
                throw new IllegalArgumentException("Unrecognized property: " + key);
            }
        }

        metastore.createDb(dbName, properties);
    }

    public void dropDb(String dbName, boolean force) throws MetaNotFoundException {
        Database database;
        try {
            database = getDb(dbName);
        } catch (Exception e) {
            LOG.error("Failed to access database {}", dbName, e);
            throw new MetaNotFoundException("Failed to access database " + dbName);
        }

        if (database == null) {
            throw new MetaNotFoundException("Not found database " + dbName);
        }

        String dbLocation = database.getLocation();
        if (Strings.isNullOrEmpty(dbLocation)) {
            throw new MetaNotFoundException("Database location is empty");
        }
        boolean deleteData = false;
        try {
            deleteData = !FileSystem.get(URI.create(dbLocation), hadoopConf)
                    .listLocatedStatus(new Path(dbLocation)).hasNext();
        } catch (Exception e) {
            LOG.error("Failed to check database directory", e);
        }

        metastore.dropDb(dbName, deleteData);
    }

    public Database getDb(String dbName) {
        return metastore.getDb(dbName);
    }

    public List<String> getAllTableNames(String dbName) {
        return metastore.getAllTableNames(dbName);
    }

    public boolean createTable(CreateTableStmt stmt, List<Column> partitionColumns) throws DdlException {
        String dbName = stmt.getDbName();
        String tableName = stmt.getTableName();
        Map<String, String> properties = stmt.getProperties() != null ? stmt.getProperties() : new HashMap<>();
        Path tablePath = null;
        boolean tableLocationExists = false;
        if (!stmt.isExternal()) {
            checkLocationProperties(properties);
            if (!Strings.isNullOrEmpty(properties.get(LOCATION_PROPERTY))) {
                String tableLocationWithUserAssign = properties.get(LOCATION_PROPERTY);
                tablePath = new Path(tableLocationWithUserAssign);
                if (pathExists(tablePath, hadoopConf)) {
                    tableLocationExists = true;
                    if (!isEmpty(tablePath, hadoopConf)) {
                        throw new StarRocksConnectorException("not support creating table under non-empty directory: %s",
                                tableLocationWithUserAssign);
                    }
                }
            } else {
                tablePath = getDefaultLocation(dbName, tableName);
            }
        } else {
            // checkExternalLocationProperties(properties);
            if (properties.containsKey(EXTERNAL_LOCATION_PROPERTY)) {
                tablePath = new Path(properties.get(EXTERNAL_LOCATION_PROPERTY));
            } else if (properties.containsKey(LOCATION_PROPERTY)) {
                tablePath = new Path(properties.get(LOCATION_PROPERTY));
            }
            tableLocationExists = true;
        }

        HiveStorageFormat.check(properties);

        List<String> partitionColNames;
        if (partitionColumns.isEmpty()) {
            partitionColNames = stmt.getPartitionDesc() != null ?
                    ((ListPartitionDesc) stmt.getPartitionDesc()).getPartitionColNames() : new ArrayList<>();
        } else {
            partitionColNames = partitionColumns.stream().map(Column::getName).collect(Collectors.toList());
        }

        // default is managed table
        HiveTable.HiveTableType tableType = HiveTable.HiveTableType.MANAGED_TABLE;
        if (stmt.isExternal()) {
            tableType = HiveTable.HiveTableType.EXTERNAL_TABLE;
        }
        HiveTable.Builder builder = HiveTable.builder()
                .setId(ConnectorTableId.CONNECTOR_ID_GENERATOR.getNextId().asInt())
                .setTableName(tableName)
                .setCatalogName(catalogName)
                .setResourceName(toResourceName(catalogName, "hive"))
                .setHiveDbName(dbName)
                .setHiveTableName(tableName)
                .setPartitionColumnNames(partitionColNames)
                .setDataColumnNames(stmt.getColumns().stream()
                        .map(Column::getName)
                        .collect(Collectors.toList()).subList(0, stmt.getColumns().size() - partitionColNames.size()))
                .setFullSchema(stmt.getColumns())
                .setTableLocation(tablePath == null ? null : tablePath.toString())
                .setProperties(stmt.getProperties())
                .setStorageFormat(HiveStorageFormat.get(properties.getOrDefault(FILE_FORMAT, "parquet")))
                .setCreateTime(System.currentTimeMillis())
                .setHiveTableType(tableType);
        Table table = builder.build();
        try {
            if (!tableLocationExists) {
                createDirectory(tablePath, hadoopConf);
            }
            metastore.createTable(dbName, table);
        } catch (Exception e) {
            LOG.error("Failed to create table {}.{}", dbName, tableName);
            boolean shouldDelete;
            try {
                if (tableExists(dbName, tableName)) {
                    LOG.warn("Table {}.{} already exists. But some error occur such as accessing meta service timeout",
                            dbName, table, e);
                    return true;
                }
                FileSystem fileSystem = FileSystem.get(URI.create(tablePath.toString()), hadoopConf);
                shouldDelete = !fileSystem.listLocatedStatus(tablePath).hasNext() && !tableLocationExists;
                if (shouldDelete) {
                    fileSystem.delete(tablePath);
                }
            } catch (Exception e1) {
                LOG.error("Failed to delete table location {}", tablePath, e);
            }
            throw new DdlException(String.format("Failed to create table %s.%s. msg: %s", dbName, tableName, e.getMessage()));
        }

        return true;
    }

    public boolean createTable(CreateTableStmt stmt) throws DdlException {
        return createTable(stmt, ImmutableList.of());
    }

    public boolean createTableLike(CreateTableLikeStmt stmt) throws DdlException {
        String existedDbName = stmt.getExistedDbName();
        String existedTableName = stmt.getExistedTableName();
        Table likeTable = getTable(existedDbName, existedTableName);
        return createTable(stmt.getCreateTableStmt(), likeTable.getPartitionColumns());
    }

    public void dropTable(String dbName, String tableName) {
        metastore.dropTable(dbName, tableName);
    }

    public Table getTable(String dbName, String tableName) {
        return metastore.getTable(dbName, tableName);
    }

    public boolean tableExists(String dbName, String tableName) {
        return metastore.tableExists(dbName, tableName);
    }

    public List<String> getPartitionKeys(String dbName, String tableName) {
        return metastore.getPartitionKeysByValue(dbName, tableName, HivePartitionValue.ALL_PARTITION_VALUES);
    }

    public List<String> getPartitionKeysByValue(String dbName, String tableName, List<Optional<String>> partitionValues) {
        return metastore.getPartitionKeysByValue(dbName, tableName, partitionValues);
    }

    public Partition getPartition(String dbName, String tableName, List<String> partitionValues) {
        return metastore.getPartition(dbName, tableName, partitionValues);
    }

    public void addPartitions(String dbName, String tableName, List<HivePartitionWithStats> partitions) {
        metastore.addPartitions(dbName, tableName, partitions);
    }

    public void dropPartition(String dbName, String tableName, List<String> partitionValues, boolean deleteData) {
        metastore.dropPartition(dbName, tableName, partitionValues, deleteData);
    }

    public boolean partitionExists(Table table, List<String> partitionValues) {
        return metastore.partitionExists(table, partitionValues);
    }

    public Map<String, Partition> getPartitionByPartitionKeys(Table table, List<PartitionKey> partitionKeys) {
        String dbName = (table).getCatalogDBName();
        String tblName = (table).getCatalogTableName();
        List<String> partitionColumnNames = (table).getPartitionColumnNames();
        List<String> partitionNames = partitionKeys.stream()
                .map(partitionKey -> PartitionUtil.toHivePartitionName(partitionColumnNames, partitionKey))
                .collect(Collectors.toList());

        return metastore.getPartitionsByNames(dbName, tblName, partitionNames);
    }

    public Map<String, Partition> getPartitionByNames(Table table, List<String> partitionNames) {
        String dbName = (table).getCatalogDBName();
        String tblName = (table).getCatalogTableName();
        return metastore.getPartitionsByNames(dbName, tblName, partitionNames);
    }

    public HivePartitionStats getTableStatistics(String dbName, String tblName) {
        return metastore.getTableStatistics(dbName, tblName);
    }

    public Map<String, HivePartitionStats> getPartitionStatistics(Table table, List<String> partitionNames) {
        String catalogName = (table).getCatalogName();
        String dbName = (table).getCatalogDBName();
        String tblName = (table).getCatalogTableName();
        List<HivePartitionName> hivePartitionNames = partitionNames.stream()
                .map(partitionName -> HivePartitionName.of(dbName, tblName, partitionName))
                .peek(hivePartitionName -> checkState(hivePartitionName.getPartitionNames().isPresent(),
                        "partition name is missing"))
                .collect(Collectors.toList());

        Map<String, HivePartitionStats> partitionStats;
        if (enableCatalogLevelCache) {
            partitionStats = metastore.getPresentPartitionsStatistics(hivePartitionNames);
        } else {
            partitionStats = metastore.getPartitionStatistics(table, partitionNames);
        }

        return partitionStats;
    }

    public void invalidateAll() {
        metastore.invalidateAll();
    }

    public void updateTableStatistics(String dbName, String tableName, Function<HivePartitionStats, HivePartitionStats> update) {
        metastore.updateTableStatistics(dbName, tableName, update);
    }

    public void updatePartitionStatistics(String dbName, String tableName, String partitionName,
                                          Function<HivePartitionStats, HivePartitionStats> update) {
        metastore.updatePartitionStatistics(dbName, tableName, partitionName, update);
    }

    public Path getDefaultLocation(String dbName, String tableName) {
        Database database = getDb(dbName);

        if (database == null) {
            throw new StarRocksConnectorException("Database '%s' not found", dbName);
        }
        if (Strings.isNullOrEmpty(database.getLocation())) {
            throw new StarRocksConnectorException("Failed to find location in database '%s'. Please define the location" +
                    " when you create table or recreate another database with location." +
                    " You could execute the SQL command like 'CREATE TABLE <table_name> <columns> " +
                    "PROPERTIES('location' = '<location>')", dbName);
        }

        String dbLocation = database.getLocation();
        Path databasePath = new Path(dbLocation);

        if (!pathExists(databasePath, hadoopConf)) {
            throw new StarRocksConnectorException("Database '%s' location does not exist: %s", dbName, databasePath);
        }

        if (!isDirectory(databasePath, hadoopConf)) {
            throw new StarRocksConnectorException("Database '%s' location is not a directory: %s",
                    dbName, databasePath);
        }

        Path targetPath = new Path(databasePath, tableName);
        if (pathExists(targetPath, hadoopConf)) {
            throw new StarRocksConnectorException("Target directory for table '%s.%s' already exists: %s",
                    dbName, tableName, targetPath);
        }

        return targetPath;
    }
}
