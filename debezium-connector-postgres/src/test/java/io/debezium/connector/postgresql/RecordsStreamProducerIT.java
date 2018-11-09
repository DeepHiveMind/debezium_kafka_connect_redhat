/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.debezium.connector.postgresql;

import static io.debezium.connector.postgresql.TestHelper.PK_FIELD;
import static io.debezium.connector.postgresql.TestHelper.topicName;
import static junit.framework.TestCase.assertEquals;
import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertFalse;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.data.Envelope;
import io.debezium.data.VariableScaleDecimal;
import io.debezium.data.VerifyRecord;
import io.debezium.doc.FixFor;
import io.debezium.heartbeat.Heartbeat;
import io.debezium.junit.ConditionalFail;
import io.debezium.junit.ShouldFailWhen;
import io.debezium.relational.Table;
import io.debezium.relational.TableId;
import io.debezium.schema.TopicSelector;

/**
 * Integration test for the {@link RecordsStreamProducer} class. This also tests indirectly the PG plugin functionality for
 * different use cases.
 *
 * @author Horia Chiorean (hchiorea@redhat.com)
 */
public class RecordsStreamProducerIT extends AbstractRecordsProducerTest {

    private RecordsStreamProducer recordsProducer;
    private TestConsumer consumer;
    private final Consumer<Throwable> blackHole = t -> {};

    @Rule
    public TestRule conditionalFail = new ConditionalFail();

    @Before
    public void before() throws Exception {
        TestHelper.dropAllSchemas();
        TestHelper.executeDDL("init_postgis.ddl");
        String statements =
                "CREATE SCHEMA IF NOT EXISTS public;" +
                "DROP TABLE IF EXISTS test_table;" +
                "CREATE TABLE test_table (pk SERIAL, text TEXT, PRIMARY KEY(pk));" +
                "CREATE TABLE table_with_interval (id SERIAL PRIMARY KEY, title VARCHAR(512) NOT NULL, time_limit INTERVAL DEFAULT '60 days'::INTERVAL NOT NULL);" +
                "INSERT INTO test_table(text) VALUES ('insert');";
        TestHelper.execute(statements);
        PostgresConnectorConfig config = new PostgresConnectorConfig(TestHelper.defaultConfig()
                .with(PostgresConnectorConfig.INCLUDE_UNKNOWN_DATATYPES, false)
                .with(PostgresConnectorConfig.SCHEMA_BLACKLIST, "postgis")
                .build());
        setupRecordsProducer(config);
    }

    @After
    public void after() throws Exception {
        if (recordsProducer != null) {
            recordsProducer.stop();
        }
    }

    @Test
    public void shouldReceiveChangesForInsertsWithDifferentDataTypes() throws Exception {
        TestHelper.executeDDL("postgres_create_tables.ddl");

        consumer = testConsumer(1);
        recordsProducer.start(consumer, blackHole);

        //numerical types
        assertInsert(INSERT_NUMERIC_TYPES_STMT, schemasAndValuesForNumericType());

        //numerical decimal types
        consumer.expects(1);
        assertInsert(INSERT_NUMERIC_DECIMAL_TYPES_STMT_NO_NAN, schemasAndValuesForBigDecimalEncodedNumericTypes());

        // string types
        consumer.expects(1);
        assertInsert(INSERT_STRING_TYPES_STMT, schemasAndValuesForStringTypes());

        // monetary types
        consumer.expects(1);
        assertInsert(INSERT_CASH_TYPES_STMT,  schemaAndValuesForMoneyTypes());

        // bits and bytes
        consumer.expects(1);
        assertInsert(INSERT_BIN_TYPES_STMT, schemaAndValuesForBinTypes());

        //date and time
        consumer.expects(1);
        assertInsert(INSERT_DATE_TIME_TYPES_STMT, schemaAndValuesForDateTimeTypes());

        // text
        consumer.expects(1);
        assertInsert(INSERT_TEXT_TYPES_STMT, schemasAndValuesForTextTypes());

        // geom types
        consumer.expects(1);
        assertInsert(INSERT_GEOM_TYPES_STMT, schemaAndValuesForGeomTypes());

        // timezone range types
        consumer.expects(1);
        assertInsert(INSERT_TSTZRANGE_TYPES_STMT, schemaAndValuesForTstzRangeTypes());
    }

    @Test
    public void shouldReceiveChangesForInsertsCustomTypes() throws Exception {
        PostgresConnectorConfig config = new PostgresConnectorConfig(TestHelper.defaultConfig()
                .with(PostgresConnectorConfig.INCLUDE_UNKNOWN_DATATYPES, true)
                .with(PostgresConnectorConfig.SCHEMA_BLACKLIST, "postgis")
                .build());
        setupRecordsProducer(config);
        TestHelper.executeDDL("postgres_create_tables.ddl");

        consumer = testConsumer(1);
        recordsProducer.start(consumer, blackHole);

        // custom types + null value
        assertInsert(INSERT_CUSTOM_TYPES_STMT, schemasAndValuesForCustomTypes());

    }

