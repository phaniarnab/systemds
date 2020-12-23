/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */


package org.apache.sysds.test.functions.lineage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.recompile.Recompiler;
import org.apache.sysds.runtime.lineage.Lineage;
import org.apache.sysds.runtime.lineage.LineageCacheConfig;
import org.apache.sysds.runtime.lineage.LineageCacheConfig.ReuseCacheType;
import org.apache.sysds.runtime.lineage.LineageCacheStatistics;
import org.apache.sysds.runtime.matrix.data.MatrixValue;
import org.apache.sysds.test.TestConfiguration;
import org.apache.sysds.test.TestUtils;
import org.apache.sysds.utils.Statistics;
import org.junit.Assert;
import org.junit.Test;

@net.jcip.annotations.NotThreadSafe
public class CacheEvictionTest extends LineageBase {

	protected static final String TEST_DIR = "functions/lineage/";
	protected static final String TEST_NAME1 = "CacheEviction2";

	protected String TEST_CLASS_DIR = TEST_DIR + CacheEvictionTest.class.getSimpleName() + "/";
	
	@Override
	public void setUp() {
		TestUtils.clearAssertionInformation();
		addTestConfiguration(TEST_NAME1, new TestConfiguration(TEST_CLASS_DIR, TEST_NAME1));
	}
	
	@Test
	public void testEvictionOrder() {
		runTest(TEST_NAME1);
	}

	public void runTest(String testname) {
		boolean old_simplification = OptimizerUtils.ALLOW_ALGEBRAIC_SIMPLIFICATION;
		boolean old_sum_product = OptimizerUtils.ALLOW_SUM_PRODUCT_REWRITES;
		
		try {
			LOG.debug("------------ BEGIN " + testname + "------------");
			
			/* This test verifies the order of evicted items w.r.t. the specified
			 * cache policies. This test enables individual components of the 
			 * scoring function by masking the other components, and compare the
			 * order of evicted entries for different policies. HYBRID policy is 
			 * not considered for this test as it is hard to anticipate the reuse
			 * statistics if all the components are unmasked. 
			 * 
			 * TODO: Test disk spilling, which will need some tunings in eviction
			 * logic; otherwise the automated test might take significantly 
			 * longer as eviction logic tends to just delete entries with little
			 * computation and estimated I/O time. Note that disk spilling is 
			 * already happening as part of other tests (e.g. MultiLogReg).
			 */
			
			OptimizerUtils.ALLOW_ALGEBRAIC_SIMPLIFICATION = false;
			OptimizerUtils.ALLOW_SUM_PRODUCT_REWRITES = false;
			
			getAndLoadTestConfiguration(testname);
			fullDMLScriptName = getScript();
			Lineage.resetInternalState();
			//long cacheSize = LineageCacheEviction.getCacheLimit();
			//LineageCacheConfig.setReusableOpcodes("exp", "+", "round");
			LineageCacheConfig.setSpill(false);
			
			// LRU based eviction
			List<String> proArgs = new ArrayList<>();
			proArgs.add("-stats");
			proArgs.add("-lineage");
			proArgs.add(ReuseCacheType.REUSE_FULL.name().toLowerCase());
			proArgs.add("policy_lru");
			proArgs.add("-args");
			//proArgs.add(String.valueOf(cacheSize));
			proArgs.add(output("R"));
			programArgs = proArgs.toArray(new String[proArgs.size()]);
			runTest(true, EXCEPTION_NOT_EXPECTED, null, -1);
			HashMap<MatrixValue.CellIndex, Double> R_lru = readDMLMatrixFromOutputDir("R");
			//long expCount_lru = Statistics.getCPHeavyHitterCount("exp");
			long hitCount_lru = LineageCacheStatistics.getInstHits();
			//long evictedCount_lru = LineageCacheStatistics.getMemDeletes();
			long colmeanCount_lru = Statistics.getCPHeavyHitterCount("uacmean");
			
			// costnsize scheme (computationTime/Size)
			proArgs.clear();
			proArgs.add("-stats");
			proArgs.add("-lineage");
			proArgs.add(ReuseCacheType.REUSE_FULL.name().toLowerCase());
			proArgs.add("policy_costnsize");
			proArgs.add("-args");
			//proArgs.add(String.valueOf(cacheSize));
			proArgs.add(output("R"));
			programArgs = proArgs.toArray(new String[proArgs.size()]);
			Lineage.resetInternalState();
			runTest(true, EXCEPTION_NOT_EXPECTED, null, -1);
			HashMap<MatrixValue.CellIndex, Double> R_costnsize= readDMLMatrixFromOutputDir("R");
			//long expCount_wt = Statistics.getCPHeavyHitterCount("exp");
			long hitCount_cs = LineageCacheStatistics.getInstHits();
			//long evictedCount_wt = LineageCacheStatistics.getMemDeletes();
			//LineageCacheConfig.resetReusableOpcodes();
			long colmeanCount_cs = Statistics.getCPHeavyHitterCount("uacmean");
			
			// dagheight scheme
			proArgs.clear();
			proArgs.add("-stats");
			proArgs.add("-lineage");
			proArgs.add(ReuseCacheType.REUSE_FULL.name().toLowerCase());
			proArgs.add("policy_dagheight");
			proArgs.add("-args");
			//proArgs.add(String.valueOf(cacheSize));
			proArgs.add(output("R"));
			programArgs = proArgs.toArray(new String[proArgs.size()]);
			Lineage.resetInternalState();
			runTest(true, EXCEPTION_NOT_EXPECTED, null, -1);
			HashMap<MatrixValue.CellIndex, Double> R_dagheight = readDMLMatrixFromOutputDir("R");
			//long expCount_wt = Statistics.getCPHeavyHitterCount("exp");
			long hitCount_dh = LineageCacheStatistics.getInstHits();
			//long evictedCount_wt = LineageCacheStatistics.getMemDeletes();
			//LineageCacheConfig.resetReusableOpcodes();
			long colmeanCount_dh = Statistics.getCPHeavyHitterCount("uacmean");
			
			// Compare results
			Lineage.setLinReuseNone();
			TestUtils.compareMatrices(R_lru, R_costnsize, 1e-6, "LRU", "costnsize");
			TestUtils.compareMatrices(R_lru, R_dagheight, 1e-6, "LRU", "dagheight");
			
			// Compare reused instructions
			//Assert.assertTrue(expCount_lru >= expCount_wt);
			// Compare counts of evicted items
			// LRU tends to evict more entries to recover equal amount of memory
			// Note: changed to equals to fix flaky tests where both are not evicted at all
			// (e.g., due to high execution time as sometimes observed through github actions)
			//Assert.assertTrue(("Violated expected evictions: "+evictedCount_lru+" >= "+evictedCount_wt),
			//	evictedCount_lru >= evictedCount_wt);
			// Compare cache hits
			Assert.assertTrue(hitCount_lru < hitCount_cs);
			Assert.assertTrue(hitCount_lru < hitCount_dh);
			Assert.assertTrue(colmeanCount_cs < colmeanCount_lru);
			Assert.assertTrue(colmeanCount_dh < colmeanCount_lru);
		}
		finally {
			OptimizerUtils.ALLOW_ALGEBRAIC_SIMPLIFICATION = old_simplification;
			OptimizerUtils.ALLOW_SUM_PRODUCT_REWRITES = old_sum_product;
			LineageCacheConfig.setSpill(true);
			Recompiler.reinitRecompiler();
		}
	}
}
