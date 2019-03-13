/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.orc.reader;

import io.airlift.slice.Slice;
import io.prestosql.memory.context.LocalMemoryContext;
import io.prestosql.orc.OrcCorruptionException;
import io.prestosql.orc.StreamDescriptor;
import io.prestosql.orc.metadata.ColumnEncoding;
import io.prestosql.orc.stream.BooleanInputStream;
import io.prestosql.orc.stream.DecimalInputStream;
import io.prestosql.orc.stream.InputStreamSource;
import io.prestosql.orc.stream.InputStreamSources;
import io.prestosql.orc.stream.LongInputStream;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.block.RunLengthEncodedBlock;
import io.prestosql.spi.type.DecimalType;
import io.prestosql.spi.type.Decimals;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.UnscaledDecimal128Arithmetic;
import org.openjdk.jol.info.ClassLayout;

import javax.annotation.Nullable;

import java.io.IOException;
import java.time.ZoneId;
import java.util.List;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Verify.verify;
import static io.airlift.slice.SizeOf.sizeOf;
import static io.prestosql.orc.metadata.Stream.StreamKind.DATA;
import static io.prestosql.orc.metadata.Stream.StreamKind.PRESENT;
import static io.prestosql.orc.metadata.Stream.StreamKind.SECONDARY;
import static io.prestosql.orc.reader.StreamReaders.verifyStreamType;
import static io.prestosql.orc.stream.MissingInputStreamSource.missingStreamSource;
import static io.prestosql.spi.type.UnscaledDecimal128Arithmetic.rescale;
import static java.util.Objects.requireNonNull;