    @Test(timeout = 30000)
    public void shouldReceiveChangesForInsertsWithPostgisTypes() throws Exception {
        TestHelper.executeDDL("postgis_create_tables.ddl");
        consumer = testConsumer(1, "public"); // spatial_ref_sys produces a tonne of records in the postgis schema
        consumer.setIgnoreExtraRecords(true);
        recordsProducer.start(consumer, blackHole);

        // need to wait for all the spatial_ref_sys to flow through and be ignored.
        // this exceeds the normal 2s timeout.
        TestHelper.execute("INSERT INTO public.dummy_table DEFAULT VALUES;");
        consumer.await(TestHelper.waitTimeForRecords() * 10, TimeUnit.SECONDS);
        while (true) {
            if (!consumer.isEmpty()) {
                SourceRecord record = consumer.remove();
                if (record.topic().endsWith(".public.dummy_table")) {
                    break;
                }
            }
        }

        // now do it for actual testing
        // postgis types
        consumer.expects(1);
        assertInsert(INSERT_POSTGIS_TYPES_STMT, schemaAndValuesForPostgisTypes());
    }

    @Test(timeout = 30000)
    public void shouldReceiveChangesForInsertsWithPostgisArrayTypes() throws Exception {
        TestHelper.executeDDL("postgis_create_tables.ddl");
        consumer = testConsumer(1, "public"); // spatial_ref_sys produces a tonne of records in the postgis schema
        consumer.setIgnoreExtraRecords(true);
        recordsProducer.start(consumer, blackHole);

        // need to wait for all the spatial_ref_sys to flow through and be ignored.
        // this exceeds the normal 2s timeout.
        TestHelper.execute("INSERT INTO public.dummy_table DEFAULT VALUES;");
        consumer.await(TestHelper.waitTimeForRecords() * 10, TimeUnit.SECONDS);
        while (true) {
            if (!consumer.isEmpty()) {
                SourceRecord record = consumer.remove();
                if (record.topic().endsWith(".public.dummy_table")) {
                    break;
                }
            }
        }

        // now do it for actual testing
        // postgis types
        consumer.expects(1);
        assertInsert(INSERT_POSTGIS_ARRAY_TYPES_STMT, schemaAndValuesForPostgisArrayTypes());
    }

    @Test
    @ShouldFailWhen(DecoderDifferences.AreQuotedIdentifiersUnsupported.class)
    // TODO DBZ-493
    public void shouldReceiveChangesForInsertsWithQuotedNames() throws Exception {
        TestHelper.executeDDL("postgres_create_tables.ddl");

        consumer = testConsumer(1);
        recordsProducer.start(consumer, blackHole);

        // Quoted column name
        assertInsert(INSERT_QUOTED_TYPES_STMT, schemasAndValuesForQuotedTypes());
    }

    @Test
    public void shouldReceiveChangesForInsertsWithArrayTypes() throws Exception {
        TestHelper.executeDDL("postgres_create_tables.ddl");

        consumer = testConsumer(1);
        recordsProducer.start(consumer, blackHole);

        assertInsert(INSERT_ARRAY_TYPES_STMT, schemasAndValuesForArrayTypes());
    }

    @Test
    @FixFor("DBZ-478")
    public void shouldReceiveChangesForNullInsertsWithArrayTypes() throws Exception {
        TestHelper.executeDDL("postgres_create_tables.ddl");

        consumer = testConsumer(1);
        recordsProducer.start(consumer, blackHole);

        assertInsert(INSERT_ARRAY_TYPES_WITH_NULL_VALUES_STMT, schemasAndValuesForArrayTypesWithNullValues());
    }

    @Test
    public void shouldReceiveChangesForNewTable() throws Exception {
        String statement = "CREATE SCHEMA s1;" +
                           "CREATE TABLE s1.a (pk SERIAL, aa integer, PRIMARY KEY(pk));" +
                           "INSERT INTO s1.a (aa) VALUES (11);";
        consumer = testConsumer(1);
        recordsProducer.start(consumer, blackHole);
        executeAndWait(statement);
        assertRecordInserted("s1.a", PK_FIELD, 1);
    }

    @Test
    public void shouldReceiveChangesForRenamedTable() throws Exception {
        String statement = "DROP TABLE IF EXISTS renamed_test_table;" +
                           "ALTER TABLE test_table RENAME TO renamed_test_table;" +
                           "INSERT INTO renamed_test_table (text) VALUES ('new');";
        consumer = testConsumer(1);
        recordsProducer.start(consumer, blackHole);
        executeAndWait(statement);
        assertRecordInserted("public.renamed_test_table", PK_FIELD, 2);
    }

    @Test
    public void shouldReceiveChangesForUpdates() throws Exception {
        consumer = testConsumer(1);
        recordsProducer.start(consumer, blackHole);
        executeAndWait("UPDATE test_table set text='update' WHERE pk=1");

        // the update record should be the last record
        SourceRecord updatedRecord = consumer.remove();
        String topicName = topicName("public.test_table");
        assertEquals(topicName, updatedRecord.topic());
        VerifyRecord.isValidUpdate(updatedRecord, PK_FIELD, 1);

        // default replica identity only fires previous values for PK changes
        List<SchemaAndValueField> expectedAfter = Collections.singletonList(
                new SchemaAndValueField("text", SchemaBuilder.OPTIONAL_STRING_SCHEMA, "update"));
        assertRecordSchemaAndValues(expectedAfter, updatedRecord, Envelope.FieldName.AFTER);

        // alter the table and set its replica identity to full the issue another update
        consumer.expects(1);
        TestHelper.execute("ALTER TABLE test_table REPLICA IDENTITY FULL");
        executeAndWait("UPDATE test_table set text='update2' WHERE pk=1");

        updatedRecord = consumer.remove();
        assertEquals(topicName, updatedRecord.topic());
        VerifyRecord.isValidUpdate(updatedRecord, PK_FIELD, 1);

        // now we should get both old and new values
        List<SchemaAndValueField> expectedBefore = Collections.singletonList(new SchemaAndValueField("text", SchemaBuilder.OPTIONAL_STRING_SCHEMA,
                                                                                               "update"));
        assertRecordSchemaAndValues(expectedBefore, updatedRecord, Envelope.FieldName.BEFORE);

        expectedAfter = Collections.singletonList(new SchemaAndValueField("text", SchemaBuilder.OPTIONAL_STRING_SCHEMA, "update2"));
        assertRecordSchemaAndValues(expectedAfter, updatedRecord, Envelope.FieldName.AFTER);
    }

