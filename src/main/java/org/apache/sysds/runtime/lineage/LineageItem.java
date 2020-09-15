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

import java.util.Stack;

import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.parfor.util.IDSequence;
import org.apache.sysds.runtime.util.UtilFunctions;

public class LineageItem {
	private static IDSequence _idSeq = new IDSequence();
	
	private final long _id;
	private final String _opcode;
	private final String _data;
	private final LineageItem[] _inputs;
	private final LineageItem _dedupPatchDag;
	private int _hash = 0;
	// init visited to true to ensure visited items are
	// not hidden when used as inputs to new items
	private boolean _visited = true;
	
	public enum LineageItemType {Literal, Creation, Instruction, Dedup}
	public static final String dedupItemOpcode = "dedup";
	
	public LineageItem() {
		this("");
	}
	
	public LineageItem(String data) {
		this(_idSeq.getNextID(), data);
	}

	public LineageItem(long id, String data) {
		this(id, data, "", null);
	}
	
	public LineageItem(String data, String opcode) {
		this(_idSeq.getNextID(), data, opcode, null);
	}
	
	public LineageItem(String opcode, LineageItem[] inputs) { 
		this(_idSeq.getNextID(), "", opcode, inputs);
	}

	public LineageItem(String opcode, LineageItem[] inputs, LineageItem dedupDag) { 
		this(_idSeq.getNextID(), "", opcode, inputs, dedupDag);
	}

	public LineageItem(String data, String opcode, LineageItem[] inputs) {
		this(_idSeq.getNextID(), data, opcode, inputs);
	}
	
	public LineageItem(LineageItem li) {
		this(_idSeq.getNextID(), li);
	}
	
	public LineageItem(long id, LineageItem li) {
		this(id, li._data, li._opcode, li._inputs);
	}

	public LineageItem(long id, String data, String opcode) {
		this(id, data, opcode, null);
	}

	public LineageItem(long id, String data, String opcode, LineageItem[] inputs) {
		this(id, data, opcode, inputs, null);
	}
	
	public LineageItem(long id, String data, String opcode, LineageItem[] inputs, LineageItem patchRoot) {
		_id = id;
		_opcode = opcode;
		_data = data;
		_inputs = inputs;
		_dedupPatchDag = patchRoot;
		// materialize hash on construction 
		// (constant time operation if input hashes constructed)
		_hash = hashCode();
	}
	
	public LineageItem[] getInputs() {
		return _inputs;
	}
	
	public void setInput(int i, LineageItem item) {
		_inputs[i] = item;
		_hash = 0; //reset hash
	}
	
	public String getData() {
		return _data;
	}
	
	public boolean isVisited() {
		return _visited;
	}
	
	public void setVisited() {
		setVisited(true);
	}
	
	public void setVisited(boolean flag) {
		_visited = flag;
	}
	
	public long getId() {
		return _id;
	}
	
	public String getOpcode() {
		return _opcode;
	}
	
	public LineageItemType getType() {
		if (_opcode.startsWith(dedupItemOpcode))
			return LineageItemType.Dedup;
		if (isLeaf() && isInstruction())
			return LineageItemType.Creation;
		else if (isLeaf() && !isInstruction())
			return LineageItemType.Literal;
		else if (!isLeaf() && isInstruction())
			return LineageItemType.Instruction;
		else
			throw new DMLRuntimeException("An inner node could not be a literal!");
	}
	
	public boolean isPlaceholder() {
		return _opcode.startsWith(LineageItemUtils.LPLACEHOLDER);
	}
	
	@Override
	public String toString() {
		return LineageItemUtils.explainSingleLineageItem(this);
	}
	
	@Override
	public boolean equals(Object o) {
		if (!(o instanceof LineageItem))
			return false;
		
		resetVisitStatusNR();
		boolean ret = equalsLI((LineageItem) o);
		resetVisitStatusNR();
		return ret;
	}
	
