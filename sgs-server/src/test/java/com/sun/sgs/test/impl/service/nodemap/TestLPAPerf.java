/*
 * Copyright 2007-2009 Sun Microsystems, Inc.
 *
 * This file is part of Project Darkstar Server.
 *
 * Project Darkstar Server is free software: you can redistribute it
 * and/or modify it under the terms of the GNU General Public License
 * version 2 as published by the Free Software Foundation and
 * distributed hereunder to you.
 *
 * Project Darkstar Server is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.sun.sgs.test.impl.service.nodemap;

import com.sun.sgs.impl.service.nodemap.affinity.AffinityGroup;
import com.sun.sgs.impl.service.nodemap.affinity.GraphBuilder;
import com.sun.sgs.impl.service.nodemap.affinity.LabelPropagation;
import com.sun.sgs.tools.test.ParameterizedFilteredNameRunner;
import java.util.Arrays;
import java.util.Collection;
import org.junit.runner.RunWith;
import org.junit.Test;
import org.junit.runners.Parameterized;
/**
 *
 *
 */
//@RunWith(FilteredNameRunner.class)
@RunWith(ParameterizedFilteredNameRunner.class)
public class TestLPAPerf {

    private static final int WARMUP_RUNS = 100;
    private static final int RUNS = 500;

    // Number of threads, set with data below for each run
    private int numThreads;
    private double nodePref;

    @Parameterized.Parameters
    public static Collection data() {
        return Arrays.asList(new Object[][]
            {{1, 0.00}, {2, 0.00}, {4, 0.00}, {8, 0.00}, {16, 0.00},
             {1, 0.1}, {2, 0.1}, {4, 0.1}, {8, 0.1}, {16, 0.1},
             {1, 0.2}, {2, 0.2}, {4, 0.2}, {8, 0.2}, {16, 0.2},
             {1, -0.1}, {2, -0.1}, {4, -0.1}, {8, -0.1}, {16, -0.1}});
    }

    public TestLPAPerf(int numThreads, double nodePref) {
        this.numThreads = numThreads;
        this.nodePref = nodePref;
    }

    @Test
    public void warmup() {
        // Warm up the compilers
        LabelPropagation lpa =
           new LabelPropagation(new ZachBuilder(), false, numThreads, nodePref);

        for (int i = 0; i < WARMUP_RUNS; i++) {
            lpa.findCommunities();
        }
        lpa.shutdown();
    }

    @Test
    public void testZachary() {
        GraphBuilder builder = new ZachBuilder();
        // second argument true:  gather statistics
        LabelPropagation lpa =
            new LabelPropagation(builder, true, numThreads, nodePref);

        long avgTime = 0;
        int avgIter = 0;
        double avgMod  = 0.0;
        double maxMod = 0.0;
        double minMod = 1.0;
        long maxTime = 0;
        long minTime = Integer.MAX_VALUE;
        for (int i = 0; i < RUNS; i++) {
            Collection<AffinityGroup> groups = lpa.findCommunities();
            long time = lpa.getTime();
            avgTime = avgTime + time;
            maxTime = Math.max(maxTime, time);
            minTime = Math.min(minTime, time);

            avgIter = avgIter + lpa.getIterations();
            double mod = lpa.getModularity();
            avgMod = avgMod + mod;
            maxMod = Math.max(maxMod, mod);
            minMod = Math.min(minMod, mod);
        }
        System.out.printf("XXX (%d runs, %d threads, %.4f): " +
                          "avg time : %4.2f ms, " +
                          " time range [%d - %d ms] " +
                          " avg iters : %4.2f, avg modularity: %.4f, " +
                          " modularity range [%.4f - %.4f] %n",
                          RUNS, numThreads, nodePref,
                          avgTime/(double) RUNS,
                          minTime, maxTime,
                          avgIter/(double) RUNS,
                          avgMod/(double) RUNS,
                          minMod, maxMod);
        lpa.shutdown();
    }
}
