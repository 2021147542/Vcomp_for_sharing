/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 */
package org.apache.cassandra.db.compaction.vcomp;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Exact integer count/select certificate used by VComp splitting and materialization. */
public final class VCompDiscreteCdf
{
    private static final BigInteger ONE = BigInteger.ONE;

    public static final class Interval
    {
        final long keyMin, keyMax, entries;

        public Interval(long keyMin, long keyMax, long entries)
        {
            if (keyMin < 0 || keyMax < keyMin || entries < 0)
                throw new IllegalArgumentException("invalid discrete interval");
            if (BigInteger.valueOf(entries).compareTo(span(keyMin, keyMax)) > 0)
                throw new IllegalArgumentException("discrete interval mass exceeds capacity");
            this.keyMin = keyMin;
            this.keyMax = keyMax;
            this.entries = entries;
        }
    }

    static final class Cell
    {
        long keyMin, keyMax, entries, prefix;
        long originKeyMin, originKeyMax, originEntries, originRankBegin;
    }

    private final List<Cell> cells;
    private final long count;

    public VCompDiscreteCdf(List<Interval> intervals)
    {
        if (intervals == null)
            throw new NullPointerException("intervals");
        ArrayList<Cell> result = new ArrayList<>(intervals.size());
        long total = 0;
        long previous = -1;
        for (Interval interval : intervals)
        {
            if (interval.keyMin <= previous)
                throw new IllegalArgumentException("discrete intervals overlap or are unordered");
            previous = interval.keyMax;
            if (interval.entries == 0)
                continue;
            if (Long.MAX_VALUE - total < interval.entries)
                throw new IllegalArgumentException("discrete cardinality overflow");
            Cell cell = new Cell();
            cell.entries = interval.entries;
            cell.prefix = total;
            cell.originKeyMin = interval.keyMin;
            cell.originKeyMax = interval.keyMax;
            cell.originEntries = interval.entries;
            cell.keyMin = selectInCell(cell, 0);
            cell.keyMax = selectInCell(cell, interval.entries - 1);
            result.add(cell);
            total += interval.entries;
        }
        cells = Collections.unmodifiableList(result);
        count = total;
    }

    private VCompDiscreteCdf(List<Cell> cells, long count)
    {
        this.cells = Collections.unmodifiableList(cells);
        this.count = count;
    }

    public long count() { return count; }
    public boolean isEmpty() { return count == 0; }

    public long select(long rank)
    {
        if (rank < 0 || rank >= count)
            throw new IllegalArgumentException("rank is outside the discrete CDF");
        int low = 0, high = cells.size();
        while (low < high)
        {
            int middle = (low + high) >>> 1;
            Cell cell = cells.get(middle);
            if (rank >= cell.prefix + cell.entries)
                low = middle + 1;
            else
                high = middle;
        }
        Cell cell = cells.get(low);
        return selectInCell(cell, rank - cell.prefix);
    }

    public long countLessThan(long key) { return countBeforeEdge(BigInteger.valueOf(key)); }
    public long countThrough(long key) { return countBeforeEdge(BigInteger.valueOf(key).add(ONE)); }

    private long countBeforeEdge(BigInteger edge)
    {
        int low = 0, high = cells.size();
        while (low < high)
        {
            int middle = (low + high) >>> 1;
            if (BigInteger.valueOf(cells.get(middle).keyMax).compareTo(edge) < 0)
                low = middle + 1;
            else
                high = middle;
        }
        if (low == cells.size())
            return count;
        Cell cell = cells.get(low);
        if (edge.compareTo(BigInteger.valueOf(cell.keyMin)) <= 0)
            return cell.prefix;
        BigInteger originCount = BigInteger.valueOf(cell.originEntries)
                                                   .multiply(edge.subtract(BigInteger.valueOf(cell.originKeyMin)))
                                                   .divide(span(cell.originKeyMin, cell.originKeyMax));
        BigInteger begin = BigInteger.valueOf(cell.originRankBegin);
        BigInteger end = begin.add(BigInteger.valueOf(cell.entries));
        if (originCount.compareTo(begin) < 0) originCount = begin;
        if (originCount.compareTo(end) > 0) originCount = end;
        return cell.prefix + originCount.subtract(begin).longValueExact();
    }