	private boolean equalsLI(LineageItem that) {
		if (isVisited() || this == that)
			return true; 
		
		// For dedup items, compare the patch DAG
		LineageItem left = this._dedupPatchDag != null ? this._dedupPatchDag : this;
		LineageItem right = that._dedupPatchDag != null ? that._dedupPatchDag : that;
		// NOTE: It's not needed to come back to the main DAG as the _dedupPatchDag 
		// is not cut at the placeholders and contains all nodes.
		
		boolean ret = left._opcode.equals(right._opcode);
		ret &= left._data.equals(right._data);
		ret &= (left.hashCode() == right.hashCode());
		if( ret && left._inputs != null && left._inputs.length == right._inputs.length )
			for (int i = 0; i < left._inputs.length; i++) {
				// Skip the placeholders
				LineageItem leftInput = left._inputs[i].isPlaceholder() ? 
						left._inputs[i].getInputs()[0]:left._inputs[i];
				LineageItem rightInput = right._inputs[i].isPlaceholder() ? 
						right._inputs[i].getInputs()[0] : right._inputs[i];
				ret &= leftInput.equalsLI(rightInput);
				if (left._inputs[i].isPlaceholder()) left._inputs[i].setVisited();
			}
		
		left.setVisited();
		setVisited();
		return ret;
	}
	
	@Override
	public int hashCode() {
		if (_hash == 0) {
			//compute hash over opcode and all inputs
			if (isPlaceholder()) {
				//placeholder item has the same hash as its input
				_hash = _inputs[0].hashCode();
				return _hash;
			}
			if (_dedupPatchDag != null) {
				//dedup item has the same hash as patch DAG root
				_hash = _dedupPatchDag._hash;
				return _hash;
			}

			int h = UtilFunctions.intHashCode(
				_opcode.hashCode(), _data.hashCode());
			if (_inputs != null)
				for (LineageItem li : _inputs)
					h = UtilFunctions.intHashCodeRobust(li.hashCode(), h);
			_hash = h;
		}
		return _hash;
	}

	public LineageItem deepCopy() { //bottom-up
		if (isLeaf())
			return new LineageItem(this);
		
		LineageItem[] copyInputs = new LineageItem[getInputs().length];
		for (int i=0; i<_inputs.length; i++) 
			copyInputs[i] = _inputs[i].deepCopy();
		return new LineageItem(_opcode, copyInputs);
	}
	
	public boolean isLeaf() {
		return _inputs == null || _inputs.length == 0;
	}
	
	public boolean isInstruction() {
		return !_opcode.isEmpty();
	}
	
	public boolean isDedup() {
		return _opcode.startsWith(dedupItemOpcode);
	}
	
	/**
	 * Non-recursive equivalent of {@link #resetVisitStatus()} 
	 * for robustness with regard to stack overflow errors.
	 */
	public void resetVisitStatusNR() {
		Stack<LineageItem> q = new Stack<>();
		q.push(this);
		while( !q.empty() ) {
			LineageItem tmp = q.pop();
			if( !tmp.isVisited() && !tmp.isPlaceholder() )
				// skip the placeholders
				continue;
			if (tmp.isDedup() && tmp._dedupPatchDag != null)
				tmp._dedupPatchDag.resetVisitStatusNR();
			if (tmp.getInputs() != null)
				for (LineageItem li : tmp.getInputs())
					q.push(li);
			tmp.setVisited(false);
		}
	}
	
	/**
	 * Non-recursive equivalent of {@link #resetVisitStatus(LineageItem[])} 
	 * for robustness with regard to stack overflow errors.
	 * 
	 * @param lis root lineage items
	 */
	public static void resetVisitStatusNR(LineageItem[] lis) {
		if (lis != null)
			for (LineageItem liRoot : lis)
				liRoot.resetVisitStatusNR();
	}
	
	@Deprecated
	public void resetVisitStatus() {
		if (!isVisited())
			return;
		if (_inputs != null)
			for (LineageItem li : getInputs())
				li.resetVisitStatus();
		setVisited(false);
	}
	
	@Deprecated
	public static void resetVisitStatus(LineageItem[] lis) {
		if (lis != null)
			for (LineageItem liRoot : lis)
				liRoot.resetVisitStatus();
	}
	
	public static void resetIDSequence() {
		_idSeq.reset(-1);
	}
}
