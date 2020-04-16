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

package org.apache.sysds.runtime.lineage;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.cost.CostEstimatorStaticRuntime;
import org.apache.sysds.lops.MMTSJ.MMTSJType;
import org.apache.sysds.parser.Statement;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.parfor.stat.InfrastructureAnalyzer;
import org.apache.sysds.runtime.instructions.CPInstructionParser;
import org.apache.sysds.runtime.instructions.Instruction;
import org.apache.sysds.runtime.instructions.cp.CPInstruction.CPType;
import org.apache.sysds.runtime.instructions.cp.ComputationCPInstruction;
import org.apache.sysds.runtime.instructions.cp.Data;
import org.apache.sysds.runtime.instructions.cp.MMTSJCPInstruction;
import org.apache.sysds.runtime.instructions.cp.ParameterizedBuiltinCPInstruction;
import org.apache.sysds.runtime.instructions.cp.ScalarObject;
import org.apache.sysds.runtime.lineage.LineageCacheConfig.LineageCacheStatus;
import org.apache.sysds.runtime.lineage.LineageCacheConfig.ReuseCacheType;
import org.apache.sysds.runtime.matrix.data.InputInfo;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.matrix.data.OutputInfo;
import org.apache.sysds.runtime.meta.MetaDataFormat;
import org.apache.sysds.runtime.util.LocalFileUtils;

public class LineageCache {
	private static final Map<LineageItem, Entry> _cache = new HashMap<>();
	private static final Map<LineageItem, SpilledItem> _spillList = new HashMap<>();
	private static final HashSet<LineageItem> _removelist = new HashSet<>();
	private static final double CACHE_FRAC = 0.05; // 5% of JVM heap size
	private static final long CACHE_LIMIT; //limit in bytes
	private static String _outdir = null;
	private static double _rw100mb = 0;
	private static long _cachesize = 0;
	private static Entry _head = null;
	private static Entry _end = null;

	static {
		long maxMem = InfrastructureAnalyzer.getLocalMaxMemory();
		CACHE_LIMIT = (long)(CACHE_FRAC * maxMem);
		//CACHE_LIMIT = 200*1024*1024; //DEBUG
	}
	
	//--------------------- CACHE LOGIC METHODS ----------------------
	
	public static boolean reuse(Instruction inst, ExecutionContext ec) {
		if (ReuseCacheType.isNone())
			return false;
		
		boolean reuse = false;
		//NOTE: the check for computation CP instructions ensures that the output
		// will always fit in memory and hence can be pinned unconditionally
		if (inst instanceof ComputationCPInstruction && LineageCache.isReusable(inst, ec)) {
			LineageItem item = ((ComputationCPInstruction) inst).getLineageItems(ec)[0];
			
			synchronized( _cache ) {
				//try to reuse full or partial intermediates
				if (LineageCacheConfig.getCacheType().isFullReuse())
					reuse = fullReuse(item, (ComputationCPInstruction)inst, ec); 
				if (LineageCacheConfig.getCacheType().isPartialReuse())
					reuse |= LineageRewriteReuse.executeRewrites(inst, ec);
				
				if (reuse && DMLScript.STATISTICS)
					LineageCacheStatistics.incrementInstHits();
				
				//create a placeholder if no reuse to avoid redundancy
				//(e.g., concurrent threads that try to start the computation)
				if(!reuse && isMarkedForCaching(inst, ec))
					putIntern(item, null, null, 0);
			}
		}
		
		return reuse;
	}
	
	public static Entry reuse(LineageItem item) {
		if (ReuseCacheType.isNone())
			return null;

		Entry e = null;
		synchronized( _cache ) {
			if (LineageCache.probe(item)) 
				e = LineageCache.get(item);
			else
				//create a placeholder if no reuse to avoid redundancy
				//(e.g., concurrent threads that try to start the computation)
				putIntern(item, null, null, 0);
				//FIXME: parfor - every thread gets different function names
		}
		return e;
	}
	
