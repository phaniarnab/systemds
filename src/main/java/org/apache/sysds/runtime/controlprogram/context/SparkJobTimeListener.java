package org.apache.sysds.runtime.controlprogram.context;

import org.apache.spark.scheduler.SparkListener;
import org.apache.spark.scheduler.SparkListenerJobEnd;
import org.apache.spark.scheduler.SparkListenerJobStart;

public class SparkJobTimeListener extends SparkListener
{
	private long startTime;
	private long endTime;

	@Override
	public void onJobStart(SparkListenerJobStart jobStart) {
		startTime = System.nanoTime();
	}

	@Override
	public void onJobEnd(SparkListenerJobEnd jobEnd) {
		endTime = System.nanoTime();
		long runTime = (endTime - startTime)/1000000;
		int jobID = jobEnd.jobId();
		System.out.println("Job " + jobID + " Execution time: " + runTime + " ms");
	}
}
