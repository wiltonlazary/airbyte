/*
 * MIT License
 *
 * Copyright (c) 2020 Airbyte
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.airbyte.config.persistence;

import static io.airbyte.db.instance.configs.jooq.Tables.AIRBYTE_CONFIGS;
import static org.jooq.impl.DSL.asterisk;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.select;

import com.fasterxml.jackson.databind.JsonNode;
import io.airbyte.commons.json.Jsons;
import io.airbyte.config.AirbyteConfig;
import io.airbyte.config.ConfigSchema;
import io.airbyte.config.ConfigSchemaMigrationSupport;
import io.airbyte.config.StandardDestinationDefinition;
import io.airbyte.config.StandardSourceDefinition;
import io.airbyte.db.Database;
import io.airbyte.db.ExceptionWrappingDatabase;
import io.airbyte.validation.json.JsonValidationException;
import java.io.IOException;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.Record2;
import org.jooq.Result;
import org.jooq.impl.SQLDataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatabaseConfigPersistence implements ConfigPersistence {

  private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseConfigPersistence.class);

  private final ExceptionWrappingDatabase database;

  public DatabaseConfigPersistence(Database database) {
    this.database = new ExceptionWrappingDatabase(database);
  }

  /**
   * Populate the {@code airbyte_configs} table with configs from the seed persistence. Only do so if the table is empty. Otherwise, we assume that it
   * has been populated.
   */
  public DatabaseConfigPersistence loadData(ConfigPersistence seedConfigPersistence) throws IOException {
    database.transaction(ctx -> {
      boolean isInitialized = ctx.fetchExists(select().from(AIRBYTE_CONFIGS).where());
      if (isInitialized) {
        updateConfigsFromSeed(ctx, seedConfigPersistence);
      } else {
        copyConfigsFromSeed(ctx, seedConfigPersistence);
      }
      return null;
    });
    return this;
  }

  public ValidatingConfigPersistence withValidation() {
    return new ValidatingConfigPersistence(this);
  }

  @Override
  public <T> T getConfig(AirbyteConfig configType, String configId, Class<T> clazz)
      throws ConfigNotFoundException, JsonValidationException, IOException {
    Result<Record> result = database.query(ctx -> ctx.select(asterisk())
        .from(AIRBYTE_CONFIGS)
        .where(AIRBYTE_CONFIGS.CONFIG_TYPE.eq(configType.name()), AIRBYTE_CONFIGS.CONFIG_ID.eq(configId))
        .fetch());

    if (result.isEmpty()) {
      throw new ConfigNotFoundException(configType, configId);
    } else if (result.size() > 1) {
      throw new IllegalStateException(String.format("Multiple %s configs found for ID %s: %s", configType, configId, result));
    }

    return Jsons.deserialize(result.get(0).get(AIRBYTE_CONFIGS.CONFIG_BLOB).data(), clazz);
  }

  @Override
  public <T> List<T> listConfigs(AirbyteConfig configType, Class<T> clazz) throws IOException {
    Result<Record> results = database.query(ctx -> ctx.select(asterisk())
        .from(AIRBYTE_CONFIGS)
        .where(AIRBYTE_CONFIGS.CONFIG_TYPE.eq(configType.name()))
        .orderBy(AIRBYTE_CONFIGS.CONFIG_TYPE, AIRBYTE_CONFIGS.CONFIG_ID)
        .fetch());
    return results.stream()
        .map(record -> Jsons.deserialize(record.get(AIRBYTE_CONFIGS.CONFIG_BLOB).data(), clazz))
        .collect(Collectors.toList());
  }

  @Override
  public <T> void writeConfig(AirbyteConfig configType, String configId, T config) throws IOException {
    LOGGER.info("Upserting {} record {}", configType, configId);

    database.transaction(ctx -> {
      boolean isExistingConfig = ctx.fetchExists(select()
          .from(AIRBYTE_CONFIGS)
          .where(AIRBYTE_CONFIGS.CONFIG_TYPE.eq(configType.name()), AIRBYTE_CONFIGS.CONFIG_ID.eq(configId)));

      OffsetDateTime timestamp = OffsetDateTime.now();

      if (isExistingConfig) {
        int updateCount = ctx.update(AIRBYTE_CONFIGS)
            .set(AIRBYTE_CONFIGS.CONFIG_BLOB, JSONB.valueOf(Jsons.serialize(config)))
            .set(AIRBYTE_CONFIGS.UPDATED_AT, timestamp)
            .where(AIRBYTE_CONFIGS.CONFIG_TYPE.eq(configType.name()), AIRBYTE_CONFIGS.CONFIG_ID.eq(configId))
            .execute();
        if (updateCount != 0 && updateCount != 1) {
          LOGGER.warn("{} config {} has been updated; updated record count: {}", configType, configId, updateCount);
        }

        return null;
      }

      int insertionCount = ctx.insertInto(AIRBYTE_CONFIGS)
          .set(AIRBYTE_CONFIGS.CONFIG_ID, configId)
          .set(AIRBYTE_CONFIGS.CONFIG_TYPE, configType.name())
          .set(AIRBYTE_CONFIGS.CONFIG_BLOB, JSONB.valueOf(Jsons.serialize(config)))
          .set(AIRBYTE_CONFIGS.CREATED_AT, timestamp)
          .set(AIRBYTE_CONFIGS.UPDATED_AT, timestamp)
          .execute();
      if (insertionCount != 1) {
        LOGGER.warn("{} config {} has been inserted; insertion record count: {}", configType, configId, insertionCount);
      }

      return null;
    });
  }

  @Override
  public void deleteConfig(AirbyteConfig configType, String configId) throws IOException {
    database.transaction(ctx -> {
      boolean isExistingConfig = ctx.fetchExists(select()
          .from(AIRBYTE_CONFIGS)
          .where(AIRBYTE_CONFIGS.CONFIG_TYPE.eq(configType.name()), AIRBYTE_CONFIGS.CONFIG_ID.eq(configId)));

      if (isExistingConfig) {
        ctx.deleteFrom(AIRBYTE_CONFIGS)
            .where(AIRBYTE_CONFIGS.CONFIG_TYPE.eq(configType.name()), AIRBYTE_CONFIGS.CONFIG_ID.eq(configId))
            .execute();
        return null;
      }
      return null;
    });
  }

  @Override
  public <T> void replaceAllConfigs(Map<AirbyteConfig, Stream<T>> configs, boolean dryRun) throws IOException {
    if (dryRun) {
      return;
    }

    LOGGER.info("Replacing all configs");

    OffsetDateTime timestamp = OffsetDateTime.now();
    int insertionCount = database.transaction(ctx -> {
      ctx.truncate(AIRBYTE_CONFIGS).restartIdentity().execute();

      return configs.entrySet().stream().map(entry -> {
        AirbyteConfig configType = entry.getKey();
        return entry.getValue()
            .map(configObject -> insertConfigRecord(ctx, timestamp, configType.name(), Jsons.jsonNode(configObject), configType.getIdFieldName()))
            .reduce(0, Integer::sum);
      }).reduce(0, Integer::sum);
    });

    LOGGER.info("Config database is reset with {} records", insertionCount);
  }

  @Override
  public Map<String, Stream<JsonNode>> dumpConfigs() throws IOException {
    LOGGER.info("Exporting all configs...");

    Map<String, Result<Record>> results = database.query(ctx -> ctx.select(asterisk())
        .from(AIRBYTE_CONFIGS)
        .orderBy(AIRBYTE_CONFIGS.CONFIG_TYPE, AIRBYTE_CONFIGS.CONFIG_ID)
        .fetchGroups(AIRBYTE_CONFIGS.CONFIG_TYPE));
    return results.entrySet().stream().collect(Collectors.toMap(
        Entry::getKey,
        e -> e.getValue().stream().map(r -> Jsons.deserialize(r.get(AIRBYTE_CONFIGS.CONFIG_BLOB).data()))));
  }

  /**
   * @return the number of inserted records for convenience, which is always 1.
   */
  private int insertConfigRecord(DSLContext ctx, OffsetDateTime timestamp, String configType, JsonNode configJson, String idFieldName) {
    String configId = idFieldName == null
        ? UUID.randomUUID().toString()
        : configJson.get(idFieldName).asText();
    LOGGER.info("Inserting {} record {}", configType, configId);

    ctx.insertInto(AIRBYTE_CONFIGS)
        .set(AIRBYTE_CONFIGS.CONFIG_ID, configId)
        .set(AIRBYTE_CONFIGS.CONFIG_TYPE, configType)
        .set(AIRBYTE_CONFIGS.CONFIG_BLOB, JSONB.valueOf(Jsons.serialize(configJson)))
        .set(AIRBYTE_CONFIGS.CREATED_AT, timestamp)
        .set(AIRBYTE_CONFIGS.UPDATED_AT, timestamp)
        .execute();
    return 1;
  }

  /**
   * @return the number of updated records.
   */
  private int updateConfigRecord(DSLContext ctx, OffsetDateTime timestamp, String configType, JsonNode configJson, String configId) {
    LOGGER.info("Updating {} record {}", configType, configId);

    return ctx.update(AIRBYTE_CONFIGS)
        .set(AIRBYTE_CONFIGS.CONFIG_BLOB, JSONB.valueOf(Jsons.serialize(configJson)))
        .set(AIRBYTE_CONFIGS.UPDATED_AT, timestamp)
        .where(AIRBYTE_CONFIGS.CONFIG_TYPE.eq(configType), AIRBYTE_CONFIGS.CONFIG_ID.eq(configId))
        .execute();
  }

  private void copyConfigsFromSeed(DSLContext ctx, ConfigPersistence seedConfigPersistence) throws SQLException {
    LOGGER.info("Loading data to config database...");

    Map<String, Stream<JsonNode>> seedConfigs;
    try {
      seedConfigs = seedConfigPersistence.dumpConfigs();
    } catch (IOException e) {
      throw new SQLException(e);
    }

    OffsetDateTime timestamp = OffsetDateTime.now();
    int insertionCount = seedConfigs.entrySet().stream().map(entry -> {
      String configType = entry.getKey();
      return entry.getValue().map(configJson -> {
        String idFieldName = ConfigSchemaMigrationSupport.CONFIG_SCHEMA_ID_FIELD_NAMES.get(configType);
        return insertConfigRecord(ctx, timestamp, configType, configJson, idFieldName);
      }).reduce(0, Integer::sum);
    }).reduce(0, Integer::sum);

    LOGGER.info("Config database data loading completed with {} records", insertionCount);
  }

  private void updateConfigsFromSeed(DSLContext ctx, ConfigPersistence seedConfigPersistence) throws SQLException {
    LOGGER.info("Config database has been initialized; updating connector definitions from the seed if necessary...");

    try {
      Set<String> usedConnectorRepos = getUsedConnectorRepos(ctx);
      Map<String, Record2<String, String>> currentRepoToIdAndVersions = getCurrentRepoToIdAndVersions(ctx);

      OffsetDateTime timestamp = OffsetDateTime.now();
      int insertionCount = 0;
      int updatedCount = 0;

      List<StandardSourceDefinition> latestSources = seedConfigPersistence.listConfigs(
          ConfigSchema.STANDARD_SOURCE_DEFINITION, StandardSourceDefinition.class);
      Record2<Integer, Integer> sourceUpdate = updateConnectorDefinitions(ctx, timestamp, ConfigSchema.STANDARD_SOURCE_DEFINITION,
          latestSources, usedConnectorRepos, currentRepoToIdAndVersions);
      insertionCount += sourceUpdate.value1();
      updatedCount += sourceUpdate.value2();

      List<StandardDestinationDefinition> latestDestinations = seedConfigPersistence.listConfigs(
          ConfigSchema.STANDARD_DESTINATION_DEFINITION, StandardDestinationDefinition.class);
      Record2<Integer, Integer> destinationUpdate = updateConnectorDefinitions(ctx, timestamp, ConfigSchema.STANDARD_DESTINATION_DEFINITION,
          latestDestinations, usedConnectorRepos, currentRepoToIdAndVersions);
      insertionCount += destinationUpdate.value1();
      updatedCount += destinationUpdate.value2();

      LOGGER.info("Connector definitions have been updated ({} new connectors, and {} updates)", insertionCount, updatedCount);
    } catch (IOException | JsonValidationException e) {
      throw new SQLException(e);
    }
  }

  private <T> Record2<Integer, Integer> updateConnectorDefinitions(DSLContext ctx,
                                                                   OffsetDateTime timestamp,
                                                                   AirbyteConfig configType,
                                                                   List<T> latestDefinitions,
                                                                   Set<String> usedConnectorRepos,
                                                                   Map<String, Record2<String, String>> currentRepoToIdAndVersions) {
    int insertionCount = 0;
    int updatedCount = 0;
    for (T latestDefinition : latestDefinitions) {
      JsonNode configJson = Jsons.jsonNode(latestDefinition);
      String repository = configJson.get("dockerRepository").asText();
      if (usedConnectorRepos.contains(repository)) {
        continue;
      }

      if (!currentRepoToIdAndVersions.containsKey(repository)) {
        insertConfigRecord(ctx, timestamp, configType.name(), configJson, configType.getIdFieldName());
        continue;
      }

      Record2<String, String> currentSourceIdAndVersion = currentRepoToIdAndVersions.get(repository);
      String latestVersion = configJson.get("dockerImageTag").asText();
      if (!latestVersion.equals(currentSourceIdAndVersion.value2())) {
        updatedCount += updateConfigRecord(ctx, timestamp, configType.name(), configJson, currentSourceIdAndVersion.value1());
      }
    }
    return ctx
        .newRecord(field("Insertion", SQLDataType.INTEGER), field("Update Count", SQLDataType.INTEGER))
        .values(insertionCount, updatedCount);
  }

  /**
   * @return a map from docker repository to config id and versions
   */
  private Map<String, Record2<String, String>> getCurrentRepoToIdAndVersions(DSLContext ctx) {
    Field<String> repoField = field("config_blob -> 'dockerRepository'", SQLDataType.VARCHAR).as("repository");
    Field<String> versionField = field("config_blob -> 'dockerImageTag'", SQLDataType.VARCHAR).as("version");
    return ctx.select(AIRBYTE_CONFIGS.CONFIG_ID, repoField, versionField)
        .from(AIRBYTE_CONFIGS)
        .where(AIRBYTE_CONFIGS.CONFIG_TYPE.in(ConfigSchema.STANDARD_SOURCE_DEFINITION.name(), ConfigSchema.STANDARD_DESTINATION_DEFINITION.name()))
        .fetch().stream()
        .collect(Collectors.toMap(
            row -> row.getValue(repoField),
            row -> ctx.newRecord(row.field(AIRBYTE_CONFIGS.CONFIG_ID), row.field(versionField))));
  }

  private Set<String> getUsedConnectorRepos(DSLContext ctx) {
    Field<String> sourceIdField = field("config_blob -> 'sourceId'", SQLDataType.VARCHAR).as("sourceId");
    Field<String> destinationIdField = field("config_blob -> 'destinationId'", SQLDataType.VARCHAR).as("destinationId");
    return ctx
        .select(sourceIdField, destinationIdField)
        .from(AIRBYTE_CONFIGS)
        .where(AIRBYTE_CONFIGS.CONFIG_TYPE.eq(ConfigSchema.STANDARD_SYNC.name()))
        .fetch().stream()
        .flatMap(row -> Stream.of(row.getValue(sourceIdField), row.getValue(destinationIdField)))
        .collect(Collectors.toSet());
  }

}