    @Test
    public void shouldReceiveChangesForUpdatesWithColumnChanges() throws Exception {
        // add a new column
        String statements = "ALTER TABLE test_table ADD COLUMN uvc VARCHAR(2);" +
                            "ALTER TABLE test_table REPLICA IDENTITY FULL;" +
                            "UPDATE test_table SET uvc ='aa' WHERE pk = 1;";

        consumer = testConsumer(1);
        recordsProducer.start(consumer, blackHole);
        executeAndWait(statements);

        // the update should be the last record
        SourceRecord updatedRecord = consumer.remove();
        String topicName = topicName("public.test_table");
        assertEquals(topicName, updatedRecord.topic());
        VerifyRecord.isValidUpdate(updatedRecord, PK_FIELD, 1);

        // now check we got the updated value (the old value should be null, the new one whatever we set)
        List<SchemaAndValueField> expectedBefore = Collections.singletonList(new SchemaAndValueField("uvc", null, null));
        assertRecordSchemaAndValues(expectedBefore, updatedRecord, Envelope.FieldName.BEFORE);

        List<SchemaAndValueField> expectedAfter = Collections.singletonList(new SchemaAndValueField("uvc", SchemaBuilder.OPTIONAL_STRING_SCHEMA,
                                                                                           "aa"));
        assertRecordSchemaAndValues(expectedAfter, updatedRecord, Envelope.FieldName.AFTER);

        // rename a column
        statements = "ALTER TABLE test_table RENAME COLUMN uvc to xvc;" +
                     "UPDATE test_table SET xvc ='bb' WHERE pk = 1;";

        consumer.expects(1);
        executeAndWait(statements);

        updatedRecord = consumer.remove();
        VerifyRecord.isValidUpdate(updatedRecord, PK_FIELD, 1);

        // now check we got the updated value (the old value should be null, the new one whatever we set)
        expectedBefore = Collections.singletonList(new SchemaAndValueField("xvc", SchemaBuilder.OPTIONAL_STRING_SCHEMA, "aa"));
        assertRecordSchemaAndValues(expectedBefore, updatedRecord, Envelope.FieldName.BEFORE);

        expectedAfter = Collections.singletonList(new SchemaAndValueField("xvc", SchemaBuilder.OPTIONAL_STRING_SCHEMA, "bb"));
        assertRecordSchemaAndValues(expectedAfter, updatedRecord, Envelope.FieldName.AFTER);

        // drop a column
        statements = "ALTER TABLE test_table DROP COLUMN xvc;" +
                     "UPDATE test_table SET text ='update' WHERE pk = 1;";

        consumer.expects(1);
        executeAndWait(statements);
        updatedRecord = consumer.remove();
        VerifyRecord.isValidUpdate(updatedRecord, PK_FIELD, 1);

        // change a column type
        statements = "ALTER TABLE test_table ADD COLUMN modtype INTEGER;" +
                "INSERT INTO test_table (pk,modtype) VALUES (2,1);";

        consumer.expects(1);
        executeAndWait(statements);
        updatedRecord = consumer.remove();

        VerifyRecord.isValidInsert(updatedRecord, PK_FIELD, 2);
        assertRecordSchemaAndValues(
                Collections.singletonList(new SchemaAndValueField("modtype", SchemaBuilder.OPTIONAL_INT32_SCHEMA, 1)), updatedRecord, Envelope.FieldName.AFTER);

        statements = "ALTER TABLE test_table ALTER COLUMN modtype TYPE SMALLINT;"
                + "UPDATE test_table SET modtype = 2 WHERE pk = 2;";

        consumer.expects(1);
        executeAndWait(statements);
        updatedRecord = consumer.remove();
        VerifyRecord.isValidUpdate(updatedRecord, PK_FIELD, 2);
        assertRecordSchemaAndValues(
                Collections.singletonList(new SchemaAndValueField("modtype", SchemaBuilder.OPTIONAL_INT16_SCHEMA, (short)1)), updatedRecord, Envelope.FieldName.BEFORE);
        assertRecordSchemaAndValues(
                Collections.singletonList(new SchemaAndValueField("modtype", SchemaBuilder.OPTIONAL_INT16_SCHEMA, (short)2)), updatedRecord, Envelope.FieldName.AFTER);
    }

