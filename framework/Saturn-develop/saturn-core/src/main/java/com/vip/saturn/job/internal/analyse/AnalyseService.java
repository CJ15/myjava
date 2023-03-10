/**
 * Copyright 1999-2015 dangdang.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package com.vip.saturn.job.internal.analyse;

import com.vip.saturn.job.basic.AbstractSaturnService;
import com.vip.saturn.job.basic.JobScheduler;
import com.vip.saturn.job.internal.statistics.ProcessCountStatistics;

/**
 * 作业服务器节点统计服务.
 */
public class AnalyseService extends AbstractSaturnService {

	public AnalyseService(final JobScheduler jobScheduler) {
		super(jobScheduler);

	}

	@Override
	public void start() {
		super.start();
		initTotalCount();
		initErrorCount();
	}

	private void initTotalCount() {
		// init total count.
		Integer totalCount = 0;
		if (!getJobNodeStorage().isJobNodeExisted(AnalyseNode.PROCESS_COUNT)) {
			getJobNodeStorage().createOrUpdateJobNodeWithValue(AnalyseNode.PROCESS_COUNT, totalCount.toString());
		}
		ProcessCountStatistics.initTotalCountDelta(executorName, jobName, 0);
	}

	private void initErrorCount() {
		// init error count.
		Integer errorCount = 0;
		if (!getJobNodeStorage().isJobNodeExisted(AnalyseNode.ERROR_COUNT)) {
			getJobNodeStorage().createOrUpdateJobNodeWithValue(AnalyseNode.ERROR_COUNT, errorCount.toString());
		}
		ProcessCountStatistics.initErrorCountDelta(executorName, jobName, 0);
	}

	public synchronized void persistTotalCount() {
		int delta = ProcessCountStatistics.getTotalCountDelta(executorName, jobName);
		if (delta > 0) {
			int totalCount = Integer.parseInt(getJobNodeStorage().getJobNodeData(AnalyseNode.PROCESS_COUNT));
			getJobNodeStorage().updateJobNode(AnalyseNode.PROCESS_COUNT, totalCount + delta);
			ProcessCountStatistics.initTotalCountDelta(executorName, jobName, 0);
		}
	}

	public synchronized void persistErrorCount() {
		int delta = ProcessCountStatistics.getErrorCountDelta(executorName, jobName);
		if (delta > 0) {
			int errorCount = Integer.parseInt(getJobNodeStorage().getJobNodeData(AnalyseNode.ERROR_COUNT));
			getJobNodeStorage().updateJobNode(AnalyseNode.ERROR_COUNT, delta + errorCount);
			ProcessCountStatistics.initErrorCountDelta(executorName, jobName, 0);
		}
	}

}