	public static boolean reuse(List<String> outputs, int numOutputs, LineageItem[] liInputs, String name, ExecutionContext ec)
	{
		if( ReuseCacheType.isNone() || !LineageCacheConfig.getCacheType().isMultilevelReuse())
			return false;

		boolean reuse = (numOutputs != 0);
		HashMap<String, Data> funcOutputs = new HashMap<>();
		HashMap<String, LineageItem> funcLIs = new HashMap<>();
		for (int i=0; i<numOutputs; i++) {
			String opcode = name + String.valueOf(i+1);
			LineageItem li = new LineageItem(outputs.get(i), opcode, liInputs);
			Entry cachedValue = LineageCache.reuse(li); 
			//TODO: handling of recursive calls
			
			if (cachedValue != null && !cachedValue.isNullVal()) {
				String boundVarName = outputs.get(i);
				Data boundValue = null;
				//convert to matrix object
				if (cachedValue.isMatrixValue()) {
					MetaDataFormat md = new MetaDataFormat(cachedValue.getMBValue().getDataCharacteristics(), 
							OutputInfo.BinaryCellOutputInfo, InputInfo.BinaryCellInputInfo);
					boundValue = new MatrixObject(ValueType.FP64, boundVarName, md);
					((MatrixObject)boundValue).acquireModify(cachedValue.getMBValue());
					((MatrixObject)boundValue).release();
				}
				else
					boundValue = cachedValue.getSOValue();

				funcOutputs.put(boundVarName, boundValue);
				
				LineageItem orig = _cache.get(li)._origItem; //FIXME: synchronize
				funcLIs.put(boundVarName, orig);
			}
			else {
				// if one output cannot be reused, we need to execute the function
				// NOTE: all outputs need to be prepared for caching and hence,
				// we cannot directly return here
				reuse = false;
			}
		}
		
		if (reuse) {
			funcOutputs.forEach((var, val) -> {
				//cleanup existing data bound to output variable name
				Data exdata = ec.removeVariable(var);
				if( exdata != val)
					ec.cleanupDataObject(exdata);
				//add/replace data in symbol table
				ec.setVariable(var, val);
			});
			//map original lineage items return to the calling site
			funcLIs.forEach((var, li) -> ec.getLineage().set(var, li));
		}
		return reuse;
	}
	
	//NOTE: safe to pin the object in memory as coming from CPInstruction
	public static void put(Instruction inst, ExecutionContext ec, long exectime) {
		if (inst instanceof ComputationCPInstruction && isReusable(inst, ec) ) {
			LineageItem item = ((LineageTraceable) inst).getLineageItems(ec)[0];
			//This method is called only to put matrix value
			MatrixObject mo = ec.getMatrixObject(((ComputationCPInstruction) inst).output);
			synchronized( _cache ) {
				putIntern(item, mo.acquireReadAndRelease(), null, exectime);
			}
		}
	}
	
	public static void putValue(Instruction inst, ExecutionContext ec, long RealExectime) {
		if (ReuseCacheType.isNone())
			return;
		if (inst instanceof ComputationCPInstruction && isReusable(inst, ec) ) {
			if (!isMarkedForCaching(inst, ec)) return;
			LineageItem item = ((LineageTraceable) inst).getLineageItems(ec)[0];
			//MatrixObject mo = ec.getMatrixObject(((ComputationCPInstruction) inst).output);
			Data data = ec.getVariable(((ComputationCPInstruction) inst).output);
			MatrixObject mo = data instanceof MatrixObject ? (MatrixObject)data : null;
			ScalarObject so = data instanceof ScalarObject ? (ScalarObject)data : null;
			MatrixBlock Mval = mo != null ? mo.acquireReadAndRelease() : null;
			_cache.get(item).setValue(Mval, so, RealExectime); //outside sync to prevent deadlocks
			long size = _cache.get(item).getSize();
			
			synchronized( _cache ) {
				if( !isBelowThreshold(size) ) 
					makeSpace(size);
				updateSize(size, true);
			}
		}
	}
	
	public static void putValue(LineageItem item, LineageItem probeItem, long RealExectime) {
		if (ReuseCacheType.isNone())
			return;
		if (LineageCache.probe(probeItem)) {
			Entry oe = LineageCache.get(probeItem);
			Entry e = _cache.get(item);
			//TODO: compute estimate for function
			if (oe.isMatrixValue())
				e.setValue(oe.getMBValue(), null, RealExectime); 
			else
				e.setValue(null, oe.getSOValue(), RealExectime);
			e._origItem = probeItem; 

			long size = oe.getSize();
			synchronized( _cache ) {
				if(!isBelowThreshold(size)) 
					makeSpace(size);
				updateSize(size, true);
			}
		}
		else
			removeEntry(item);  //remove the placeholder

	}