    @Test
    public void shouldReceiveChangesForUpdatesWithPKChanges() throws Exception {
        consumer = testConsumer(3);
        recordsProducer.start(consumer, blackHole);
        executeAndWait("UPDATE test_table SET text = 'update', pk = 2");

        String topicName = topicName("public.test_table");

        // first should be a delete of the old pk
        SourceRecord deleteRecord = consumer.remove();
        assertEquals(topicName, deleteRecord.topic());
        VerifyRecord.isValidDelete(deleteRecord, PK_FIELD, 1);

        // followed by a tombstone of the old pk
        SourceRecord tombstoneRecord = consumer.remove();
        assertEquals(topicName, tombstoneRecord.topic());
        VerifyRecord.isValidTombstone(tombstoneRecord, PK_FIELD, 1);

        // and finally insert of the new value
        SourceRecord insertRecord = consumer.remove();
        assertEquals(topicName, insertRecord.topic());
        VerifyRecord.isValidInsert(insertRecord, PK_FIELD, 2);
    }

    @Test
    @FixFor("DBZ-582")
    public void shouldReceiveChangesForUpdatesWithPKChangesWithoutTombstone() throws Exception {
        PostgresConnectorConfig config = new PostgresConnectorConfig(TestHelper.defaultConfig()
                .with(PostgresConnectorConfig.INCLUDE_UNKNOWN_DATATYPES, true)
                .with(CommonConnectorConfig.TOMBSTONES_ON_DELETE, false)
                .build()
        );
        setupRecordsProducer(config);
        consumer = testConsumer(2);
        recordsProducer.start(consumer, blackHole);

        executeAndWait("UPDATE test_table SET text = 'update', pk = 2");

        String topicName = topicName("public.test_table");

        // first should be a delete of the old pk
        SourceRecord deleteRecord = consumer.remove();
        assertEquals(topicName, deleteRecord.topic());
        VerifyRecord.isValidDelete(deleteRecord, PK_FIELD, 1);

        // followed by insert of the new value
        SourceRecord insertRecord = consumer.remove();
        assertEquals(topicName, insertRecord.topic());
        VerifyRecord.isValidInsert(insertRecord, PK_FIELD, 2);
    }

    @Test
    public void shouldReceiveChangesForDefaultValues() throws Exception {
        String statements = "ALTER TABLE test_table REPLICA IDENTITY FULL;" +
                            "ALTER TABLE test_table ADD COLUMN default_column TEXT DEFAULT 'default';" +
                            "INSERT INTO test_table (text) VALUES ('update');";
        consumer = testConsumer(1);
        recordsProducer.start(consumer, blackHole);
        executeAndWait(statements);

        SourceRecord insertRecord = consumer.remove();
        assertEquals(topicName("public.test_table"), insertRecord.topic());
        VerifyRecord.isValidInsert(insertRecord, PK_FIELD, 2);
        List<SchemaAndValueField> expectedSchemaAndValues = Arrays.asList(
                new SchemaAndValueField("text", SchemaBuilder.OPTIONAL_STRING_SCHEMA, "update"),
                new SchemaAndValueField("default_column", SchemaBuilder.OPTIONAL_STRING_SCHEMA ,"default"));
        assertRecordSchemaAndValues(expectedSchemaAndValues, insertRecord, Envelope.FieldName.AFTER);
    }