public class DecimalStreamReader
        implements StreamReader
{
    private static final int INSTANCE_SIZE = ClassLayout.parseClass(DecimalStreamReader.class).instanceSize();

    private final DecimalType type;
    private final StreamDescriptor streamDescriptor;

    private int readOffset;
    private int nextBatchSize;

    private boolean[] nullVector = new boolean[0];
    private long[] scaleVector = new long[0];

    private InputStreamSource<BooleanInputStream> presentStreamSource = missingStreamSource(BooleanInputStream.class);
    @Nullable
    private BooleanInputStream presentStream;

    private InputStreamSource<DecimalInputStream> decimalStreamSource = missingStreamSource(DecimalInputStream.class);
    @Nullable
    private DecimalInputStream decimalStream;

    private InputStreamSource<LongInputStream> scaleStreamSource = missingStreamSource(LongInputStream.class);
    @Nullable
    private LongInputStream scaleStream;

    private boolean rowGroupOpen;

    private final LocalMemoryContext systemMemoryContext;

    public DecimalStreamReader(Type type, StreamDescriptor streamDescriptor, LocalMemoryContext systemMemoryContext)
            throws OrcCorruptionException
    {
        requireNonNull(type, "type is null");
        verifyStreamType(streamDescriptor, type, DecimalType.class::isInstance);
        this.type = (DecimalType) type;

        this.streamDescriptor = requireNonNull(streamDescriptor, "stream is null");
        this.systemMemoryContext = requireNonNull(systemMemoryContext, "systemMemoryContext is null");
    }

    @Override
    public void prepareNextRead(int batchSize)
    {
        readOffset += nextBatchSize;
        nextBatchSize = batchSize;
    }

    @Override
    public Block readBlock()
            throws IOException
    {
        if (!rowGroupOpen) {
            openRowGroup();
        }

        seekToOffset();

        if (decimalStream == null && scaleStream == null && presentStream != null) {
            presentStream.skip(nextBatchSize);
            Block nullValueBlock = RunLengthEncodedBlock.create(type, null, nextBatchSize);
            readOffset = 0;
            nextBatchSize = 0;
            return nullValueBlock;
        }

        BlockBuilder builder = type.createBlockBuilder(null, nextBatchSize);

        if (presentStream == null) {
            if (decimalStream == null) {
                throw new OrcCorruptionException(streamDescriptor.getOrcDataSourceId(), "Value is not null but decimal stream is not present");
            }
            if (scaleStream == null) {
                throw new OrcCorruptionException(streamDescriptor.getOrcDataSourceId(), "Value is not null but scale stream is not present");
            }

            for (int i = 0; i < nextBatchSize; i++) {
                long sourceScale = scaleStream.next();
                if (type.isShort()) {
                    long rescaledDecimal = Decimals.rescale(decimalStream.nextLong(), (int) sourceScale, type.getScale());
                    type.writeLong(builder, rescaledDecimal);
                }
                else {
                    Slice decimal = UnscaledDecimal128Arithmetic.unscaledDecimal();
                    Slice rescaledDecimal = UnscaledDecimal128Arithmetic.unscaledDecimal();

                    decimalStream.nextLongDecimal(decimal);
                    rescale(decimal, (int) (type.getScale() - sourceScale), rescaledDecimal);
                    type.writeSlice(builder, rescaledDecimal);
                }
            }
        }
        else {
            verify(decimalStream != null);
            verify(scaleStream != null);
            for (int i = 0; i < nextBatchSize; i++) {
                if (presentStream.nextBoolean()) {
                    // The current row is not null
                    long sourceScale = scaleStream.next();
                    if (type.isShort()) {
                        long rescaledDecimal = Decimals.rescale(decimalStream.nextLong(), (int) sourceScale, type.getScale());
                        type.writeLong(builder, rescaledDecimal);
                    }
                    else {
                        Slice decimal = UnscaledDecimal128Arithmetic.unscaledDecimal();
                        Slice rescaledDecimal = UnscaledDecimal128Arithmetic.unscaledDecimal();

                        decimalStream.nextLongDecimal(decimal);
                        rescale(decimal, (int) (type.getScale() - sourceScale), rescaledDecimal);
                        type.writeSlice(builder, rescaledDecimal);
                    }
                }
                else {
                    builder.appendNull();
                }
            }
        }

        readOffset = 0;
        nextBatchSize = 0;

        return builder.build();
    }

    private void openRowGroup()
            throws IOException
    {
        presentStream = presentStreamSource.openStream();
        decimalStream = decimalStreamSource.openStream();
        scaleStream = scaleStreamSource.openStream();
        rowGroupOpen = true;
    }

    private void seekToOffset()
            throws IOException
    {
        if (readOffset > 0) {
            if (presentStream != null) {
                // skip ahead the present bit reader, but count the set bits
                // and use this as the skip size for the data reader
                readOffset = presentStream.countBitsSet(readOffset);
            }
            if (readOffset > 0) {
                if (decimalStream == null) {
                    throw new OrcCorruptionException(streamDescriptor.getOrcDataSourceId(), "Value is not null but decimal stream is not present");
                }
                if (scaleStream == null) {
                    throw new OrcCorruptionException(streamDescriptor.getOrcDataSourceId(), "Value is not null but scale stream is not present");
                }

                decimalStream.skip(readOffset);
                scaleStream.skip(readOffset);
            }
        }
    }

    @Override
    public void startStripe(ZoneId timeZone, InputStreamSources dictionaryStreamSources, List<ColumnEncoding> encoding)
    {
        presentStreamSource = missingStreamSource(BooleanInputStream.class);
        decimalStreamSource = missingStreamSource(DecimalInputStream.class);
        scaleStreamSource = missingStreamSource(LongInputStream.class);

        readOffset = 0;
        nextBatchSize = 0;

        presentStream = null;
        decimalStream = null;
        scaleStream = null;

        rowGroupOpen = false;
    }

    @Override
    public void startRowGroup(InputStreamSources dataStreamSources)
    {
        presentStreamSource = dataStreamSources.getInputStreamSource(streamDescriptor, PRESENT, BooleanInputStream.class);
        decimalStreamSource = dataStreamSources.getInputStreamSource(streamDescriptor, DATA, DecimalInputStream.class);
        scaleStreamSource = dataStreamSources.getInputStreamSource(streamDescriptor, SECONDARY, LongInputStream.class);

        readOffset = 0;
        nextBatchSize = 0;

        presentStream = null;
        decimalStream = null;
        scaleStream = null;

        rowGroupOpen = false;
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .addValue(streamDescriptor)
                .toString();
    }

    @Override
    public void close()
    {
        systemMemoryContext.close();
        nullVector = null;
        scaleVector = null;
    }

    @Override
    public long getRetainedSizeInBytes()
    {
        return INSTANCE_SIZE + sizeOf(nullVector) + sizeOf(scaleVector);
    }
}