	public static void putValue(List<String> outputs, int numOutputs, LineageItem[] liInputs, 
			String name, ExecutionContext ec, long exectime)
	{
		if( ReuseCacheType.isNone() || !LineageCacheConfig.getCacheType().isMultilevelReuse())
			return;

		HashMap<LineageItem, LineageItem> FuncLIMap = new HashMap<>();
		boolean AllOutputsCacheable = true;
		for (int i=0; i<numOutputs; i++) {
			String opcode = name + String.valueOf(i+1);
			LineageItem li = new LineageItem(outputs.get(i), opcode, liInputs);
			String boundVarName = outputs.get(i);
			LineageItem boundLI = ec.getLineage().get(boundVarName);
			if (boundLI != null)
				boundLI.resetVisitStatus();
			if (boundLI == null 
				|| !LineageCache.probe(li)
				|| LineageItemUtils.containsRandDataGen(new HashSet<>(Arrays.asList(liInputs)), boundLI)) {
				AllOutputsCacheable = false;
			}
			FuncLIMap.put(li, boundLI);
		}

		//cache either all the outputs, or none.
		if(AllOutputsCacheable) 
			FuncLIMap.forEach((Li, boundLI) -> LineageCache.putValue(Li, boundLI, exectime));
		else 
			//remove all the placeholders
			FuncLIMap.forEach((Li, boundLI) -> LineageCache.removeEntry(Li));
		
		return;
	}
	
	private static void putIntern(LineageItem key, MatrixBlock Mval, ScalarObject Sval, long exectime) {
		if (_cache.containsKey(key))
			//can come here if reuse_partial option is enabled
			return; 
			//throw new DMLRuntimeException("Redundant lineage caching detected: "+inst);
		
		// Create a new entry.
		Entry newItem = new Entry(key, Mval, Sval, exectime);
		
		// Make space by removing or spilling LRU entries.
		if( Mval != null || Sval != null ) {
			long size = newItem.getSize();
			if( size > CACHE_LIMIT )
				return; //not applicable
			if( !isBelowThreshold(size) ) 
				makeSpace(size);
			updateSize(size, true);
		}
		
		// Place the entry at head position.
		setHead(newItem);
		
		_cache.put(key, newItem);
		if (DMLScript.STATISTICS)
			LineageCacheStatistics.incrementMemWrites();
	}
	
	protected static boolean probe(LineageItem key) {
		boolean p = (_cache.containsKey(key) || _spillList.containsKey(key));
		if (!p && DMLScript.STATISTICS && _removelist.contains(key)) {
			// The sought entry was in cache but removed later 
			LineageCacheStatistics.incrementDelHits();
			//System.out.println("LI "+key+" is deleted");
		}
		return p;
	}
	
	public static void resetCache() {
		_cache.clear();
		_spillList.clear();
		_head = null;
		_end = null;
		_outdir = null;
		// reset cache size, otherwise the cache clear leads to unusable 
		// space which means evictions could run into endless loops
		_cachesize = 0;
		if (DMLScript.STATISTICS)
			_removelist.clear();
	}
	

	private static boolean fullReuse (LineageItem item, ComputationCPInstruction inst, ExecutionContext ec) {
		if (LineageCache.probe(item)) {
			Entry e = LineageCache.get(item);
			if (e.isMatrixValue())
				ec.setMatrixOutput(inst.output.getName(), e.getMBValue());
			else
				ec.setScalarOutput(inst.output.getName(), e.getSOValue());
			return true;
		}
		return false;
	}
	
	protected static Entry get(LineageItem key) {
		// This method is called only when entry is present either in cache or in local FS.
		if (_cache.containsKey(key)) {
			// Read and put the entry at head.
			Entry e = _cache.get(key);
			delete(e);
			setHead(e);
			if (DMLScript.STATISTICS)
				LineageCacheStatistics.incrementMemHits();
			return e;
		}
		else
			return readFromLocalFS(key);
	}
	
