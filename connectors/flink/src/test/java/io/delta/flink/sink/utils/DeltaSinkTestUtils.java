/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.delta.flink.sink.utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import io.delta.flink.internal.options.DeltaConnectorConfiguration;
import io.delta.flink.sink.DeltaSink;
import io.delta.flink.sink.internal.DeltaBucketAssigner;
import io.delta.flink.sink.internal.DeltaPartitionComputer;
import io.delta.flink.sink.internal.DeltaSinkBuilder;
import io.delta.flink.sink.internal.DeltaSinkInternal;
import io.delta.flink.sink.internal.committables.DeltaCommittable;
import io.delta.flink.sink.internal.committables.DeltaGlobalCommittable;
import io.delta.flink.utils.DeltaTestUtils;
import io.delta.flink.utils.TestParquetReader;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.MemorySize;
import org.apache.flink.configuration.RestOptions;
import org.apache.flink.configuration.TaskManagerOptions;
import org.apache.flink.connector.file.sink.utils.FileSinkTestUtils;
import org.apache.flink.core.fs.FileSystem;
import org.apache.flink.core.fs.Path;
import org.apache.flink.core.fs.local.LocalFileSystem;
import org.apache.flink.core.fs.local.LocalRecoverableWriter;
import org.apache.flink.formats.parquet.ParquetWriterFactory;
import org.apache.flink.formats.parquet.row.ParquetRowDataBuilder;
import org.apache.flink.runtime.minicluster.MiniCluster;
import org.apache.flink.runtime.minicluster.MiniClusterConfiguration;
import org.apache.flink.streaming.api.functions.sink.filesystem.DeltaBulkBucketWriter;
import org.apache.flink.streaming.api.functions.sink.filesystem.DeltaPendingFile;
import org.apache.flink.streaming.api.functions.sink.filesystem.bucketassigners.BasePathBucketAssigner;
import org.apache.flink.streaming.api.functions.sink.filesystem.rollingpolicies.OnCheckpointRollingPolicy;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.util.DataFormatConverters;
import org.apache.flink.table.types.logical.IntType;
import org.apache.flink.table.types.logical.RowType;
import org.apache.flink.table.types.logical.VarCharType;
import org.apache.flink.table.types.utils.TypeConversions;
import org.apache.flink.types.Row;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DeltaSinkTestUtils {

    ///////////////////////////////////////////////////////////////////////////
    // test data utils
    ///////////////////////////////////////////////////////////////////////////

    public static final RowType TEST_ROW_TYPE = new RowType(Arrays.asList(
        new RowType.RowField("name", new VarCharType(VarCharType.MAX_LENGTH)),
        new RowType.RowField("surname", new VarCharType(VarCharType.MAX_LENGTH)),
        new RowType.RowField("age", new IntType())
    ));

    public static final RowType TEST_ROW_TYPE2 = new RowType(Arrays.asList(
        new RowType.RowField("name", new VarCharType(VarCharType.MAX_LENGTH)),
        new RowType.RowField("surname", new VarCharType(VarCharType.MAX_LENGTH)),
        new RowType.RowField("age", new VarCharType(VarCharType.MAX_LENGTH))
    ));

    public static final RowType TEST_PARTITIONED_ROW_TYPE = new RowType(Arrays.asList(
        new RowType.RowField("name", new VarCharType(VarCharType.MAX_LENGTH)),
        new RowType.RowField("surname", new VarCharType(VarCharType.MAX_LENGTH)),
        new RowType.RowField("age", new IntType()),
        new RowType.RowField("col1", new VarCharType(VarCharType.MAX_LENGTH)),
        new RowType.RowField("col2", new VarCharType(VarCharType.MAX_LENGTH))
    ));

    /**
     * {@link org.apache.flink.table.data.util.DataFormatConverters.DataFormatConverter} for
     * {@link #TEST_ROW_TYPE}
     */
    @SuppressWarnings("unchecked")
    public static final DataFormatConverters.DataFormatConverter<RowData, Row>
        TEST_ROW_TYPE_CONVERTER = DataFormatConverters.getConverterForDataType(
            TypeConversions.fromLogicalToDataType(TEST_ROW_TYPE)
        );

    @SuppressWarnings("unchecked")
    public static final DataFormatConverters.DataFormatConverter<RowData, Row>
        TEST_ROW_TYPE_CONVERTER2 = DataFormatConverters.getConverterForDataType(
        TypeConversions.fromLogicalToDataType(TEST_ROW_TYPE2)
    );

    @SuppressWarnings("unchecked")
    public static final DataFormatConverters.DataFormatConverter<RowData, Row>
            PARTITIONED_CONVERTER = DataFormatConverters.getConverterForDataType(
            TypeConversions.fromLogicalToDataType(TEST_PARTITIONED_ROW_TYPE)
        );

    public static List<RowData> getTestRowData(int num_records) {
        List<RowData> rows = new ArrayList<>(num_records);
        for (int i = 0; i < num_records; i++) {
            Integer v = i;
            rows.add(
                TEST_ROW_TYPE_CONVERTER.toInternal(
                    Row.of(
                        String.valueOf(v),
                        String.valueOf((v + v)),
                        v)
                )
            );
        }
        return rows;
    }

    public static RowData getTestRowDataEvent(String name, String surname, Integer age) {
        return TEST_ROW_TYPE_CONVERTER.toInternal(Row.of(name, surname, age));
    }

    public static RowType addNewColumnToSchema(RowType schema) {
        return addNewColumnToSchema(schema, true);
    }

    public static RowType addNewColumnToSchema(RowType schema, boolean isNullable) {
        List<RowType.RowField> fields = new ArrayList<>(schema.getFields());
        fields.add(new RowType.RowField("someNewField", new IntType(isNullable)));
        return new RowType(fields);
    }

    public static RowType dropOneColumnFromSchema(RowType schema) {
        List<RowType.RowField> fields = new ArrayList<>(
            schema.getFields().subList(0, schema.getFields().size() - 2)
        );
        return new RowType(fields);
    }

    ///////////////////////////////////////////////////////////////////////////
    // test delta lake table utils
    ///////////////////////////////////////////////////////////////////////////

    public static LinkedHashMap<String, String> getEmptyTestPartitionSpec() {
        return new LinkedHashMap<>();
    }

    public static LinkedHashMap<String, String> getTestPartitionSpec() {
        return new LinkedHashMap<String, String>() {{
                put("col1", "val1");
                put("col2", "val2");
            }};
    }

    ///////////////////////////////////////////////////////////////////////////
    // test delta pending files utils
    ///////////////////////////////////////////////////////////////////////////

    public static DeltaPendingFile getTestDeltaPendingFile() {
        return getTestDeltaPendingFile(new LinkedHashMap<>());
    }

    public static DeltaPendingFile getTestDeltaPendingFile(
            LinkedHashMap<String, String> partitionSpec) {
        return new DeltaPendingFile(
            partitionSpec,
            "file_name-" + UUID.randomUUID(),
            new FileSinkTestUtils.TestPendingFileRecoverable(),
            new Random().nextInt(30000),
            new Random().nextInt(500000),
            System.currentTimeMillis()
        );
    }

    public static DeltaPendingFile getTestDeltaPendingFileWithAbsolutePath(
            org.apache.hadoop.fs.Path basePath,
            LinkedHashMap<String, String> partitionSpec) {

        return new DeltaPendingFile(
            partitionSpec,
            ((basePath.toString().endsWith("/")) ? basePath.toString() : basePath + "/")
            + "file_name-" + UUID.randomUUID(),
            new FileSinkTestUtils.TestPendingFileRecoverable(),
            new Random().nextInt(30000),
            new Random().nextInt(500000),
            System.currentTimeMillis()
        );
    }

    public static DeltaPendingFile getTestDeltaPendingFileForFileName(
        String fileName,
        LinkedHashMap<String, String> partitionSpec) {

        return new DeltaPendingFile(
            partitionSpec,
            fileName,
            new FileSinkTestUtils.TestPendingFileRecoverable(),
            new Random().nextInt(30000),
            new Random().nextInt(500000),
            System.currentTimeMillis()
        );
    }

    ///////////////////////////////////////////////////////////////////////////
    // test delta committable utils
    ///////////////////////////////////////////////////////////////////////////

    static final String TEST_APP_ID = UUID.randomUUID().toString();
    static final long TEST_CHECKPOINT_ID = new Random().nextInt(10);


    public static List<DeltaCommittable> committablesToAbstractCommittables(
            List<DeltaCommittable> committables) {
        return committables;
    }

    public static List<DeltaCommittable> getListOfDeltaCommittables(int size, long checkpointId) {
        return getListOfDeltaCommittables(size, new LinkedHashMap<>(), checkpointId);
    }

    public static List<DeltaCommittable> getListOfDeltaCommittables(
        int size, LinkedHashMap<String, String> partitionSpec, long checkpointId) {
        List<DeltaCommittable> deltaCommittableList = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            deltaCommittableList.add(
                DeltaSinkTestUtils.getTestDeltaCommittableWithPendingFile(
                    partitionSpec, checkpointId)
            );
        }
        return deltaCommittableList;
    }

    public static List<DeltaGlobalCommittable> getListOfDeltaGlobalCommittables(
            List<DeltaCommittable> committables) {
        return Collections.singletonList(
                new DeltaGlobalCommittable(committablesToAbstractCommittables(committables)));
    }

    public static List<DeltaGlobalCommittable> getListOfDeltaGlobalCommittables(
            int size, LinkedHashMap<String, String> partitionSpec) {
        List<DeltaCommittable> committables =
                committablesToAbstractCommittables(getListOfDeltaCommittables(
                        size, partitionSpec, TEST_CHECKPOINT_ID));
        return Collections.singletonList(new DeltaGlobalCommittable(committables));
    }

    public static DeltaCommittable getTestDeltaCommittableWithPendingFile(
        LinkedHashMap<String, String> partitionSpec) {
        return getTestDeltaCommittableWithPendingFile(partitionSpec, TEST_CHECKPOINT_ID);
    }

    public static DeltaCommittable getTestDeltaCommittableWithPendingFile(
        LinkedHashMap<String, String> partitionSpec, long checkpointId) {
        return new DeltaCommittable(
            DeltaSinkTestUtils.getTestDeltaPendingFile(partitionSpec),
            TEST_APP_ID,
            checkpointId
        );
    }

    public static void validateDeltaCommittablesEquality(
        DeltaCommittable committable,
        DeltaCommittable deserialized,
        LinkedHashMap<String, String> expectedPartitionSpec) {
        assertEquals(
            committable.getDeltaPendingFile().getPendingFile(),
            deserialized.getDeltaPendingFile().getPendingFile());
        assertEquals(committable.getCheckpointId(), deserialized.getCheckpointId());
        assertEquals(committable.getAppId(), deserialized.getAppId());
        assertEquals(
            committable.getDeltaPendingFile().getFileName(),
            deserialized.getDeltaPendingFile().getFileName());
        assertEquals(
            committable.getDeltaPendingFile().getFileSize(),
            deserialized.getDeltaPendingFile().getFileSize());
        assertEquals(
            committable.getDeltaPendingFile().getRecordCount(),
            deserialized.getDeltaPendingFile().getRecordCount());
        assertEquals(
            committable.getDeltaPendingFile().getLastUpdateTime(),
            deserialized.getDeltaPendingFile().getLastUpdateTime());
        assertEquals(
            expectedPartitionSpec,
            deserialized.getDeltaPendingFile().getPartitionSpec());
    }

    ///////////////////////////////////////////////////////////////////////////
    // filesystem test utils
    ///////////////////////////////////////////////////////////////////////////

    public static int validateIfPathContainsParquetFilesWithData(String deltaTablePath)
        throws IOException {
        List<File> files = Files.walk(Paths.get(deltaTablePath))
            .map(java.nio.file.Path::toFile)
            .filter(file -> !file.isDirectory())
            .filter(file -> !file.getName().contains("inprogress"))
            .filter(file -> file.getName().endsWith(".snappy.parquet"))
            .collect(Collectors.toList());

        assertTrue(files.size() > 0);

        int totalRecordsCount = 0;
        for (File file : files) {
            // simple check if files contain any data besides footer
            assertTrue(file.length() > 100);
            totalRecordsCount += TestParquetReader.parseAndCountRecords(
                new Path(file.toURI()),
                DeltaSinkTestUtils.TEST_ROW_TYPE,
                TEST_ROW_TYPE_CONVERTER
            );
        }
        return totalRecordsCount;
    }


    ///////////////////////////////////////////////////////////////////////////
    // ParquetWriterFactory test utils
    ///////////////////////////////////////////////////////////////////////////

    public static ParquetWriterFactory<RowData> createTestWriterFactory() {
        return ParquetRowDataBuilder.createWriterFactory(
            DeltaSinkTestUtils.TEST_ROW_TYPE,
            DeltaTestUtils.getHadoopConf(),
            true // utcTimestamp
        );
    }

    ///////////////////////////////////////////////////////////////////////////
    // writer test utils
    ///////////////////////////////////////////////////////////////////////////

    public static final OnCheckpointRollingPolicy<RowData, String> ON_CHECKPOINT_ROLLING_POLICY =
        OnCheckpointRollingPolicy.build();

    /**
     * Internal testing method for getting local data writer.
     *
     * @param path bucket path
     * @return mock implementation for {@link LocalRecoverableWriter}
     */
    private static LocalRecoverableWriter getTestBucketRecoverableWriter(Path path) {
        try {
            final FileSystem fs = FileSystem.get(path.toUri());
            if (!(fs instanceof LocalFileSystem)) {
                fail(
                    "Expected Local FS but got a "
                        + fs.getClass().getName()
                        + " for path: "
                        + path);
            }
            return new LocalRecoverableWriter((LocalFileSystem) fs);
        } catch (IOException e) {
            fail();
        }
        return null;
    }

    public static DeltaBulkBucketWriter<RowData, String> createBucketWriter(Path path)
        throws IOException {
        return new DeltaBulkBucketWriter<>(
            getTestBucketRecoverableWriter(path),
            DeltaSinkTestUtils.createTestWriterFactory());
    }

    ///////////////////////////////////////////////////////////////////////////
    // IT case utils
    ///////////////////////////////////////////////////////////////////////////

    public static DeltaSinkInternal<RowData> createDeltaSink(
            String deltaTablePath,
            boolean isTablePartitioned) {
        return createDeltaSink(deltaTablePath, isTablePartitioned, DeltaTestUtils.getHadoopConf());
    }

    public static DeltaSinkInternal<RowData> createDeltaSink(
            String deltaTablePath,
            boolean isTablePartitioned,
            org.apache.hadoop.conf.Configuration hadoopConf) {

        if (isTablePartitioned) {
            DeltaSinkBuilder<RowData> builder = new DeltaSinkBuilder.DefaultDeltaFormatBuilder<>(
                new Path(deltaTablePath),
                DeltaTestUtils.getHadoopConf(),
                ParquetRowDataBuilder.createWriterFactory(
                    DeltaSinkTestUtils.TEST_ROW_TYPE,
                    hadoopConf,
                    true // utcTimestamp
                ),
                new BasePathBucketAssigner<>(),
                OnCheckpointRollingPolicy.build(),
                DeltaSinkTestUtils.TEST_PARTITIONED_ROW_TYPE,
                false, // mergeSchema
                new DeltaConnectorConfiguration()
            );
            return builder
                .withBucketAssigner(getTestPartitionAssigner())
                .build();
        }

        return DeltaSink
            .forRowData(
                new Path(deltaTablePath),
                hadoopConf,
                DeltaSinkTestUtils.TEST_ROW_TYPE).build();
    }

    public static DeltaBucketAssigner<RowData> getTestPartitionAssigner() {
        DeltaPartitionComputer<RowData> partitionComputer =
            (element, context) -> new LinkedHashMap<String, String>() {{
                    put("col1", Integer.toString(ThreadLocalRandom.current().nextInt(0, 2)));
                    put("col2", Integer.toString(ThreadLocalRandom.current().nextInt(0, 2)));
                }};
        return new DeltaBucketAssigner<>(partitionComputer);
    }

    public static MiniCluster getMiniCluster() {
        final Configuration config = new Configuration();
        config.setString(RestOptions.BIND_PORT, "18081-19000");
        config.set(TaskManagerOptions.FRAMEWORK_OFF_HEAP_MEMORY, MemorySize.parse("128mb"));
        config.set(TaskManagerOptions.TASK_OFF_HEAP_MEMORY, MemorySize.parse("128mb"));
        final MiniClusterConfiguration cfg =
            new MiniClusterConfiguration.Builder()
                .setNumTaskManagers(3)
                .setNumSlotsPerTaskManager(2)
                .setConfiguration(config)
                .build();
        return new MiniCluster(cfg);
    }
}