    @Test
    public void shouldReceiveChangesForTypeConstraints() throws Exception {
        // add a new column
        String statements = "ALTER TABLE test_table ADD COLUMN num_val NUMERIC(5,2);" +
                            "ALTER TABLE test_table REPLICA IDENTITY FULL;" +
                            "UPDATE test_table SET num_val = 123.45 WHERE pk = 1;";

        consumer = testConsumer(1);
        recordsProducer.start(consumer, blackHole);
        executeAndWait(statements);

        // the update should be the last record
        SourceRecord updatedRecord = consumer.remove();
        String topicName = topicName("public.test_table");
        assertEquals(topicName, updatedRecord.topic());
        VerifyRecord.isValidUpdate(updatedRecord, PK_FIELD, 1);

        // now check we got the updated value (the old value should be null, the new one whatever we set)
        List<SchemaAndValueField> expectedBefore = Collections.singletonList(new SchemaAndValueField("num_val", null, null));
        assertRecordSchemaAndValues(expectedBefore, updatedRecord, Envelope.FieldName.BEFORE);

        List<SchemaAndValueField> expectedAfter = Collections.singletonList(new SchemaAndValueField("num_val", Decimal.builder(2).parameter(TestHelper.PRECISION_PARAMETER_KEY, "5").optional().build(), new BigDecimal("123.45")));
        assertRecordSchemaAndValues(expectedAfter, updatedRecord, Envelope.FieldName.AFTER);

        // change a constraint
        statements = "ALTER TABLE test_table ALTER COLUMN num_val TYPE NUMERIC(6,1);" +
                "INSERT INTO test_table (pk,num_val) VALUES (2,123.41);";

        consumer.expects(1);
        executeAndWait(statements);
        updatedRecord = consumer.remove();

        VerifyRecord.isValidInsert(updatedRecord, PK_FIELD, 2);
        assertRecordSchemaAndValues(
                Collections.singletonList(new SchemaAndValueField("num_val", Decimal.builder(1).parameter(TestHelper.PRECISION_PARAMETER_KEY, "6").optional().build(), new BigDecimal("123.4"))), updatedRecord, Envelope.FieldName.AFTER);

        statements = "ALTER TABLE test_table ALTER COLUMN num_val TYPE NUMERIC;" +
                "INSERT INTO test_table (pk,num_val) VALUES (3,123.4567);";

        consumer.expects(1);
        executeAndWait(statements);
        updatedRecord = consumer.remove();

        final Struct dvs = new Struct(VariableScaleDecimal.schema());
        dvs.put("scale", 4).put("value", new BigDecimal("123.4567").unscaledValue().toByteArray());
        VerifyRecord.isValidInsert(updatedRecord, PK_FIELD, 3);
        assertRecordSchemaAndValues(
                Collections.singletonList(new SchemaAndValueField("num_val", VariableScaleDecimal.builder().optional().build(), dvs)), updatedRecord, Envelope.FieldName.AFTER);

        statements = "ALTER TABLE test_table ALTER COLUMN num_val TYPE DECIMAL(12,4);" +
                "INSERT INTO test_table (pk,num_val) VALUES (4,2.48);";

        consumer.expects(1);
        executeAndWait(statements);
        updatedRecord = consumer.remove();

        VerifyRecord.isValidInsert(updatedRecord, PK_FIELD, 4);
        assertRecordSchemaAndValues(
                Collections.singletonList(new SchemaAndValueField("num_val", Decimal.builder(4).parameter(TestHelper.PRECISION_PARAMETER_KEY, "12").optional().build(), new BigDecimal("2.4800"))), updatedRecord, Envelope.FieldName.AFTER);

        statements = "ALTER TABLE test_table ALTER COLUMN num_val TYPE DECIMAL(12);" +
                "INSERT INTO test_table (pk,num_val) VALUES (5,1238);";

        consumer.expects(1);
        executeAndWait(statements);
        updatedRecord = consumer.remove();

        VerifyRecord.isValidInsert(updatedRecord, PK_FIELD, 5);
        assertRecordSchemaAndValues(
                Collections.singletonList(new SchemaAndValueField("num_val", Decimal.builder(0).parameter(TestHelper.PRECISION_PARAMETER_KEY, "12").optional().build(), new BigDecimal("1238"))), updatedRecord, Envelope.FieldName.AFTER);

        statements = "ALTER TABLE test_table ALTER COLUMN num_val TYPE DECIMAL;" +
                "INSERT INTO test_table (pk,num_val) VALUES (6,1225.1);";

        consumer.expects(1);
        executeAndWait(statements);
        updatedRecord = consumer.remove();

        final Struct dvs2 = new Struct(VariableScaleDecimal.schema());
        dvs2.put("scale", 1).put("value", new BigDecimal("1225.1").unscaledValue().toByteArray());
        VerifyRecord.isValidInsert(updatedRecord, PK_FIELD, 6);
        assertRecordSchemaAndValues(
                Collections.singletonList(new SchemaAndValueField("num_val", VariableScaleDecimal.builder().optional().build(), dvs2)), updatedRecord, Envelope.FieldName.AFTER);
}

    @Test
    public void shouldReceiveChangesForDeletes() throws Exception {
        // add a new entry and remove both
        String statements = "INSERT INTO test_table (text) VALUES ('insert2');" +
                            "DELETE FROM test_table WHERE pk > 0;";
        consumer = testConsumer(5);
        recordsProducer.start(consumer, blackHole);
        executeAndWait(statements);


        String topicPrefix = "public.test_table";
        String topicName = topicName(topicPrefix);
        assertRecordInserted(topicPrefix, PK_FIELD, 2);

        // first entry removed
        SourceRecord record = consumer.remove();
        assertEquals(topicName, record.topic());
        VerifyRecord.isValidDelete(record, PK_FIELD, 1);

        // followed by a tombstone
        record = consumer.remove();
        assertEquals(topicName, record.topic());
        VerifyRecord.isValidTombstone(record, PK_FIELD, 1);

        // second entry removed
        record = consumer.remove();
        assertEquals(topicName, record.topic());
        VerifyRecord.isValidDelete(record, PK_FIELD, 2);

        // followed by a tombstone
        record = consumer.remove();
        assertEquals(topicName, record.topic());
        VerifyRecord.isValidTombstone(record, PK_FIELD, 2);
    }

    @Test
    @FixFor("DBZ-582")
    public void shouldReceiveChangesForDeletesWithoutTombstone() throws Exception {
        PostgresConnectorConfig config = new PostgresConnectorConfig(TestHelper.defaultConfig()
                .with(PostgresConnectorConfig.INCLUDE_UNKNOWN_DATATYPES, true)
                .with(CommonConnectorConfig.TOMBSTONES_ON_DELETE, false)
                .build()
        );
        setupRecordsProducer(config);

        // add a new entry and remove both
        String statements = "INSERT INTO test_table (text) VALUES ('insert2');" +
                            "DELETE FROM test_table WHERE pk > 0;";
        consumer = testConsumer(3);
        recordsProducer.start(consumer, blackHole);
        executeAndWait(statements);


        String topicPrefix = "public.test_table";
        String topicName = topicName(topicPrefix);
        assertRecordInserted(topicPrefix, PK_FIELD, 2);

        // first entry removed
        SourceRecord record = consumer.remove();
        assertEquals(topicName, record.topic());
        VerifyRecord.isValidDelete(record, PK_FIELD, 1);

        // second entry removed
        record = consumer.remove();
        assertEquals(topicName, record.topic());
        VerifyRecord.isValidDelete(record, PK_FIELD, 2);
    }

