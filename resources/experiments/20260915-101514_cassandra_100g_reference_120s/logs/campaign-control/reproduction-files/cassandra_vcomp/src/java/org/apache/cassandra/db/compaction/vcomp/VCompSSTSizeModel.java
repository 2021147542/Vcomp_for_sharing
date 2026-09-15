/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.cassandra.db.compaction.vcomp;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Upper envelope of rational affine estimates of encoded Cassandra SSTable bytes. */
public final class VCompSSTSizeModel
{
    private final List<Line> lines = new ArrayList<>();

    public static VCompSSTSizeModel logical(long bytesPerEntry)
    {
        VCompSSTSizeModel model = new VCompSSTSizeModel();
        if (bytesPerEntry > 0) model.lines.add(new Line(bytesPerEntry, 1, 0));
        return model;
    }

    public boolean addCalibration(long n1, long bytes1, long n2, long bytes2)
    {
        if (n1 <= 0 || n2 <= n1 || bytes2 <= bytes1) return false;
        long numerator = bytes2 - bytes1;
        long denominator = n2 - n1;
        BigInteger left = BigInteger.valueOf(bytes1).multiply(BigInteger.valueOf(denominator));
        BigInteger right = BigInteger.valueOf(n1).multiply(BigInteger.valueOf(numerator));
        long fixed = 0;
        if (left.compareTo(right) > 0)
        {
            BigInteger[] result = left.subtract(right).divideAndRemainder(BigInteger.valueOf(denominator));
            fixed = result[0].longValueExact() + (result[1].signum() == 0 ? 0 : 1);
        }
        lines.add(new Line(numerator, denominator, fixed));
        return true;
    }

    public boolean isValid() { return !lines.isEmpty(); }

    public long estimate(long entries)
    {
        if (entries == 0) return 0;
        long result = 0;
        for (Line line : lines)
        {
            BigInteger[] variable = BigInteger.valueOf(entries).multiply(BigInteger.valueOf(line.numerator))
                                              .divideAndRemainder(BigInteger.valueOf(line.denominator));
            BigInteger estimate = variable[0].add(variable[1].signum() == 0 ? BigInteger.ZERO : BigInteger.ONE)
                                             .add(BigInteger.valueOf(line.fixed));
            long bounded = estimate.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0
                           ? Long.MAX_VALUE : estimate.longValueExact();
            result = Math.max(result, bounded);
        }
        return result;
    }

    public long maxEntries(long targetBytes)
    {
        if (!isValid()) return 0;
        long result = Long.MAX_VALUE;
        for (Line line : lines)
        {
            if (targetBytes < line.fixed) return 0;
            BigInteger capacity = BigInteger.valueOf(targetBytes - line.fixed)
                                            .multiply(BigInteger.valueOf(line.denominator))
                                            .divide(BigInteger.valueOf(line.numerator));
            long bounded = capacity.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0
                           ? Long.MAX_VALUE : capacity.longValueExact();
            result = Math.min(result, bounded);
        }
        return result;
    }

    private static final class Line
    {
        final long numerator, denominator, fixed;
        Line(long numerator, long denominator, long fixed)
        { this.numerator = numerator; this.denominator = denominator; this.fixed = fixed; }
    }
}