    public VCompDiscreteCdf slice(long firstRank, long sliceCount)
    {
        if (firstRank < 0 || sliceCount < 0 || firstRank > count || sliceCount > count - firstRank)
            throw new IllegalArgumentException("slice is outside the discrete CDF");
        if (sliceCount == 0)
            return new VCompDiscreteCdf(Collections.emptyList(), 0);
        long endRank = firstRank + sliceCount;
        long prefix = 0;
        ArrayList<Cell> result = new ArrayList<>();
        for (Cell source : cells)
        {
            long sourceEnd = source.prefix + source.entries;
            if (sourceEnd <= firstRank) continue;
            if (source.prefix >= endRank) break;
            long begin = Math.max(firstRank, source.prefix);
            long end = Math.min(endRank, sourceEnd);
            Cell child = new Cell();
            child.originKeyMin = source.originKeyMin;
            child.originKeyMax = source.originKeyMax;
            child.originEntries = source.originEntries;
            child.originRankBegin = source.originRankBegin + begin - source.prefix;
            child.entries = end - begin;
            child.prefix = prefix;
            child.keyMin = selectInCell(child, 0);
            child.keyMax = selectInCell(child, child.entries - 1);
            result.add(child);
            prefix += child.entries;
        }
        if (prefix != sliceCount)
            throw new IllegalStateException("discrete slice count mismatch");
        return new VCompDiscreteCdf(result, sliceCount);
    }

    public Cursor cursor() { return new Cursor(this); }

    private static BigInteger span(long minimum, long maximum)
    {
        return BigInteger.valueOf(maximum).subtract(BigInteger.valueOf(minimum)).add(ONE);
    }

    private static long selectInCell(Cell cell, long localRank)
    {
        BigInteger rank = BigInteger.valueOf(cell.originRankBegin)
                                    .add(BigInteger.valueOf(localRank)).add(ONE);
        BigInteger offset = rank
                                      .multiply(span(cell.originKeyMin, cell.originKeyMax))
                                      .subtract(ONE)
                                      .divide(BigInteger.valueOf(cell.originEntries));
        return BigInteger.valueOf(cell.originKeyMin).add(offset).longValueExact();
    }

    /** O(1) integer work per emitted key after one division per cell. */
    public static final class Cursor
    {
        private final VCompDiscreteCdf cdf;
        private int cellIndex;
        private long emitted, current, stepQuotient, stepRemainder, remainder;
        private boolean initialized;

        private Cursor(VCompDiscreteCdf cdf) { this.cdf = cdf; }

        public boolean hasNext() { return cellIndex < cdf.cells.size(); }

        public long nextLong()
        {
            if (!hasNext())
                throw new java.util.NoSuchElementException();
            if (!initialized) initialize();
            Cell cell = cdf.cells.get(cellIndex);
            long result = current;
            emitted++;
            if (emitted == cell.entries)
            {
                cellIndex++;
                initialized = false;
            }
            else
            {
                boolean carry = stepRemainder != 0 && remainder >= cell.originEntries - stepRemainder;
                remainder = carry ? remainder - (cell.originEntries - stepRemainder)
                                  : remainder + stepRemainder;
                current = Math.addExact(current, stepQuotient);
                if (carry) current = Math.addExact(current, 1);
            }
            return result;
        }

        private void initialize()
        {
            Cell cell = cdf.cells.get(cellIndex);
            BigInteger width = span(cell.originKeyMin, cell.originKeyMax);
            BigInteger numerator = BigInteger.valueOf(cell.originRankBegin).add(ONE)
                                               .multiply(width).subtract(ONE);
            BigInteger[] first = numerator.divideAndRemainder(BigInteger.valueOf(cell.originEntries));
            current = BigInteger.valueOf(cell.originKeyMin).add(first[0]).longValueExact();
            remainder = first[1].longValueExact();
            BigInteger[] step = width.divideAndRemainder(BigInteger.valueOf(cell.originEntries));
            stepQuotient = step[0].longValueExact();
            stepRemainder = step[1].longValueExact();
            emitted = 0;
            initialized = true;
        }
    }
}
