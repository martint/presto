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
package io.prestosql.orc.stream;

import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.prestosql.orc.checkpoint.FloatStreamCheckpoint;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.type.Type;

import java.io.IOException;

import static io.airlift.slice.SizeOf.SIZE_OF_FLOAT;
import static java.lang.Float.floatToIntBits;
import static java.lang.Integer.min;

public class FloatInputStream
        implements ValueInputStream<FloatStreamCheckpoint>
{
    private static final int BUFFER_SIZE = 128;
    private final OrcInputStream input;
    private final byte[] buffer = new byte[SIZE_OF_FLOAT * BUFFER_SIZE];
    private final Slice slice = Slices.wrappedBuffer(buffer);

    public FloatInputStream(OrcInputStream input)
    {
        this.input = input;
    }

    @Override
    public Class<FloatStreamCheckpoint> getCheckpointType()
    {
        return FloatStreamCheckpoint.class;
    }

    @Override
    public void seekToCheckpoint(FloatStreamCheckpoint checkpoint)
            throws IOException
    {
        input.seekToCheckpoint(checkpoint.getInputStreamCheckpoint());
    }

    @Override
    public void skip(long items)
            throws IOException
    {
        long length = items * SIZE_OF_FLOAT;
        input.skipFully(length);
    }

    public float next()
            throws IOException
    {
        input.readFully(buffer, 0, SIZE_OF_FLOAT);
        return slice.getFloat(0);
    }

    public Block nextBlock(Type type, boolean[] isNull)
            throws IOException
    {
        int items = isNull.length;
        BlockBuilder blockBuilder = type.createBlockBuilder(null, items);

        for (int batchBase = 0; batchBase < items; batchBase += BUFFER_SIZE) {
            int batchSize = min(items - batchBase, BUFFER_SIZE);

            // stream is null suppressed, so count the present values
            int nonNullCount = 0;
            for (int i = batchBase; i < batchBase + batchSize; i++) {
                if (!isNull[i]) {
                    nonNullCount++;
                }
            }
            input.readFully(buffer, 0, SIZE_OF_FLOAT * nonNullCount);

            int bufferIndex = 0;
            for (int i = batchBase; i < batchBase + batchSize; i++) {
                if (!isNull[i]) {
                    type.writeLong(blockBuilder, floatToIntBits(slice.getFloat(bufferIndex * SIZE_OF_FLOAT)));
                    bufferIndex++;
                }
                else {
                    blockBuilder.appendNull();
                }
            }
        }
        return blockBuilder.build();
    }

    public Block nextBlock(Type type, int items)
            throws IOException
    {
        BlockBuilder blockBuilder = type.createBlockBuilder(null, items);
        for (int batchBase = 0; batchBase < items; batchBase += BUFFER_SIZE) {
            int batchSize = min(items - batchBase, BUFFER_SIZE);

            input.readFully(buffer, 0, SIZE_OF_FLOAT * batchSize);

            for (int i = 0; i < batchSize; i++) {
                type.writeLong(blockBuilder, floatToIntBits(slice.getFloat(i * SIZE_OF_FLOAT)));
            }
        }
        return blockBuilder.build();
    }
}