	public static boolean isReusable (Instruction inst, ExecutionContext ec) {
		// TODO: Move this to the new class LineageCacheConfig and extend
		return inst.getOpcode().equalsIgnoreCase("tsmm")
				|| inst.getOpcode().equalsIgnoreCase("ba+*")
				|| inst.getOpcode().equalsIgnoreCase("*") 
				|| inst.getOpcode().equalsIgnoreCase("/")
				|| inst.getOpcode().equalsIgnoreCase("+")
				|| inst.getOpcode().equalsIgnoreCase("nrow")
				|| inst.getOpcode().equalsIgnoreCase("ncol")
				|| inst.getOpcode().equalsIgnoreCase("rightIndex")
				|| inst.getOpcode().equalsIgnoreCase("leftIndex")
				|| inst.getOpcode().equalsIgnoreCase("groupedagg")
				|| inst.getOpcode().equalsIgnoreCase("r'")
				|| (inst.getOpcode().equalsIgnoreCase("append") && isVectorAppend(inst, ec))
				|| inst.getOpcode().equalsIgnoreCase("solve")
				|| inst.getOpcode().contains("spoof");
	}
	
	private static boolean isVectorAppend(Instruction inst, ExecutionContext ec) {
		ComputationCPInstruction cpinst = (ComputationCPInstruction) inst;
		if( !cpinst.input1.isMatrix() || !cpinst.input2.isMatrix() )
			return false;
		long c1 = ec.getMatrixObject(cpinst.input1).getNumColumns();
		long c2 = ec.getMatrixObject(cpinst.input2).getNumColumns();
		return(c1 == 1 || c2 == 1);
	}
	
	public static boolean isMarkedForCaching (Instruction inst, ExecutionContext ec) {
		if (!LineageCacheConfig.getCompAssRW())
			return true;

		if (((ComputationCPInstruction)inst).output.isMatrix()) {
			MatrixObject mo = ec.getMatrixObject(((ComputationCPInstruction)inst).output);
			//limit this to full reuse as partial reuse is applicable even for loop dependent operation
			boolean marked = (LineageCacheConfig.getCacheType() == ReuseCacheType.REUSE_FULL  
					&& !mo.isMarked()) ? false : true; 
			return marked;
		}
		else
			return true;
	}
	
	//---------------- CACHE SPACE MANAGEMENT METHODS -----------------
	
	private static boolean isBelowThreshold(long spaceNeeded) {
		return ((spaceNeeded + _cachesize) <= CACHE_LIMIT);
	}
	
	private static void makeSpace(long spaceNeeded) {
		// cost based eviction
		Entry e = _end;
		while (e != _head) {
			if ((spaceNeeded +_cachesize) <= CACHE_LIMIT)
				break;
			
			if (!LineageCacheConfig.isSetSpill()) {
				removeEntry(e);
				e = e._prev;
				continue;
			}

			//if (!e.getCacheStatus().canEvict() || !e.isMatrixValue()) {
			if (!e.getCacheStatus().canEvict()) {
				//Skip 1) function/SB placeholders currently under exectuion
				//2) entries with scalar object. Scalars can be part of
				//function outputs, removing those can impact multi-level reuse.
				//TODO: Array of MatrixBlocks and/or ScalarObjects in Entry
				e = e._prev;
				continue;
			}
			
			double exectime = ((double)e._exectime)/1000000; //in milliseconds
			if (!e.isMatrixValue()) {
				if (exectime < 100)
					removeEntry(e);
				e = e._prev;
				continue;
			}
			
			//BigDecimal exectime = new BigDecimal(((double)e._exectime)/1000000);
			//BigDecimal spilltime = new BigDecimal(getDiskSpillEstimate(e)*1000, MathContext.DECIMAL64);
			double spilltime = getDiskSpillEstimate(e) * 1000;  //in milliseconds
			
			/*if (((double)e._exectime)/1000000 > 100) {
				System.out.print("LI "+e._key.getOpcode());
				System.out.print(" exec time "+((double)e._exectime)/1000000000);
				System.out.print(" estimate time "+getDiskSpillEstimate(e));
				System.out.print(" dim "+e.getMBValue().getNumRows()+" "+e.getMBValue().getNumColumns());
				System.out.println(" size "+getDiskSizeEstimate(e));
			}*/
			
			if (LineageCacheConfig.isSetSpill()) {
				if (spilltime < 100) {
					if (exectime >= 100)
						spillToLocalFS(e); 
				}
				else {
					if (exectime > spilltime)
						spillToLocalFS(e);
				}
			}
				
			//System.out.print("estimated time :" + spilltime.toString() +" \n");
			/*if (LineageCacheConfig.isSetSpill()
					&& exectime.compareTo(spilltime) > 0
					&& getDiskSizeEstimate(e) >= 20) {
				//Spill to disk if 1) spilling is enabled, 2) size is more than 20MB
				//as estimated spill time for smaller matrices are not perfect,
				//3)re-execution is more expensive than to write and read.
				long t0 = System.nanoTime();
				spillToLocalFS(e); 
				long t1 = System.nanoTime();
				BigDecimal realSpilltime = new BigDecimal(((double)(t1-t0))/1000000);
				System.out.println("actual write time :" + realSpilltime.toString());
			}*/

			//Remove the entry from cache.
			removeEntry(e);
			e = e._prev;
		} 
	}
	
