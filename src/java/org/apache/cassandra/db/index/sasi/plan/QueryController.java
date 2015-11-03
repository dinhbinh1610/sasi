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
package org.apache.cassandra.db.index.sasi.plan;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.TimeUnit;

import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.filter.ExtendedFilter;
import org.apache.cassandra.db.index.SSTableAttachedSecondaryIndex;
import org.apache.cassandra.db.index.sasi.SSTableIndex;
import org.apache.cassandra.db.index.sasi.TermIterator;
import org.apache.cassandra.db.index.sasi.conf.ColumnIndex;
import org.apache.cassandra.db.index.sasi.conf.view.View;
import org.apache.cassandra.db.index.sasi.disk.Token;
import org.apache.cassandra.db.index.sasi.exceptions.TimeQuotaExceededException;
import org.apache.cassandra.db.index.sasi.memory.IndexMemtable;
import org.apache.cassandra.db.index.sasi.plan.Operation.OperationType;
import org.apache.cassandra.db.index.sasi.utils.RangeIntersectionIterator;
import org.apache.cassandra.db.index.sasi.utils.RangeIterator;
import org.apache.cassandra.db.index.sasi.utils.RangeUnionIterator;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.io.sstable.SSTableReader;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.utils.Pair;

public class QueryController
{
    private final long executionQuota;
    private final long executionStart;

    private final SSTableAttachedSecondaryIndex backend;
    private final Map<Collection<Expression>, List<RangeIterator<Long, Token>>> resources = new HashMap<>();
    private final Set<SSTableReader> scope;

    public QueryController(SSTableAttachedSecondaryIndex backend, ExtendedFilter filter, long timeQuotaMs)
    {
        this.backend = backend;
        this.executionQuota = TimeUnit.MILLISECONDS.toNanos(timeQuotaMs);
        this.executionStart = System.nanoTime();
        this.scope = getSSTableScope(backend.getBaseCfs(), filter);
    }

    public AbstractType<?> getKeyValidator()
    {
        return backend.getKeyValidator();
    }

    public ColumnIndex getIndex(ByteBuffer columnName)
    {
        return backend.getIndex(columnName);
    }

    public ColumnDefinition getColumn(ByteBuffer columnName)
    {
        return backend.getBaseCfs().metadata.getColumnDefinition(columnName);
    }

    public AbstractType<?> getComparator(ColumnDefinition column)
    {
        return backend.getComparator(column);
    }

    /**
     * Build a range iterator from the given list of expressions by applying given operation (OR/AND).
     * Building of such iterator involves index search, results of which are persisted in the internal resources list
     * and can be released later via {@link QueryController#releaseIndexes(Operation)}.
     *
     * @param op The operation type to coalesce expressions with.
     * @param expressions The expressions to build range iterator from (expressions with not results are ignored).
     *
     * @return The range builder based on given expressions and operation type.
     */
    public RangeIterator.Builder<Long, Token> getIndexes(OperationType op, Collection<Expression> expressions)
    {
        if (resources.containsKey(expressions))
            throw new IllegalArgumentException("Can't process the same expressions multiple times.");

        IndexMemtable currentMemtable = backend.getMemtable();

        RangeIterator.Builder<Long, Token> builder = op == OperationType.OR
                                                ? RangeUnionIterator.<Long, Token>builder()
                                                : RangeIntersectionIterator.<Long, Token>builder();

        List<RangeIterator<Long, Token>> perIndexUnions = new ArrayList<>();

        for (Map.Entry<Expression, Set<SSTableIndex>> e : getView(op, expressions).entrySet())
        {
            RangeIterator<Long, Token> index = TermIterator.build(e.getKey(),
                                                                  currentMemtable.search(e.getKey()),
                                                                  e.getValue());

            if (index == null)
                continue;

            builder.add(index);
            perIndexUnions.add(index);
        }

        resources.put(expressions, perIndexUnions);
        return builder;
    }

    public void checkpoint()
    {
        if ((System.nanoTime() - executionStart) >= executionQuota)
            throw new TimeQuotaExceededException();
    }

    public void releaseIndexes(Operation operation)
    {
        if (operation.expressions != null)
            releaseIndexes(resources.remove(operation.expressions.values()));
    }

    private void releaseIndexes(List<RangeIterator<Long, Token>> indexes)
    {
        if (indexes == null)
            return;

        for (RangeIterator<Long, Token> index : indexes)
            FileUtils.closeQuietly(index);
    }

    public void finish()
    {
        try
        {
            for (List<RangeIterator<Long, Token>> indexes : resources.values())
                releaseIndexes(indexes);
        }
        finally
        {
            SSTableReader.releaseReferences(scope);
        }
    }

    private Map<Expression, Set<SSTableIndex>> getView(OperationType op, Collection<Expression> expressions)
    {
        // first let's determine the primary expression if op is AND
        Pair<Expression, Set<SSTableIndex>> primary = (op == OperationType.AND) ? calculatePrimary(expressions) : null;

        Map<Expression, Set<SSTableIndex>> indexes = new HashMap<>();
        for (Expression e : expressions)
        {
            // NO_EQ and non-index column query should only act as FILTER BY for satisfiedBy(Row) method
            // because otherwise it likely to go through the whole index.
            if (!e.isIndexed() || e.getOp() == Expression.Op.NOT_EQ)
                continue;

            // primary expression, we'll have to add as is
            if (primary != null && e.equals(primary.left))
            {
                indexes.put(primary.left, primary.right);
                continue;
            }

            View view = e.index.getView();
            if (view == null)
                continue;

            Set<SSTableIndex> readers = new HashSet<>();
            if (primary != null && primary.right.size() > 0)
            {
                for (SSTableIndex index : primary.right)
                    readers.addAll(view.match(index.minKey(), index.maxKey()));
            }
            else
            {
                readers.addAll(view.match(scope, e));
            }

            indexes.put(e, readers);
        }

        return indexes;
    }

    private Pair<Expression, Set<SSTableIndex>> calculatePrimary(Collection<Expression> expressions)
    {
        Expression expression = null;
        Set<SSTableIndex> primaryIndexes = Collections.emptySet();

        for (Expression e : expressions)
        {
            if (!e.isIndexed())
                continue;

            View view = e.index.getView();
            if (view == null)
                continue;

            Set<SSTableIndex> indexes = view.match(scope, e);
            if (primaryIndexes.size() > indexes.size())
            {
                primaryIndexes = indexes;
                expression = e;
            }
        }

        return expression == null ? null : Pair.create(expression, primaryIndexes);
    }

    private static Set<SSTableReader> getSSTableScope(ColumnFamilyStore store, ExtendedFilter filter)
    {
        ColumnFamilyStore.ViewFragment scope = store.markReferenced(filter.dataRange.keyRange());
        return scope == null ? Collections.<SSTableReader>emptySet() : new HashSet<>(scope.sstables);
    }
}
