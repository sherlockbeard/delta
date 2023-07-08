package io.delta.flink.source.internal.builder;

import java.util.Collections;
import java.util.List;

import io.delta.flink.source.internal.DeltaPartitionFieldExtractor;
import io.delta.flink.source.internal.DeltaSourceOptions;
import io.delta.flink.source.internal.state.DeltaSourceSplit;
import org.apache.flink.formats.parquet.ParquetColumnarRowInputFormat;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.runtime.typeutils.InternalTypeInfo;
import org.apache.flink.table.types.logical.RowType;
import org.apache.hadoop.conf.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builder for {@link RowData} implementation io {@link FormatBuilder}
 */
public class RowDataFormatBuilder implements FormatBuilder<RowData> {

    private static final Logger LOG = LoggerFactory.getLogger(RowDataFormatBuilder.class);

    // -------------- Hardcoded Non Public Options ----------
    /**
     * Hardcoded option for {@link RowDataFormat} to threat timestamps as a UTC timestamps.
     */
    private static final boolean PARQUET_UTC_TIMESTAMP = true;

    /**
     * Hardcoded option for {@link RowDataFormat} to use case-sensitive in column name processing
     * for Parquet files.
     */
    private static final boolean PARQUET_CASE_SENSITIVE = true;
    // ------------------------------------------------------

    private final RowType rowType;

    /**
     * An instance of Hadoop configuration used to read Parquet files.
     */
    private final Configuration hadoopConfiguration;

    /**
     * An array with Delta table partition columns.
     */
    private List<String> partitionColumns; // partitionColumns are validated in DeltaSourceBuilder.

    private int batchSize = DeltaSourceOptions.PARQUET_BATCH_SIZE.defaultValue();

    RowDataFormatBuilder(RowType rowType, Configuration hadoopConfiguration) {
        this.rowType = rowType;
        this.hadoopConfiguration = hadoopConfiguration;
        this.partitionColumns = Collections.emptyList();
    }

    @Override
    public RowDataFormatBuilder partitionColumns(List<String> partitionColumns) {
        this.partitionColumns = partitionColumns;
        return this;
    }

    @Override
    public FormatBuilder<RowData> parquetBatchSize(int size) {
        this.batchSize = size;
        return this;
    }

    /**
     * Creates an instance of {@link RowDataFormat}.
     *
     * @throws io.delta.flink.internal.options.DeltaOptionValidationException if invalid
     *                arguments were passed to {@link RowDataFormatBuilder}. For example null
     *                arguments.
     */
    @Override
    public RowDataFormat build() {

        if (partitionColumns.isEmpty()) {
            LOG.info("Building format data for non-partitioned Delta table.");
            return buildFormatWithoutPartitions();
        } else {
            LOG.info("Building format data for partitioned Delta table.");
            return
                buildFormatWithPartitionColumns(
                    rowType,
                    hadoopConfiguration,
                    partitionColumns
                );
        }
    }

    private RowDataFormat buildFormatWithoutPartitions() {
        return buildFormatWithPartitionColumns(
            rowType,
            hadoopConfiguration,
            Collections.emptyList()
        );
    }

    private RowDataFormat buildFormatWithPartitionColumns(
            RowType producedRowType,
            Configuration hadoopConfig,
            List<String> partitionColumns) {

        ParquetColumnarRowInputFormat<DeltaSourceSplit> rowInputFormat =
            ParquetColumnarRowInputFormat.createPartitionedFormat(
                hadoopConfig,
                producedRowType,
                InternalTypeInfo.of(producedRowType),
                partitionColumns,
                new DeltaPartitionFieldExtractor<>(),
                batchSize,
                PARQUET_UTC_TIMESTAMP,
                PARQUET_CASE_SENSITIVE
            );

        return new RowDataFormat(rowInputFormat);
    }
}