	private static void updateSize(long space, boolean addspace) {
		if (addspace)
			_cachesize += space;
		else
			_cachesize -= space;
		//System.out.println("current cache size "+_cachesize);
	}

	//---------------- COSTING RELATED METHODS -----------------

	private static double getDiskSizeEstimate(Entry e) {
		if (!e.isMatrixValue() || e.isNullVal())
			return 0;
		MatrixBlock mb = e.getMBValue();
		long r = mb.getNumRows();
		long c = mb.getNumColumns();
		long nnz = mb.getNonZeros();
		double s = OptimizerUtils.getSparsity(r, c, nnz);
		double disksize = ((double)MatrixBlock.estimateSizeOnDisk(r, c, (long)(s*r*c))) / (1024*1024);
		return disksize;
	}

	private static double getDiskSpillEstimate(Entry e) {
		// This includes sum of writing to and reading from disk
		long t0 = DMLScript.STATISTICS ? System.nanoTime() : 0;
		MatrixBlock mb = e.getMBValue();
		long r = mb.getNumRows();
		long c = mb.getNumColumns();
		long nnz = mb.getNonZeros();
		double s = OptimizerUtils.getSparsity(r, c, nnz);
		/*double disksize = ((double)MatrixBlock.estimateSizeOnDisk(r, c, (long)(s*r*c))) / (1024*1024);
		System.out.println("\n");
		System.out.println("Size : " + String.valueOf(disksize) +" ");*/
		double loadtime = CostEstimatorStaticRuntime.getFSReadTime(r, c, s);
		double writetime = CostEstimatorStaticRuntime.getFSWriteTime(r, c, s);
		if (DMLScript.STATISTICS) 
			LineageCacheStatistics.incrementCostingTime(System.nanoTime() - t0);
		return loadtime+writetime;
	}
	