    @Test
    public void shouldReceiveNumericTypeAsDouble() throws Exception {
        PostgresConnectorConfig config = new PostgresConnectorConfig(TestHelper.defaultConfig()
                .with(PostgresConnectorConfig.DECIMAL_HANDLING_MODE, PostgresConnectorConfig.DecimalHandlingMode.DOUBLE)
                .build());
        setupRecordsProducer(config);

        TestHelper.executeDDL("postgres_create_tables.ddl");

        consumer = testConsumer(1);
        recordsProducer.start(consumer, blackHole);

        assertInsert(INSERT_NUMERIC_DECIMAL_TYPES_STMT, schemasAndValuesForDoubleEncodedNumericTypes());
    }

    @Test
    @FixFor("DBZ-611")
    public void shouldReceiveNumericTypeAsString() throws Exception {
        PostgresConnectorConfig config = new PostgresConnectorConfig(TestHelper.defaultConfig()
                .with(PostgresConnectorConfig.DECIMAL_HANDLING_MODE, PostgresConnectorConfig.DecimalHandlingMode.STRING)
                .build());
        setupRecordsProducer(config);

        TestHelper.executeDDL("postgres_create_tables.ddl");

        consumer = testConsumer(1);
        recordsProducer.start(consumer, blackHole);

        assertInsert(INSERT_NUMERIC_DECIMAL_TYPES_STMT, schemasAndValuesForStringEncodedNumericTypes());
    }

    @Test
    @FixFor("DBZ-898")
    public void shouldReceiveHStoreTypeWithSingleValueAsMap() throws Exception {
        PostgresConnectorConfig config = new PostgresConnectorConfig(TestHelper.defaultConfig()
                .with(PostgresConnectorConfig.HSTORE_HANDLING_MODE,PostgresConnectorConfig.HStoreHandlingMode.MAP)
                .build());
        setupRecordsProducer(config);
        TestHelper.executeDDL("postgres_create_tables.ddl");
        consumer = testConsumer(1);
        recordsProducer.start(consumer, blackHole);

        assertInsert(INSERT_HSTORE_TYPE_STMT, schemaAndValueFieldForMapEncodedHStoreType());
    }

    @Test
    @FixFor("DBZ-898")
    public void shouldReceiveHStoreTypeWithMultipleValuesAsMap() throws Exception {
        PostgresConnectorConfig config = new PostgresConnectorConfig(TestHelper.defaultConfig()
                .with(PostgresConnectorConfig.HSTORE_HANDLING_MODE,PostgresConnectorConfig.HStoreHandlingMode.MAP)
                .build());
        setupRecordsProducer(config);
        TestHelper.executeDDL("postgres_create_tables.ddl");
        consumer = testConsumer(1);
        recordsProducer.start(consumer, blackHole);

        assertInsert(INSERT_HSTORE_TYPE_WITH_MULTIPLE_VALUES_STMT, schemaAndValueFieldForMapEncodedHStoreTypeWithMultipleValues());
    }

    @Test
    @FixFor("DBZ-898")
    public void shouldReceiveHStoreTypeWithNullValuesAsMap() throws Exception {
        PostgresConnectorConfig config = new PostgresConnectorConfig(TestHelper.defaultConfig()
                .with(PostgresConnectorConfig.HSTORE_HANDLING_MODE,PostgresConnectorConfig.HStoreHandlingMode.MAP)
                .build());
        setupRecordsProducer(config);
        TestHelper.executeDDL("postgres_create_tables.ddl");
        consumer = testConsumer(1);
        recordsProducer.start(consumer,blackHole);

        assertInsert(INSERT_HSTORE_TYPE_WITH_NULL_VALUES_STMT, schemaAndValueFieldForMapEncodedHStoreTypeWithNullValues());
    }

    @Test
    @FixFor("DBZ-898")
    public void shouldReceiveHStoreTypeWithSpecialCharactersInValuesAsMap() throws Exception {
        PostgresConnectorConfig config = new PostgresConnectorConfig(TestHelper.defaultConfig()
                .with(PostgresConnectorConfig.HSTORE_HANDLING_MODE,PostgresConnectorConfig.HStoreHandlingMode.MAP)
                .build());
        setupRecordsProducer(config);
        TestHelper.executeDDL("postgres_create_tables.ddl");
        consumer = testConsumer(1);
        recordsProducer.start(consumer, blackHole);

        assertInsert(INSERT_HSTORE_TYPE_WITH_SPECIAL_CHAR_STMT, schemaAndValueFieldForMapEncodedHStoreTypeWithSpecialCharacters());
    }

    @Test
    @FixFor("DBZ-898")
    public void shouldReceiveHStoreTypeAsJsonString() throws Exception {
        PostgresConnectorConfig config = new PostgresConnectorConfig(TestHelper.defaultConfig()
                .with(PostgresConnectorConfig.HSTORE_HANDLING_MODE,PostgresConnectorConfig.HStoreHandlingMode.JSON)
                .build());
        setupRecordsProducer(config);
        TestHelper.executeDDL("postgres_create_tables.ddl");
        consumer = testConsumer(1);
        recordsProducer.start(consumer, blackHole);

        assertInsert(INSERT_HSTORE_TYPE_STMT, schemaAndValueFieldForJsonEncodedHStoreType());
    }

