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
package org.apache.cassandra.db.index.sasi.conf.view;

import java.nio.ByteBuffer;
import java.util.*;

import org.apache.cassandra.db.index.sasi.SSTableIndex;
import org.apache.cassandra.db.index.sasi.conf.ColumnIndex;
import org.apache.cassandra.db.index.sasi.plan.Expression;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.db.marshal.AsciiType;
import org.apache.cassandra.db.marshal.UTF8Type;
import org.apache.cassandra.io.sstable.Descriptor;
import org.apache.cassandra.io.sstable.SSTableReader;
import org.apache.cassandra.utils.Interval;
import org.apache.cassandra.utils.IntervalTree;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

public class View implements Iterable<SSTableIndex>
{
    private final Map<Descriptor, SSTableIndex> view;

    private final TermTree termTree;
    private final IntervalTree<ByteBuffer, SSTableIndex, Interval<ByteBuffer, SSTableIndex>> keyIntervalTree;

    public View(ColumnIndex index, AbstractType<?> keyValidator, Set<SSTableIndex> indexes)
    {
        this(index, keyValidator, Collections.<SSTableIndex>emptyList(), Collections.<SSTableReader>emptyList(), indexes);
    }

    public View(ColumnIndex index, AbstractType<?> keyValidator,
                Collection<SSTableIndex> currentView,
                Collection<SSTableReader> oldSSTables,
                Set<SSTableIndex> newIndexes)
    {
        Map<Descriptor, SSTableIndex> newView = new HashMap<>();

        AbstractType<?> validator = index.getValidator();
        TermTree.Builder termTreeBuilder = (validator instanceof AsciiType || validator instanceof UTF8Type)
                                            ? new PrefixTermTree.Builder(index.getMode().mode, validator)
                                            : new RangeTermTree.Builder(index.getMode().mode, validator);

        List<Interval<ByteBuffer, SSTableIndex>> keyIntervals = new ArrayList<>();
        for (SSTableIndex sstableIndex : Iterables.concat(currentView, newIndexes))
        {
            SSTableReader sstable = sstableIndex.getSSTable();
            if (oldSSTables.contains(sstable) || sstable.isMarkedCompacted() || newView.containsKey(sstable.descriptor))
            {
                sstableIndex.release();
                continue;
            }

            newView.put(sstable.descriptor, sstableIndex);

            termTreeBuilder.add(sstableIndex);
            keyIntervals.add(Interval.create(sstableIndex.minKey(), sstableIndex.maxKey(), sstableIndex));
        }

        this.view = newView;
        this.termTree = termTreeBuilder.build();
        this.keyIntervalTree = IntervalTree.build(keyIntervals, keyValidator);

        if (keyIntervalTree.intervalCount() != termTree.intervalCount())
            throw new IllegalStateException(String.format("mismatched sizes for intervals tree for keys vs terms: %d != %d", keyIntervalTree.intervalCount(), termTree.intervalCount()));
    }

    public Set<SSTableIndex> match(final Set<SSTableReader> scope, Expression expression)
    {
        return Sets.filter(termTree.search(expression), new Predicate<SSTableIndex>()
        {
            @Override
            public boolean apply(SSTableIndex index)
            {
                return scope.contains(index.getSSTable());
            }
        });
    }

    public List<SSTableIndex> match(ByteBuffer minKey, ByteBuffer maxKey)
    {
        return keyIntervalTree.search(Interval.create(minKey, maxKey, (SSTableIndex) null));
    }

    @Override
    public Iterator<SSTableIndex> iterator()
    {
        return view.values().iterator();
    }

    public Collection<SSTableIndex> getIndexes()
    {
        return view.values();
    }
}