	private static double getRecomputeEstimate(Instruction inst, ExecutionContext ec) {
		if (!((ComputationCPInstruction)inst).output.isMatrix()
			|| (((ComputationCPInstruction)inst).input1 != null && !((ComputationCPInstruction)inst).input1.isMatrix()))
			return 0; //this method will be deprecated. No need to support scalar

		long t0 = DMLScript.STATISTICS ? System.nanoTime() : 0;
		double nflops = 0;
		String instop= inst.getOpcode().contains("spoof") ? "spoof" : inst.getOpcode();
		CPType cptype = CPInstructionParser.String2CPInstructionType.get(instop);
		//TODO: All other relevant instruction types.
		switch (cptype)
		{
			case MMTSJ:  //tsmm
			{
				MatrixObject mo = ec.getMatrixObject(((ComputationCPInstruction)inst).input1);
				long r = mo.getNumRows();
				long c = mo.getNumColumns();
				long nnz = mo.getNnz();
				double s = OptimizerUtils.getSparsity(r, c, nnz);
				boolean sparse = MatrixBlock.evalSparseFormatInMemory(r, c, nnz);
				MMTSJType type = ((MMTSJCPInstruction)inst).getMMTSJType();
				if (type.isLeft())
					nflops = !sparse ? (r * c * s * c /2):(r * c * s * c * s /2);
				else
					nflops = !sparse ? ((double)r * c * r/2):(r*c*s + r*c*s*c*s /2);
				break;
			}
				
			case AggregateBinary:  //ba+*
			{
				MatrixObject mo1 = ec.getMatrixObject(((ComputationCPInstruction)inst).input1);
				MatrixObject mo2 = ec.getMatrixObject(((ComputationCPInstruction)inst).input2);
				long r1 = mo1.getNumRows();
				long c1 = mo1.getNumColumns();
				long nnz1 = mo1.getNnz();
				double s1 = OptimizerUtils.getSparsity(r1, c1, nnz1);
				boolean lsparse = MatrixBlock.evalSparseFormatInMemory(r1, c1, nnz1);
				long r2 = mo2.getNumRows();
				long c2 = mo2.getNumColumns();
				long nnz2 = mo2.getNnz();
				double s2 = OptimizerUtils.getSparsity(r2, c2, nnz2);
				boolean rsparse = MatrixBlock.evalSparseFormatInMemory(r2, c2, nnz2);
				if( !lsparse && !rsparse )
					nflops = 2 * (r1 * c1 * ((c2>1)?s1:1.0) * c2) /2;
				else if( !lsparse && rsparse )
					nflops = 2 * (r1 * c1 * s1 * c2 * s2) /2;
				else if( lsparse && !rsparse )
					nflops = 2 * (r1 * c1 * s1 * c2) /2;
				else //lsparse && rsparse
					nflops = 2 * (r1 * c1 * s1 * c2 * s2) /2;
				break;
			}
				
			case Binary:  //*, /
			{
				MatrixObject mo1 = ec.getMatrixObject(((ComputationCPInstruction)inst).input1);
				long r1 = mo1.getNumRows();
				long c1 = mo1.getNumColumns();
				if (inst.getOpcode().equalsIgnoreCase("*") || inst.getOpcode().equalsIgnoreCase("/"))
					// considering the dimensions of inputs and the output are same 
					nflops = r1 * c1; 
				else if (inst.getOpcode().equalsIgnoreCase("solve"))
					nflops = r1 * c1 * c1;
				break;
			}
			
			case MatrixIndexing:  //rightIndex
			{
				MatrixObject mo1 = ec.getMatrixObject(((ComputationCPInstruction)inst).input1);
				long r1 = mo1.getNumRows();
				long c1 = mo1.getNumColumns();
				long nnz1 = mo1.getNnz();
				double s1 = OptimizerUtils.getSparsity(r1, c1, nnz1);
				boolean lsparse = MatrixBlock.evalSparseFormatInMemory(r1, c1, nnz1);
				//if (inst.getOpcode().equalsIgnoreCase("rightIndex"))
					nflops = 1.0 * (lsparse ? r1 * c1 * s1 : r1 * c1); //FIXME
				break;
			}
			
			case ParameterizedBuiltin:  //groupedagg (sum, count)
			{
				String opcode = ((ParameterizedBuiltinCPInstruction)inst).getOpcode();
				HashMap<String, String> params = ((ParameterizedBuiltinCPInstruction)inst).getParameterMap();
				long r1 = ec.getMatrixObject(params.get(Statement.GAGG_TARGET)).getNumRows();
				String fn = params.get(Statement.GAGG_FN);
				double xga = 0;
				if (opcode.equalsIgnoreCase("groupedagg")) {
					if (fn.equalsIgnoreCase("sum"))
						xga = 4;
					else if(fn.equalsIgnoreCase("count"))
						xga = 1;
					//TODO: cm, variance
				}
				//TODO: support other PBuiltin ops
				nflops = 2 * r1+xga * r1;
				break;
			}

			case Reorg:  //r'
			{
				MatrixObject mo = ec.getMatrixObject(((ComputationCPInstruction)inst).input1);
				long r = mo.getNumRows();
				long c = mo.getNumColumns();
				long nnz = mo.getNnz();
				double s = OptimizerUtils.getSparsity(r, c, nnz);
				boolean sparse = MatrixBlock.evalSparseFormatInMemory(r, c, nnz);
				nflops = sparse ? r*c*s : r*c; 
				break;
			}

			case Append:  //cbind, rbind
			{
				MatrixObject mo1 = ec.getMatrixObject(((ComputationCPInstruction)inst).input1);
				MatrixObject mo2 = ec.getMatrixObject(((ComputationCPInstruction)inst).input2);
				long r1 = mo1.getNumRows();
				long c1 = mo1.getNumColumns();
				long nnz1 = mo1.getNnz();
				double s1 = OptimizerUtils.getSparsity(r1, c1, nnz1);
				boolean lsparse = MatrixBlock.evalSparseFormatInMemory(r1, c1, nnz1);
				long r2 = mo2.getNumRows();
				long c2 = mo2.getNumColumns();
				long nnz2 = mo2.getNnz();
				double s2 = OptimizerUtils.getSparsity(r2, c2, nnz2);
				boolean rsparse = MatrixBlock.evalSparseFormatInMemory(r2, c2, nnz2);
				nflops = 1.0 * ((lsparse ? r1*c1*s1 : r1*c1) + (rsparse ? r2*c2*s2 : r2*c2));
				break;
			}
			
			case SpoofFused:  //spoof
			{
				nflops = 0; //FIXME: this method will be deprecated
				break;
			}

			default:
				throw new DMLRuntimeException("Lineage Cache: unsupported instruction: "+inst.getOpcode());
		}
		
		if (DMLScript.STATISTICS) {
			long t1 = System.nanoTime();
			LineageCacheStatistics.incrementCostingTime(t1-t0);
		}
		return nflops / (2L * 1024 * 1024 * 1024);
	}

