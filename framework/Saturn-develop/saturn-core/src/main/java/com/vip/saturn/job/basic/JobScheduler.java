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
package com.vip.saturn.job.basic;

import com.vip.saturn.job.exception.JobException;
import com.vip.saturn.job.executor.LimitMaxJobsService;
import com.vip.saturn.job.executor.SaturnExecutorService;
import com.vip.saturn.job.internal.analyse.AnalyseService;
import com.vip.saturn.job.internal.config.ConfigurationService;
import com.vip.saturn.job.internal.config.JobConfiguration;
import com.vip.saturn.job.internal.control.ReportService;
import com.vip.saturn.job.internal.election.LeaderElectionService;
import com.vip.saturn.job.internal.execution.ExecutionContextService;
import com.vip.saturn.job.internal.execution.ExecutionService;
import com.vip.saturn.job.internal.failover.FailoverService;
import com.vip.saturn.job.internal.listener.ListenerManager;
import com.vip.saturn.job.internal.server.ServerService;
import com.vip.saturn.job.internal.sharding.ShardingService;
import com.vip.saturn.job.internal.statistics.StatisticsService;
import com.vip.saturn.job.internal.storage.JobNodeStorage;
import com.vip.saturn.job.reg.base.CoordinatorRegistryCenter;
import com.vip.saturn.job.reg.zookeeper.ZkCacheManager;
import com.vip.saturn.job.threads.ExtendableThreadPoolExecutor;
import com.vip.saturn.job.threads.SaturnThreadFactory;
import com.vip.saturn.job.threads.TaskQueue;
import com.vip.saturn.job.trigger.SaturnScheduler;
import com.vip.saturn.job.utils.LogUtils;
import org.apache.curator.framework.CuratorFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class JobScheduler {
	static Logger log = LoggerFactory.getLogger(JobScheduler.class);

	private String jobName;

	private String executorName;

	/** since all the conf-node values will be gotten from zk-cache. use this to compare with the new values. */

	private JobConfiguration previousConf = new JobConfiguration(null, null);

	private final JobConfiguration currentConf;

	private final CoordinatorRegistryCenter coordinatorRegistryCenter;

	private final ListenerManager listenerManager;

	private final ConfigurationService configService;

	private final LeaderElectionService leaderElectionService;

	private final ServerService serverService;

	private final ReportService reportService;

	private final ShardingService shardingService;

	private final ExecutionContextService executionContextService;

	private final ExecutionService executionService;

	private final FailoverService failoverService;

	private final StatisticsService statisticsService;

	private final AnalyseService analyseService;

	private final LimitMaxJobsService limitMaxJobsService;

	private final JobNodeStorage jobNodeStorage;

	private final ZkCacheManager zkCacheManager;

	private ExecutorService executorService;

	private AbstractElasticJob job;

	private SaturnExecutorService saturnExecutorService;

	private AtomicBoolean isShutdownFlag = new AtomicBoolean(false);

	public JobScheduler(final CoordinatorRegistryCenter coordinatorRegistryCenter,
			final JobConfiguration jobConfiguration) {
		this.jobName = jobConfiguration.getJobName();
		this.executorName = coordinatorRegistryCenter.getExecutorName();
		this.currentConf = jobConfiguration;
		this.coordinatorRegistryCenter = coordinatorRegistryCenter;
		this.jobNodeStorage = new JobNodeStorage(coordinatorRegistryCenter, jobConfiguration);
		initExecutorService();
		JobRegistry.addJobScheduler(executorName, jobName, this);

		zkCacheManager = new ZkCacheManager((CuratorFramework) coordinatorRegistryCenter.getRawClient(), jobName,
				executorName);
		configService = new ConfigurationService(this);
		leaderElectionService = new LeaderElectionService(this);
		serverService = new ServerService(this);
		shardingService = new ShardingService(this);
		executionContextService = new ExecutionContextService(this);
		executionService = new ExecutionService(this);
		failoverService = new FailoverService(this);
		statisticsService = new StatisticsService(this);
		analyseService = new AnalyseService(this);
		limitMaxJobsService = new LimitMaxJobsService(this);
		listenerManager = new ListenerManager(this);
		reportService = new ReportService(this);

		// see EnabledPathListener and CronPathListener, only these values are supposed to be watched.
		previousConf.setTimeZone(jobConfiguration.getTimeZone());
		previousConf.setCron(jobConfiguration.getCron());
		previousConf.setPausePeriodDate(jobConfiguration.getPausePeriodDate());
		previousConf.setPausePeriodTime(jobConfiguration.getPausePeriodTime());
		previousConf.setProcessCountIntervalSeconds(jobConfiguration.getProcessCountIntervalSeconds());
	}

	/**
	 * ???????????????.
	 */
	public void init() {
		try {
			startAll();
			createJob();
			serverService.persistServerOnline(job);
			// Notify job enabled or disabled after that all are ready, include job was initialized.
			configService.notifyJobEnabledOrNot();
		} catch (Throwable t) {
			shutdown(false);
			throw t;
		}
	}

	private void startAll() {
		configService.start();
		leaderElectionService.start();
		serverService.start();
		shardingService.start();
		executionContextService.start();
		executionService.start();
		failoverService.start();
		statisticsService.start();
		limitMaxJobsService.start();
		analyseService.start();

		limitMaxJobsService.check(currentConf.getJobName());
		listenerManager.start();
		leaderElectionService.leaderElection();

		serverService.clearRunOneTimePath();
		serverService.clearStopOneTimePath();
		serverService.resetCount();
		statisticsService.startProcessCountJob();
	}

	private void createJob() {
		try {
			job = JobTypeManager.get(currentConf.getJobType()).getHandlerClass().newInstance();
		} catch (Exception e) {
			LogUtils.error(log, jobName, "unexpected error", e);
			throw new JobException(e);
		}
		job.setJobScheduler(this);
		job.setConfigService(configService);
		job.setShardingService(shardingService);
		job.setExecutionContextService(executionContextService);
		job.setExecutionService(executionService);
		job.setFailoverService(failoverService);
		job.setServerService(serverService);
		job.setExecutorName(executorName);
		job.setReportService(reportService);
		job.setJobName(jobName);
		job.setNamespace(coordinatorRegistryCenter.getNamespace());
		job.setSaturnExecutorService(saturnExecutorService);
		job.init();
	}

	private void initExecutorService() {
		ThreadFactory factory = new SaturnThreadFactory(jobName);
		executorService = new ExtendableThreadPoolExecutor(0, 100, 2, TimeUnit.MINUTES, new TaskQueue(), factory);
	}

	public void reCreateExecutorService() {
		synchronized (isShutdownFlag) {
			if (isShutdownFlag.get()) {
				LogUtils.warn(log, jobName, "the jobScheduler was shutdown, cannot re-create business thread pool");
				return;
			}
			executionService.shutdown();
			initExecutorService();
		}
	}

	public void shutdownExecutorService() {
		if (executorService != null && !executorService.isShutdown()) {
			executorService.shutdown();
		}
	}

	/**
	 * ??????????????????????????????.????????????????????????????????????
	 *
	 * @return ????????????????????????
	 */
	public Date getNextFireTimePausePeriodEffected() {
		try {
			SaturnScheduler saturnScheduler = job.getScheduler();
			return saturnScheduler == null ? null : saturnScheduler.getNextFireTimePausePeriodEffected();
		} catch (Throwable t) {
			LogUtils.error(log, jobName, "fail to get next fire time", t);
			return null;
		}
	}

	/**
	 * ??????????????????.
	 */
	public void triggerJob(String triggeredDataStr) {
		if (job.getScheduler().isShutdown()) {
			return;
		}
		job.getScheduler().trigger(triggeredDataStr);
	}

	/**
	 * ??????process count thread
	 */
	public void shutdownCountThread() {
		statisticsService.shutdown();
	}

	public void shutdown(boolean removeJob) {
		synchronized (isShutdownFlag) {
			isShutdownFlag.set(true);

			// ??????Listener
			listenerManager.shutdown();

			// ?????????????????????????????????????????????
			if (job != null) {
				job.shutdown();
			}

			// ????????????
			shardingService.shutdown();
			configService.shutdown();
			leaderElectionService.shutdown();
			serverService.shutdown();
			executionContextService.shutdown();
			executionService.shutdown();
			failoverService.shutdown();
			statisticsService.shutdown();
			analyseService.shutdown();
			limitMaxJobsService.shutdown();

			// ??????TreeCache
			zkCacheManager.shutdown();

			// ????????????
			if (removeJob) {
				jobNodeStorage.deleteJobNode();
				saturnExecutorService.removeJobName(jobName);
			}

			// ?????????????????????
			JobRegistry.clearJob(executorName, jobName);
		}
	}

	/**
	 * ???????????????Trigger
	 */
	public void reInitializeTrigger() {
		job.getScheduler().reInitializeTrigger();
	}

	/**
	 * ???????????????????????????????????????
	 */
	public void rescheduleProcessCountJob() {
		statisticsService.startProcessCountJob();
	}

	public boolean isAllowedShutdownGracefully() {
		return JobTypeManager.get(currentConf.getJobType()).isAllowedShutdownGracefully();
	}

	public String getJobName() {
		return jobName;
	}

	public void setJobName(String jobName) {
		this.jobName = jobName;
	}

	public String getExecutorName() {
		return executorName;
	}

	public void setExecutorName(String executorName) {
		this.executorName = executorName;
	}

	public JobConfiguration getPreviousConf() {
		return previousConf;
	}

	public void setPreviousConf(JobConfiguration previousConf) {
		this.previousConf = previousConf;
	}

	public AbstractElasticJob getJob() {
		return job;
	}

	public void setJob(AbstractElasticJob job) {
		this.job = job;
	}

	public SaturnExecutorService getSaturnExecutorService() {
		return saturnExecutorService;
	}

	public void setSaturnExecutorService(SaturnExecutorService saturnExecutorService) {
		this.saturnExecutorService = saturnExecutorService;
	}

	public JobConfiguration getCurrentConf() {
		return currentConf;
	}

	public CoordinatorRegistryCenter getCoordinatorRegistryCenter() {
		return coordinatorRegistryCenter;
	}

	public ListenerManager getListenerManager() {
		return listenerManager;
	}

	public ConfigurationService getConfigService() {
		return configService;
	}

	public ReportService getReportService() {
		return reportService;
	}

	public LeaderElectionService getLeaderElectionService() {
		return leaderElectionService;
	}

	public ServerService getServerService() {
		return serverService;
	}

	public ShardingService getShardingService() {
		return shardingService;
	}

	public ExecutionContextService getExecutionContextService() {
		return executionContextService;
	}

	public ExecutionService getExecutionService() {
		return executionService;
	}

	public FailoverService getFailoverService() {
		return failoverService;
	}

	public StatisticsService getStatisticsService() {
		return statisticsService;
	}

	public AnalyseService getAnalyseService() {
		return analyseService;
	}

	public LimitMaxJobsService getLimitMaxJobsService() {
		return limitMaxJobsService;
	}

	public JobNodeStorage getJobNodeStorage() {
		return jobNodeStorage;
	}

	public ZkCacheManager getZkCacheManager() {
		return zkCacheManager;
	}

	public ExecutorService getExecutorService() {
		return executorService;
	}

}