    @Test
    @FixFor("DBZ-898")
    public void shouldReceiveHStoreTypeWithMultipleValuesAsJsonString() throws Exception {
        PostgresConnectorConfig config = new PostgresConnectorConfig(TestHelper.defaultConfig()
                .with(PostgresConnectorConfig.HSTORE_HANDLING_MODE,PostgresConnectorConfig.HStoreHandlingMode.JSON)
                .build());
        setupRecordsProducer(config);
        TestHelper.executeDDL("postgres_create_tables.ddl");
        consumer = testConsumer(1);
        recordsProducer.start(consumer, blackHole);

        assertInsert(INSERT_HSTORE_TYPE_WITH_MULTIPLE_VALUES_STMT, schemaAndValueFieldForJsonEncodedHStoreTypeWithMultipleValues());
    }

    @Test
    @FixFor("DBZ-898")
    public void shouldReceiveHStoreTypeWithSpecialValuesInJsonString() throws Exception {
        PostgresConnectorConfig config = new PostgresConnectorConfig(TestHelper.defaultConfig()
                .with(PostgresConnectorConfig.HSTORE_HANDLING_MODE,PostgresConnectorConfig.HStoreHandlingMode.JSON)
                .build());
        setupRecordsProducer(config);
        TestHelper.executeDDL("postgres_create_tables.ddl");
        consumer = testConsumer(1);
        recordsProducer.start(consumer, blackHole);

        assertInsert(INSERT_HSTORE_TYPE_WITH_SPECIAL_CHAR_STMT, schemaAndValueFieldForJsonEncodedHStoreTypeWithSpcialCharacters());
    }

    @Test
    @FixFor("DBZ-898")
    public void shouldReceiveHStoreTypeWithNullValuesAsJsonString() throws Exception {
        PostgresConnectorConfig config = new PostgresConnectorConfig(TestHelper.defaultConfig()
                .with(PostgresConnectorConfig.HSTORE_HANDLING_MODE,PostgresConnectorConfig.HStoreHandlingMode.JSON)
                .build());
        setupRecordsProducer(config);
        TestHelper.executeDDL("postgres_create_tables.ddl");
        consumer = testConsumer(1);
        recordsProducer.start(consumer, blackHole);

        assertInsert(INSERT_HSTORE_TYPE_WITH_NULL_VALUES_STMT, schemaAndValueFieldForJsonEncodedHStoreTypeWithNullValues());
    }

    @Test
    @FixFor("DBZ-259")
    public void shouldProcessIntervalDelete() throws Exception {
        final String statements =
                "INSERT INTO table_with_interval VALUES (default, 'Foo', default);" +
                "INSERT INTO table_with_interval VALUES (default, 'Bar', default);" +
                "DELETE FROM table_with_interval WHERE id = 1;";

        consumer = testConsumer(4);
        recordsProducer.start(consumer, blackHole);
        executeAndWait(statements);

        final String topicPrefix = "public.table_with_interval";
        final String topicName = topicName(topicPrefix);
        final String pk = "id";
        assertRecordInserted(topicPrefix, pk, 1);
        assertRecordInserted(topicPrefix, pk, 2);

        // first entry removed
        SourceRecord record = consumer.remove();
        assertEquals(topicName, record.topic());
        VerifyRecord.isValidDelete(record, pk, 1);

        // followed by a tombstone
        record = consumer.remove();
        assertEquals(topicName, record.topic());
        VerifyRecord.isValidTombstone(record, pk, 1);
    }

    @Test
    @FixFor("DBZ-501")
    public void shouldNotStartAfterStop() throws Exception {
        recordsProducer.stop();
        recordsProducer.start(consumer, blackHole);

        // Need to remove record created in @Before
        PostgresConnectorConfig config = new PostgresConnectorConfig(TestHelper.defaultConfig().with(PostgresConnectorConfig.INCLUDE_UNKNOWN_DATATYPES, true).build());
        setupRecordsProducer(config);

        consumer = testConsumer(1);
        recordsProducer.start(consumer, blackHole);
    }

    @Test
    @FixFor("DBZ-644")
    @Ignore
    // There are problems with test stability on Travis CI
    public void shouldPropagateSourceColumnTypeToSchemaParameter() throws Exception {
        PostgresConnectorConfig config = new PostgresConnectorConfig(TestHelper.defaultConfig()
                .with("column.propagate.source.type", ".*vc.*")
                .build());
        setupRecordsProducer(config);

        TestHelper.executeDDL("postgres_create_tables.ddl");

        consumer = testConsumer(1);
        recordsProducer.start(consumer, blackHole);

        assertInsert(INSERT_STRING_TYPES_STMT, schemasAndValuesForStringTypesWithSourceColumnTypeInfo());
    }