	// ---------------- I/O METHODS TO LOCAL FS -----------------
	
	private static void spillToLocalFS(Entry entry) {
		if (!entry.isMatrixValue())
			throw new DMLRuntimeException ("Spilling scalar objects to disk is not allowd. Key: "+entry._key);
		if (entry.isNullVal())
			throw new DMLRuntimeException ("Cannot spill null value to disk. Key: "+entry._key);
		long t0 = DMLScript.STATISTICS ? System.nanoTime() : 0;
		if (_outdir == null) {
			_outdir = LocalFileUtils.getUniqueWorkingDir(LocalFileUtils.CATEGORY_LINEAGE);
			LocalFileUtils.createLocalFileIfNotExist(_outdir);
		}
		//String outfile = _outdir+"/"+_cache.get(_end._key)._key.getId();
		String outfile = _outdir+"/"+entry._key.getId();
		try {
			LocalFileUtils.writeMatrixBlockToLocal(outfile, entry.getMBValue());
		} catch (IOException e) {
			throw new DMLRuntimeException ("Write to " + outfile + " failed.", e);
		}
		if (DMLScript.STATISTICS) {
			long t1 = System.nanoTime();
			LineageCacheStatistics.incrementFSWriteTime(t1-t0);
			LineageCacheStatistics.incrementFSWrites();
		}

		_spillList.put(entry._key, new SpilledItem(outfile, entry._exectime));
	}
	
	private static Entry readFromLocalFS(LineageItem key) {
		long t0 = DMLScript.STATISTICS ? System.nanoTime() : 0;
		MatrixBlock mb = null;
		// Read from local FS
		try {
			mb = LocalFileUtils.readMatrixBlockFromLocal(_spillList.get(key)._outfile);
		} catch (IOException e) {
			throw new DMLRuntimeException ("Read from " + _spillList.get(key)._outfile + " failed.", e);
		}
		/*long t1 = System.nanoTime();
		BigDecimal realReadtime = new BigDecimal(((double)(t1-t0))/1000000);
		System.out.println("actual read time :" + realReadtime.toString());*/

		// Restore to cache
		LocalFileUtils.deleteFileIfExists(_spillList.get(key)._outfile, true);
		putIntern(key, mb, null, _spillList.get(key)._exectime);
		//TODO: set cache status as RELOADED for this entry
		_spillList.remove(key);
		if (DMLScript.STATISTICS) {
			long t1 = System.nanoTime();
			LineageCacheStatistics.incrementFSReadTime(t1-t0);
			LineageCacheStatistics.incrementFSHits();
		}
		return _cache.get(key);
	}

