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
package io.trino.operator;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.trino.spi.Page;
import io.trino.spi.connector.RowChangeParadigm;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        property = "@type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = DeleteAndInsertMergeProcessor.class, name = "deleteAndInsert"),
        @JsonSubTypes.Type(value = ChangeOnlyUpdatedColumnsMergeProcessor.class, name = "changeOnlyUpdated"),
})
public interface RowChangeProcessor
{
    /**
     * @return Return the RowChangeParadigm used by the RowChangeProcessor.
     */
    RowChangeParadigm getRowChangeParadigm();

    /**
     * Transform the inputPage into an output page.  The format of the inputPage depends
     * on the operation.
     */
    Page transformPage(Page inputPage);
}