    @Test
    @FixFor("DBZ-800")
    public void shouldReceiveHeartbeatAlsoWhenChangingNonWhitelistedTable() throws Exception {
        // the low heartbeat interval should make sure that a heartbeat message is emitted after each change record
        // received from Postgres
        PostgresConnectorConfig config = new PostgresConnectorConfig(TestHelper.defaultConfig()
                .with(Heartbeat.HEARTBEAT_INTERVAL, "1")
                .with(PostgresConnectorConfig.TABLE_WHITELIST, "s1\\.b")
                .build());
        setupRecordsProducer(config);

        String statement = "CREATE SCHEMA s1;" +
                           "CREATE TABLE s1.a (pk SERIAL, aa integer, PRIMARY KEY(pk));" +
                           "CREATE TABLE s1.b (pk SERIAL, bb integer, PRIMARY KEY(pk));" +
                           "INSERT INTO s1.a (aa) VALUES (11);" +
                           "INSERT INTO s1.b (bb) VALUES (22);";

        // expecting two heartbeat records and one actual change record
        consumer = testConsumer(DecoderDifferences.singleHeartbeatPerTranasaction() ? 2 : 3);
        recordsProducer.start(consumer, blackHole);
        executeAndWait(statement);

        if (!DecoderDifferences.singleHeartbeatPerTranasaction()) {
            // expecting no change record for s1.a but a heartbeat
            assertHeartBeatRecordInserted();
        }

        // and then a change record for s1.b and a heartbeat
        assertRecordInserted("s1.b", PK_FIELD, 1);
        assertHeartBeatRecordInserted();

        assertThat(consumer.isEmpty()).isTrue();
    }

    @Test
    @FixFor("DBZ-911")
    public void shouldNotRefreshSchemaOnUnchangedToastedData() throws Exception {
        // the low heartbeat interval should make sure that a heartbeat message is emitted after each change record
        // received from Postgres
        PostgresConnectorConfig config = new PostgresConnectorConfig(TestHelper.defaultConfig()
                .with(PostgresConnectorConfig.SCHEMA_REFRESH_MODE, PostgresConnectorConfig.SchemaRefreshMode.COLUMNS_DIFF_EXCLUDE_UNCHANGED_TOAST)
                .build());
        setupRecordsProducer(config);

        String toastedValue = RandomStringUtils.randomAlphanumeric(10000);

        // inserting a toasted value should /always/ produce a correct record
        String statement = "ALTER TABLE test_table ADD COLUMN not_toast integer; INSERT INTO test_table (not_toast, text) values (10, '" + toastedValue + "')";
        consumer = testConsumer(1);
        recordsProducer.start(consumer, blackHole);
        executeAndWait(statement);

        SourceRecord record = consumer.remove();

        // after record should contain the toasted value
        List<SchemaAndValueField> expectedAfter = Arrays.asList(
                new SchemaAndValueField("not_toast", SchemaBuilder.OPTIONAL_INT32_SCHEMA, 10),
                new SchemaAndValueField("text", SchemaBuilder.OPTIONAL_STRING_SCHEMA, toastedValue)
        );
        assertRecordSchemaAndValues(expectedAfter, record, Envelope.FieldName.AFTER);

        // now we remove the toast column and update the not_toast column to see that our unchanged toast data
        // does not trigger a table schema refresh. the after schema should look the same as before.
        statement = "ALTER TABLE test_table DROP COLUMN text; update test_table set not_toast = 5 where not_toast = 10";

        consumer.expects(1);
        executeAndWait(statement);
        Table tbl = recordsProducer.schema().tableFor(TableId.parse("public.test_table"));
        assertEquals(Arrays.asList("pk", "text", "not_toast"), tbl.columnNames());
    }

    private void assertHeartBeatRecordInserted() {
        assertFalse("records not generated", consumer.isEmpty());

        SourceRecord heartbeat = consumer.remove();
        assertEquals("__debezium-heartbeat." + TestHelper.TEST_SERVER, heartbeat.topic());

        Struct key = (Struct) heartbeat.key();
        assertThat(key.get("serverName")).isEqualTo(TestHelper.TEST_SERVER);
    }

    private void setupRecordsProducer(PostgresConnectorConfig config) {
        if (recordsProducer != null) {
            recordsProducer.stop();
        }

        TopicSelector<TableId> selector = PostgresTopicSelector.create(config);

        PostgresTaskContext context = new PostgresTaskContext(
                config,
                TestHelper.getSchema(config),
                selector
        );
        recordsProducer = new RecordsStreamProducer(context, new SourceInfo(config.getLogicalName(), config.databaseName()));
    }

    private void assertInsert(String statement, List<SchemaAndValueField> expectedSchemaAndValuesByColumn) {
        assertInsert(statement, 1, expectedSchemaAndValuesByColumn);
    }

    private void assertInsert(String statement, int pk, List<SchemaAndValueField> expectedSchemaAndValuesByColumn) {
        TableId table = tableIdFromInsertStmt(statement);
        String expectedTopicName = table.schema() + "." + table.table();
        expectedTopicName = expectedTopicName.replaceAll("[ \"]", "_");

        try {
            executeAndWait(statement);
            SourceRecord record = assertRecordInserted(expectedTopicName, PK_FIELD, pk);
            assertRecordOffset(record, false, false);
            assertSourceInfo(record, "postgres", table.schema(), table.table());
            assertRecordSchemaAndValues(expectedSchemaAndValuesByColumn, record, Envelope.FieldName.AFTER);
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private SourceRecord assertRecordInserted(String expectedTopicName, String pkColumn, int pk) throws InterruptedException {
        assertFalse("records not generated", consumer.isEmpty());
        SourceRecord insertedRecord = consumer.remove();
        assertEquals(topicName(expectedTopicName), insertedRecord.topic());
        VerifyRecord.isValidInsert(insertedRecord, pkColumn, pk);
        return insertedRecord;
    }

    private void executeAndWait(String statements) throws Exception {
        TestHelper.execute(statements);
        consumer.await(TestHelper.waitTimeForRecords(), TimeUnit.SECONDS);
    }
}