	//------------------ LINKEDLIST MAINTENANCE METHODS -------------------
	
	private static void delete(Entry entry) {
		if (entry._prev != null)
			entry._prev._next = entry._next;
		else
			_head = entry._next;
		if (entry._next != null)
			entry._next._prev = entry._prev;
		else
			_end = entry._prev;
	}
	
	private static void setHead(Entry entry) {
		entry._next = _head;
		entry._prev = null;
		if (_head != null)
			_head._prev = entry;
		_head = entry;
		if (_end == null)
			_end = _head;
	}
	
	private static void setEnd2Head(Entry entry) {
		delete(entry);
		setHead(entry);
	}

	private static void removeEntry(Entry e) {
		if (_cache.remove(e._key) == null)
			return;

		if (DMLScript.STATISTICS)
			_removelist.add(e._key);

		_cachesize -= e.getSize();
		delete(e);
		if (DMLScript.STATISTICS)
			LineageCacheStatistics.incrementMemDeletes();
	}
	
	public static void removeEntry(LineageItem key) {
		// Remove the entry for key
		if (!_cache.containsKey(key))
			return;
		delete(_cache.get(key));
		_cache.remove(key);
	}
	
	static class Entry {
		private final LineageItem _key;
		private MatrixBlock _MBval;
		private ScalarObject _SOval;
		private long _exectime;
		private LineageCacheStatus _status = null;
		private Entry _prev;
		private Entry _next;
		private LineageItem _origItem;
		
		public Entry(LineageItem key, MatrixBlock Mval, ScalarObject Sval, long exectime) {
			_key = key;
			_MBval = Mval;
			_SOval = Sval;
			_exectime = exectime;
			_status = isNullVal() ? LineageCacheStatus.EMPTY : LineageCacheStatus.CACHED;
			_origItem = null;
		}

		public synchronized MatrixBlock getMBValue() {
			try {
				//wait until other thread completes operation
				//in order to avoid redundant computation
				while( _MBval == null ) {
					wait();
				}
				return _MBval;
			}
			catch( InterruptedException ex ) {
				throw new DMLRuntimeException(ex);
			}
		}

		public synchronized ScalarObject getSOValue() {
			try {
				//wait until other thread completes operation
				//in order to avoid redundant computation
				while( _SOval == null ) {
					wait();
				}
				return _SOval;
			}
			catch( InterruptedException ex ) {
				throw new DMLRuntimeException(ex);
			}
		}
		
		public synchronized LineageCacheStatus getCacheStatus() {
			return _status;
		}
		
		public synchronized long getSize() {
			return ((_MBval != null ? _MBval.getInMemorySize() : 0) + (_SOval != null ? _SOval.getSize() : 0));
		}
		
		public boolean isNullVal() {
			return(_MBval == null && _SOval == null);
		}
		
		public boolean isMatrixValue() {
			return(_MBval != null);
		}
		
		public synchronized void setValue(MatrixBlock val, long Exectime) {
			_MBval = val;
			_status = isNullVal() ? LineageCacheStatus.EMPTY : LineageCacheStatus.CACHED;
			_exectime = Exectime;
			notifyAll();
		}

		public synchronized void setValue(MatrixBlock mval, ScalarObject so, long Exectime) {
			_MBval = mval;
			_SOval = so;
			_status = isNullVal() ? LineageCacheStatus.EMPTY : LineageCacheStatus.CACHED;
			_exectime = Exectime;
			notifyAll();
		}

		public synchronized void setValue(MatrixBlock mval, ScalarObject so, 
				LineageCacheStatus st, long Exectime) {
			_MBval = mval;
			_SOval = so;
			_status = st;
			_exectime = Exectime;
			notifyAll();
		}
	}
	
	private static class SpilledItem {
		String _outfile;
		long _exectime;

		public SpilledItem(String outfile, long exectime) {
			this._outfile = outfile;
			this._exectime= exectime;
		}
	}
}
