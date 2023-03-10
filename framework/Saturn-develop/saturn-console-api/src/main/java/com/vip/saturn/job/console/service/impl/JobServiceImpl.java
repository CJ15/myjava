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

package com.vip.saturn.job.console.service.impl;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.vip.saturn.job.console.domain.*;
import com.vip.saturn.job.console.domain.ExecutionInfo.ExecutionStatus;
import com.vip.saturn.job.console.exception.SaturnJobConsoleException;
import com.vip.saturn.job.console.exception.SaturnJobConsoleHttpException;
import com.vip.saturn.job.console.mybatis.entity.JobConfig4DB;
import com.vip.saturn.job.console.mybatis.service.CurrentJobConfigService;
import com.vip.saturn.job.console.repository.zookeeper.CuratorRepository;
import com.vip.saturn.job.console.repository.zookeeper.CuratorRepository.CuratorFrameworkOp;
import com.vip.saturn.job.console.service.JobService;
import com.vip.saturn.job.console.service.RegistryCenterService;
import com.vip.saturn.job.console.service.SystemConfigService;
import com.vip.saturn.job.console.service.helper.SystemConfigProperties;
import com.vip.saturn.job.console.utils.*;
import com.vip.saturn.job.console.vo.GetJobConfigVo;
import com.vip.saturn.job.sharding.node.SaturnExecutorsNode;
import jxl.Cell;
import jxl.CellType;
import jxl.Sheet;
import jxl.Workbook;
import jxl.write.*;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.zookeeper.data.Stat;
import org.codehaus.jackson.map.type.MapType;
import org.codehaus.jackson.map.type.TypeFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.File;
import java.lang.Boolean;
import java.text.ParseException;
import java.util.*;
import java.util.regex.Pattern;

import static com.vip.saturn.job.console.exception.SaturnJobConsoleException.ERROR_CODE_BAD_REQUEST;
import static com.vip.saturn.job.console.exception.SaturnJobConsoleException.ERROR_CODE_NOT_EXISTED;

public class JobServiceImpl implements JobService {

	public static final String CONFIG_ITEM_LOAD_LEVEL = "loadLevel";
	public static final String CONFIG_ITEM_ENABLED = "enabled";
	public static final String CONFIG_ITEM_DESCRIPTION = "description";
	public static final String CONFIG_ITEM_CUSTOM_CONTEXT = "customContext";
	public static final String CONFIG_ITEM_JOB_TYPE = "jobType";
	public static final String CONFIG_ITEM_JOB_MODE = "jobMode";
	public static final String CONFIG_ITEM_SHARDING_ITEM_PARAMETERS = "shardingItemParameters";
	public static final String CONFIG_ITEM_JOB_PARAMETER = "jobParameter";
	public static final String CONFIG_ITEM_QUEUE_NAME = "queueName";
	public static final String CONFIG_ITEM_CHANNEL_NAME = "channelName";
	public static final String CONFIG_ITEM_FAILOVER = "failover";
	public static final String CONFIG_ITEM_MONITOR_EXECUTION = "monitorExecution";
	public static final String CONFIG_ITEM_TIMEOUT_4_ALARM_SECONDS = "timeout4AlarmSeconds";
	public static final String CONFIG_ITEM_TIMEOUT_SECONDS = "timeoutSeconds";
	public static final String CONFIG_DISABLE_TIMEOUT_SECONDS = "disableTimeoutSeconds";
	public static final String CONFIG_ITEM_TIME_ZONE = "timeZone";
	public static final String CONFIG_ITEM_CRON = "cron";
	public static final String CONFIG_ITEM_PAUSE_PERIOD_DATE = "pausePeriodDate";
	public static final String CONFIG_ITEM_PAUSE_PERIOD_TIME = "pausePeriodTime";
	public static final String CONFIG_ITEM_PROCESS_COUNT_INTERVAL_SECONDS = "processCountIntervalSeconds";
	public static final String CONFIG_ITEM_SHARDING_TOTAL_COUNT = "shardingTotalCount";
	public static final String CONFIG_ITEM_SHOW_NORMAL_LOG = "showNormalLog";
	public static final String CONFIG_ITEM_JOB_DEGREE = "jobDegree";
	public static final String CONFIG_ITEM_ENABLED_REPORT = "enabledReport";
	public static final String CONFIG_ITEM_PREFER_LIST = "preferList";
	public static final String CONFIG_ITEM_USE_DISPREFER_LIST = "useDispreferList";
	public static final String CONFIG_ITEM_LOCAL_MODE = "localMode";
	public static final String CONFIG_ITEM_USE_SERIAL = "useSerial";
	public static final String CONFIG_ITEM_DEPENDENCIES = "dependencies";
	public static final String CONFIG_ITEM_GROUPS = "groups";
	public static final String CONFIG_ITEM_JOB_CLASS = "jobClass";
	public static final String CONFIG_ITEM_RERUN = "rerun";
	public static final String CONFIG_ITEM_DOWNSTREAM = "downStream";
	public static final String CONFIG_ITEM_UPSTREAM = "upStream";
	private static final Logger log = LoggerFactory.getLogger(JobServiceImpl.class);
	private static final int DEFAULT_MAX_JOB_NUM = 100;
	private static final int DEFAULT_INTERVAL_TIME_OF_ENABLED_REPORT = 5;
	// ?????????????????????job log???zk?????????max jute buffer size
	private static final int DEFAULT_MAX_ZNODE_DATA_LENGTH = 1048576;
	private static final String ERR_MSG_PENDING_STATUS = "job:[{}] item:[{}] on executor:[{}] execution status is "
			+ "PENDING as {}";
	private static final String ERR_MSG_TOO_LONG_TO_DISPLAY = "Not display the log as the length is out of max length";

	@Resource
	private RegistryCenterService registryCenterService;

	@Resource
	private CurrentJobConfigService currentJobConfigService;

	@Resource
	private SystemConfigService systemConfigService;

	private Random random = new Random();

	private MapType customContextType = TypeFactory.defaultInstance().constructMapType(HashMap.class, String.class,
			String.class);

	private JobStatus getJobStatus(final String jobName, CuratorRepository.CuratorFrameworkOp curatorFrameworkOp,
			boolean enabled) {
		// see if all the shards is finished.
		boolean isAllShardsFinished = isAllShardsFinished(jobName, curatorFrameworkOp);
		if (enabled) {
			if (isAllShardsFinished) {
				return JobStatus.READY;
			}
			return JobStatus.RUNNING;
		} else {
			if (isAllShardsFinished) {
				return JobStatus.STOPPED;
			}
			return JobStatus.STOPPING;
		}
	}

	private boolean isAllShardsFinished(final String jobName, CuratorRepository.CuratorFrameworkOp curatorFrameworkOp) {
		List<String> executionItems = curatorFrameworkOp.getChildren(JobNodePath.getExecutionNodePath(jobName));
		boolean isAllShardsFinished = true;
		if (executionItems != null && !executionItems.isEmpty()) {
			for (String itemStr : executionItems) {
				boolean isItemCompleted = curatorFrameworkOp
						.checkExists(JobNodePath.getExecutionNodePath(jobName, itemStr, "completed"));
				boolean isItemRunning = curatorFrameworkOp
						.checkExists(JobNodePath.getExecutionNodePath(jobName, itemStr, "running"));
				// if executor is kill by -9 while it is running, completed node won't exists as
				// well as running node.
				// under this circumstance, we consider it is completed.
				if (!isItemCompleted && isItemRunning) {
					isAllShardsFinished = false;
					break;
				}
			}
		}
		return isAllShardsFinished;
	}

	@Override
	public List<String> getGroups(String namespace) throws SaturnJobConsoleException {
		Set<String> groups = new HashSet<>();
		List<JobConfig> unSystemJobs = getUnSystemJobs(namespace);
		if (unSystemJobs != null) {
			for (JobConfig jobConfig : unSystemJobs) {
				String jobGroups = jobConfig.getGroups();
				if (StringUtils.isBlank(jobGroups)) {
					jobGroups = SaturnConstants.NO_GROUPS_LABEL;
				}
				String[] groupArray = jobGroups.replaceAll("\\s*", "").split(",");
				groups.addAll(Arrays.asList(groupArray));
			}
		}
		ArrayList<String> groupList = new ArrayList<>(groups);
		Collections.sort(groupList);
		return groupList;
	}

	@Transactional(rollbackFor = Exception.class)
	@Override
	public void enableJob(String namespace, String jobName, String updatedBy) throws SaturnJobConsoleException {
		JobConfig4DB jobConfig = currentJobConfigService.findConfigByNamespaceAndJobName(namespace, jobName);
		if (jobConfig == null) {
			throw new SaturnJobConsoleException(ERROR_CODE_NOT_EXISTED, "????????????????????????" + jobName + "??????????????????????????????");
		}
		if (jobConfig.getEnabled()) {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST, "????????????" + jobName + "???????????????????????????");
		}
		CuratorRepository.CuratorFrameworkOp curatorFrameworkOp = registryCenterService
				.getCuratorFrameworkOp(namespace);
		boolean allShardsFinished = isAllShardsFinished(jobName, curatorFrameworkOp);
		if (!allShardsFinished) {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST, "????????????????????????" + jobName + "??????????????????????????????STOPPED??????");
		}
		jobConfig.setEnabled(true);
		jobConfig.setLastUpdateTime(new Date());
		jobConfig.setLastUpdateBy(updatedBy);
		currentJobConfigService.updateByPrimaryKey(jobConfig);
		curatorFrameworkOp.update(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_ENABLED), true);
	}

	@Transactional(rollbackFor = Exception.class)
	@Override
	public void disableJob(String namespace, String jobName, String updatedBy) throws SaturnJobConsoleException {
		JobConfig4DB jobConfig = currentJobConfigService.findConfigByNamespaceAndJobName(namespace, jobName);
		if (jobConfig == null) {
			throw new SaturnJobConsoleException(ERROR_CODE_NOT_EXISTED, "????????????????????????" + jobName + "??????????????????????????????");
		}
		if (!jobConfig.getEnabled()) {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST, "????????????" + jobName + "???????????????????????????");
		}
		jobConfig.setEnabled(Boolean.FALSE);
		jobConfig.setLastUpdateTime(new Date());
		jobConfig.setLastUpdateBy(updatedBy);
		currentJobConfigService.updateByPrimaryKey(jobConfig);
		CuratorRepository.CuratorFrameworkOp curatorFrameworkOp = registryCenterService
				.getCuratorFrameworkOp(namespace);
		curatorFrameworkOp.update(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_ENABLED), false);
	}

	@Transactional(rollbackFor = Exception.class)
	@Override
	public void removeJob(String namespace, String jobName) throws SaturnJobConsoleException {
		JobConfig4DB jobConfig4DB = currentJobConfigService.findConfigByNamespaceAndJobName(namespace, jobName);
		if (jobConfig4DB == null) {
			throw new SaturnJobConsoleException(ERROR_CODE_NOT_EXISTED, "????????????????????????" + jobName + "??????????????????????????????");
		}
		String upStream = jobConfig4DB.getUpStream();
		if (StringUtils.isNotBlank(upStream)) {
			throw new SaturnJobConsoleException(ERROR_CODE_NOT_EXISTED,
					"????????????????????????" + jobName + "??????????????????????????????????????????" + upStream + "??????????????????????????????????????????");
		}
		String downStream = jobConfig4DB.getDownStream();
		if (StringUtils.isNotBlank(downStream)) {
			throw new SaturnJobConsoleException(ERROR_CODE_NOT_EXISTED,
					"????????????????????????" + jobName + "??????????????????????????????????????????" + downStream + "??????????????????????????????????????????");
		}
		CuratorRepository.CuratorFrameworkOp curatorFrameworkOp = registryCenterService
				.getCuratorFrameworkOp(namespace);
		JobStatus jobStatus = getJobStatus(jobName, curatorFrameworkOp, jobConfig4DB.getEnabled());

		if (JobStatus.STOPPED != jobStatus) {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
					String.format("?????????????????????(%s)???????????????????????????STOPPED??????", jobName));
		}

		Stat stat = curatorFrameworkOp.getStat(JobNodePath.getJobNodePath(jobName));
		if (stat != null) {
			long createTimeDiff = System.currentTimeMillis() - stat.getCtime();
			if (createTimeDiff < SaturnConstants.JOB_CAN_BE_DELETE_TIME_LIMIT) {
				throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
						String.format("?????????????????????(%s)???????????????????????????????????????????????????%d??????", jobName,
								SaturnConstants.JOB_CAN_BE_DELETE_TIME_LIMIT / 60000));
			}
		}
		// remove job from db
		currentJobConfigService.deleteByPrimaryKey(jobConfig4DB.getId());
		// remove job from zk
		removeJobFromZk(jobName, curatorFrameworkOp);
	}

	/**
	 * ??????zk?????????????????????????????????config/toDelete????????????executor??????????????????shutdown?????????????????????????????????executor?????????shutdown???????????????????????????????????????????????????
	 * 
	 * @return ??????executor shutdown???????????????????????????????????????executor????????????shutdown???????????????????????????false???
	 *         ??????????????????????????????executor???shutdown??????????????????????????????????????????true???
	 */
	private boolean removeJobFromZk(String jobName, CuratorRepository.CuratorFrameworkOp curatorFrameworkOp)
			throws SaturnJobConsoleException {
		// 1.?????????executor???online??????????????????toDelete??????????????????????????????????????????
		String toDeleteNodePath = JobNodePath.getConfigNodePath(jobName, "toDelete");
		if (curatorFrameworkOp.checkExists(toDeleteNodePath)) {
			curatorFrameworkOp.deleteRecursive(toDeleteNodePath);
		}
		curatorFrameworkOp.create(toDeleteNodePath);

		for (int i = 0; i < 20; i++) {
			// 2.?????????executor???offline????????????????????????online?????????offline?????????
			String jobServerPath = JobNodePath.getServerNodePath(jobName);
			if (!curatorFrameworkOp.checkExists(jobServerPath)) {
				// (1)???????????????$Job/JobName/servers????????????????????????????????????executor????????????????????????????????????
				curatorFrameworkOp.deleteRecursive(JobNodePath.getJobNodePath(jobName));
				return true;
			}
			// (2)???????????????servers???????????????executor??????????????????????????????
			List<String> executors = curatorFrameworkOp.getChildren(jobServerPath);
			if (CollectionUtils.isEmpty(executors)) {
				curatorFrameworkOp.deleteRecursive(JobNodePath.getJobNodePath(jobName));
				return true;
			}
			// (3)???????????????????????????????????????????????????executor???????????????????????????????????????
			boolean hasOnlineExecutor = false;
			for (String executor : executors) {
				if (curatorFrameworkOp.checkExists(ExecutorNodePath.getExecutorNodePath(executor, "ip"))
						&& curatorFrameworkOp.checkExists(JobNodePath.getServerStatus(jobName, executor))) {
					hasOnlineExecutor = true;
				} else {
					curatorFrameworkOp.deleteRecursive(JobNodePath.getServerNodePath(jobName, executor));
				}
			}
			if (!hasOnlineExecutor) {
				curatorFrameworkOp.deleteRecursive(JobNodePath.getJobNodePath(jobName));
				return true;
			}
			try {
				Thread.sleep(200);
			} catch (Exception e) {
				throw new SaturnJobConsoleException(e);
			}
		}

		return false;
	}

	@Override
	public List<ExecutorProvided> getCandidateExecutors(String namespace, String jobName)
			throws SaturnJobConsoleException {
		JobConfig4DB currentJobConfig = currentJobConfigService.findConfigByNamespaceAndJobName(namespace, jobName);
		if (currentJobConfig == null) {
			throw new SaturnJobConsoleException(ERROR_CODE_NOT_EXISTED,
					"????????????????????????" + jobName + "?????????????????????Executor???????????????????????????");
		}
		List<ExecutorProvided> executorProvidedList = new ArrayList<>();
		CuratorRepository.CuratorFrameworkOp curatorFrameworkOp = registryCenterService
				.getCuratorFrameworkOp(namespace);
		String executorsNodePath = SaturnExecutorsNode.getExecutorsNodePath();
		if (!curatorFrameworkOp.checkExists(executorsNodePath)) {
			return executorProvidedList;
		}
		List<String> executors = curatorFrameworkOp.getChildren(executorsNodePath);
		if (executors == null) {
			executors = new ArrayList<>();
		}
		if (!executors.isEmpty()) {
			for (String executor : executors) {
				if (curatorFrameworkOp.checkExists(SaturnExecutorsNode.getExecutorTaskNodePath(executor))) {
					continue;// ??????????????????Executor????????????????????????????????????taskId??????
				}
				ExecutorProvided executorProvided = new ExecutorProvided();
				executorProvided.setType(ExecutorProvidedType.PHYSICAL);
				executorProvided.setExecutorName(executor);
				executorProvided.setNoTraffic(
						curatorFrameworkOp.checkExists(SaturnExecutorsNode.getExecutorNoTrafficNodePath(executor)));
				String ip = curatorFrameworkOp.getData(SaturnExecutorsNode.getExecutorIpNodePath(executor));
				if (StringUtils.isNotBlank(ip)) {
					executorProvided.setStatus(ExecutorProvidedStatus.ONLINE);
					executorProvided.setIp(ip);
				} else {
					executorProvided.setStatus(ExecutorProvidedStatus.OFFLINE);
				}
				executorProvidedList.add(executorProvided);
			}
		}

		List<ExecutorProvided> dockerExecutorProvided = getContainerTaskIds(curatorFrameworkOp);
		executorProvidedList.addAll(dockerExecutorProvided);

		if (StringUtils.isBlank(jobName)) {
			return executorProvidedList;
		}

		String preferListNodePath = JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_PREFER_LIST);

		if (!curatorFrameworkOp.checkExists(preferListNodePath)) {
			return executorProvidedList;
		}

		String preferList = curatorFrameworkOp.getData(preferListNodePath);
		if (Strings.isNullOrEmpty(preferList)) {
			return executorProvidedList;
		}

		handlerPreferListString(curatorFrameworkOp, preferList, executors, dockerExecutorProvided,
				executorProvidedList);

		return executorProvidedList;
	}

	private void handlerPreferListString(CuratorRepository.CuratorFrameworkOp curatorFrameworkOp, String preferList,
			List<String> executors, List<ExecutorProvided> dockerExecutorProvided,
			List<ExecutorProvided> executorProvidedList) {
		String[] preferExecutorList = preferList.split(",");
		for (String preferExecutor : preferExecutorList) {
			if (!preferExecutor.startsWith("@")) {
				if (!executors.contains(preferExecutor)) {
					ExecutorProvided executorProvided = new ExecutorProvided();
					executorProvided.setExecutorName(preferExecutor);
					executorProvided.setType(ExecutorProvidedType.PHYSICAL);
					executorProvided.setStatus(ExecutorProvidedStatus.DELETED);
					executorProvided.setNoTraffic(curatorFrameworkOp
							.checkExists(SaturnExecutorsNode.getExecutorNoTrafficNodePath(preferExecutor)));
					executorProvidedList.add(executorProvided);
				}
			} else {
				String executorName = preferExecutor.substring(1);
				boolean include = false;
				for (ExecutorProvided executorProvided : dockerExecutorProvided) {
					if (executorProvided.getExecutorName().equals(executorName)) {
						include = true;
						break;
					}
				}
				if (!include) {
					ExecutorProvided executorProvided = new ExecutorProvided();
					executorProvided.setExecutorName(executorName);
					executorProvided.setType(ExecutorProvidedType.DOCKER);
					executorProvided.setStatus(ExecutorProvidedStatus.DELETED);
					executorProvidedList.add(executorProvided);
				}
			}
		}
	}

	/**
	 * ?????????DCOS????????????taskID?????????????????????????????????????????????executor???????????????;
	 * <p>
	 * ???????????????DCOS???????????????K8S??????????????????
	 */
	protected List<ExecutorProvided> getContainerTaskIds(CuratorRepository.CuratorFrameworkOp curatorFrameworkOp) {
		List<ExecutorProvided> executorProvidedList = new ArrayList<>();

		List<String> containerTaskIds = getDCOSContainerTaskIds(curatorFrameworkOp);
		if (CollectionUtils.isEmpty(containerTaskIds)) {
			containerTaskIds = getK8SContainerTaskIds(curatorFrameworkOp);
		}

		if (!CollectionUtils.isEmpty(containerTaskIds)) {
			for (String task : containerTaskIds) {
				ExecutorProvided executorProvided = new ExecutorProvided();
				executorProvided.setExecutorName(task);
				executorProvided.setType(ExecutorProvidedType.DOCKER);
				executorProvidedList.add(executorProvided);
			}
		}

		return executorProvidedList;
	}

	private List<String> getDCOSContainerTaskIds(CuratorRepository.CuratorFrameworkOp curatorFrameworkOp) {
		List<String> containerTaskIds = Lists.newArrayList();

		String containerNodePath = ContainerNodePath.getDcosTasksNodePath();
		if (curatorFrameworkOp.checkExists(containerNodePath)) {
			containerTaskIds = curatorFrameworkOp.getChildren(containerNodePath);
		}

		return containerTaskIds;
	}

	private List<String> getK8SContainerTaskIds(CuratorRepository.CuratorFrameworkOp curatorFrameworkOp) {
		List<String> taskIds = new ArrayList<>();
		String executorsNodePath = SaturnExecutorsNode.getExecutorsNodePath();
		List<String> executors = curatorFrameworkOp.getChildren(executorsNodePath);
		if (executors != null) {
			for (String executor : executors) {
				String executorTaskNodePath = SaturnExecutorsNode.getExecutorTaskNodePath(executor);
				if (curatorFrameworkOp.checkExists(executorTaskNodePath)) {
					String taskId = curatorFrameworkOp.getData(executorTaskNodePath);
					if (taskId != null && !taskIds.contains(taskId)) {
						taskIds.add(taskId);
					}
				}
			}
		}
		return taskIds;
	}

	@Transactional(rollbackFor = Exception.class)
	@Override
	public void setPreferList(String namespace, String jobName, String preferList, String updatedBy)
			throws SaturnJobConsoleException {
		// save to db
		JobConfig4DB oldJobConfig = currentJobConfigService.findConfigByNamespaceAndJobName(namespace, jobName);
		if (oldJobConfig == null) {
			throw new SaturnJobConsoleException(ERROR_CODE_NOT_EXISTED, "??????????????????" + jobName + "?????????Executor?????????????????????????????????");
		}
		// ????????????????????????????????????????????????preferList
		Boolean enabled = oldJobConfig.getEnabled();
		Boolean localMode = oldJobConfig.getLocalMode();
		if (enabled != null && enabled && localMode != null && localMode) {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
					String.format("?????????????????????????????????(%s)?????????????????????Executor??????????????????", jobName));
		}
		JobConfig4DB newJobConfig = new JobConfig4DB();
		BeanUtils.copyProperties(oldJobConfig, newJobConfig);
		newJobConfig.setPreferList(preferList);
		currentJobConfigService.updateNewAndSaveOld2History(newJobConfig, oldJobConfig, updatedBy);

		// save to zk
		CuratorRepository.CuratorFrameworkOp curatorFrameworkOp = registryCenterService
				.getCuratorFrameworkOp(namespace);
		String jobConfigPreferListNodePath = SaturnExecutorsNode.getJobConfigPreferListNodePath(jobName);
		curatorFrameworkOp.update(jobConfigPreferListNodePath, preferList);
		// delete and create the forceShard node
		String jobConfigForceShardNodePath = SaturnExecutorsNode.getJobConfigForceShardNodePath(jobName);
		curatorFrameworkOp.delete(jobConfigForceShardNodePath);
		curatorFrameworkOp.create(jobConfigForceShardNodePath);
	}

	@Override
	public List<String> getCandidateUpStream(String namespace) throws SaturnJobConsoleException {
		List<String> candidateDownStream = new ArrayList<>();
		List<JobConfig> unSystemJobs = getUnSystemJobs(namespace);
		for (JobConfig temp : unSystemJobs) {
			if (canBeUpStream(temp)) {
				candidateDownStream.add(temp.getJobName());
			}
		}
		Collections.sort(candidateDownStream);
		return candidateDownStream;
	}

	@Override
	public List<String> getCandidateDownStream(String namespace) throws SaturnJobConsoleException {
		List<String> candidateDownStream = new ArrayList<>();
		List<JobConfig> unSystemJobs = getUnSystemJobs(namespace);
		for (JobConfig temp : unSystemJobs) {
			if (canBeDownStream(temp)) {
				candidateDownStream.add(temp.getJobName());
			}
		}
		Collections.sort(candidateDownStream);
		return candidateDownStream;
	}

	private void validateJobConfig(String namespace, JobConfig jobConfig, List<JobConfig> unSystemJobs,
			Set<JobConfig> streamChangedJobs) throws SaturnJobConsoleException {
		// ???????????????
		String jobName = jobConfig.getJobName();
		if (StringUtils.isBlank(jobName)) {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST, "???????????????");
		}
		// ?????????????????????????????????0-9???????????????a-z???????????????A-Z????????????_
		if (!jobName.matches("[0-9a-zA-Z_]*")) {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST, "?????????????????????????????????0-9???????????????a-z???????????????A-Z????????????_");
		}
		// ???????????????????????????????????????0-9???????????????a-z???????????????A-Z????????????_???????????????,
		if (jobConfig.getDependencies() != null && !jobConfig.getDependencies().matches("[0-9a-zA-Z_,]*")) {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST, "???????????????????????????????????????0-9???????????????a-z???????????????A-Z????????????_???????????????,");
		}
		// ??????????????????
		if (StringUtils.isBlank(jobConfig.getJobType())) {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST, "??????????????????");
		}
		// ??????????????????
		JobType jobType = JobType.getJobType(jobConfig.getJobType());
		if (jobType == JobType.UNKNOWN_JOB) {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST, "??????????????????");
		}
		// ?????????JAVA??????????????????????????????
		if (JobType.isJava(jobType) && StringUtils.isBlank(jobConfig.getJobClass())) {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST, "??????java??????????????????????????????");
		}
		// ????????????????????????queue??????
		if (JobType.isMsg(jobType)) {
			validateQueue(jobConfig);
		}
		// ??????cron
		validateCronFieldOfJobConfig(jobConfig);
		// ??????shardingItemParameters
		validateShardingItemFieldOfJobConfig(jobConfig);
		// ????????????
		validateGroupsFieldOfJobConfig(jobConfig);
		// ????????????????????????
		if (jobConfig.getJobMode() != null && jobConfig.getJobMode().startsWith(JobMode.SYSTEM_PREFIX)) {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST, "?????????????????????????????????????????????");
		}
		// ????????????????????????????????????????????????????????????????????????
		validateStreamAndLinkingUpdateOtherJobs(namespace, jobConfig, unSystemJobs, streamChangedJobs);
	}

	protected void validateQueue(JobConfig jobConfig) throws SaturnJobConsoleException {
		if (StringUtils.isBlank(jobConfig.getQueueName())) {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST, "?????????????????????queue??????");
		}
		jobConfig.setQueueName(jobConfig.getQueueName().trim().replaceAll("\r\n", ""));
	}

	private void validateGroupsFieldOfJobConfig(JobConfig jobConfig) throws SaturnJobConsoleException {
		String groups = jobConfig.getGroups();
		if (groups == null) {
			return;
		}
		if (groups.length() > 255) {
			throw new SaturnJobConsoleException("???????????????????????????255?????????");
		}
		Pattern pattern = Pattern.compile("[`!@#$%^???@#???%??????&*??????|{}???????????????????????????????????? ]");
		for (String group : groups.split(",")) {
			validateGroupName(group, pattern);
		}
	}

	private void validateGroupName(String groupName, Pattern pattern) throws SaturnJobConsoleException {
		if ("?????????".equals(groupName.trim())) {
			throw new SaturnJobConsoleException("??????????????????????????????");
		}
		if (pattern.matcher(groupName).find()) {
			throw new SaturnJobConsoleException("????????????????????????????????????");
		}
	}

	private void validateStreamAndLinkingUpdateOtherJobs(String namespace, JobConfig jobConfig,
			List<JobConfig> unSystemJobs, Set<JobConfig> streamChangedJobs) throws SaturnJobConsoleException {
		Set<String> downStream = parseStreamToList(jobConfig.getDownStream());
		if (!downStream.isEmpty()) {
			validateDownStreamBasic(jobConfig, true);
		}
		Set<String> upStream = parseStreamToList(jobConfig.getUpStream());
		if (!upStream.isEmpty()) {
			validateUpStreamBasic(jobConfig, true);
		}
		// ?????????????????????????????????jobConfig???unSystemJobs
		List<JobConfig> newUnSystemJobs = new ArrayList<>();
		newUnSystemJobs.addAll(unSystemJobs);
		boolean included = false;
		for (JobConfig otherJob : newUnSystemJobs) {
			if (otherJob.getJobName().equals(jobConfig.getJobName())) {
				included = true;
				break;
			}
		}
		if (!included) {
			newUnSystemJobs.add(jobConfig);
		}
		// ??????????????????????????????????????????downStream
		validateAndUpdateStream(jobConfig, upStream, newUnSystemJobs, streamChangedJobs, false);
		// ??????????????????????????????????????????upStream
		validateAndUpdateStream(jobConfig, downStream, newUnSystemJobs, streamChangedJobs, true);
		// ??????????????????????????????????????????
		getAncestors(namespace, jobConfig, newUnSystemJobs, new Stack<String>(), true);
		// ??????????????????????????????????????????
		jobConfig.setUpStream(formatStream(upStream));
		// ??????????????????????????????????????????
		jobConfig.setDownStream(formatStream(downStream));
	}

	private Set<String> parseStreamToList(String stream) {
		Set<String> streamList = new HashSet<>();
		if (StringUtils.isBlank(stream)) {
			return streamList;
		}
		String[] split = stream.split(",");
		for (String temp : split) {
			if (StringUtils.isNotBlank(temp)) {
				streamList.add(temp.trim());
			}
		}
		return streamList;
	}

	private void validateDownStreamBasic(JobConfig jobConfig, boolean isCurrentJob) throws SaturnJobConsoleException {
		// ?????????cron/passive???????????????????????????
		JobType jobType = JobType.getJobType(jobConfig.getJobType());
		if (!JobType.isCron(jobType) && !JobType.isPassive(jobType)) {
			if (isCurrentJob) {
				throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST, "??????????????????????????????????????????????????????????????????");
			} else {
				throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
						"?????????????????????(" + jobConfig.getJobName() + ")?????????????????????????????????");
			}
		}
		// ?????????????????????????????????????????????????????????????????????1
		if (jobConfig.getLocalMode() != null && jobConfig.getLocalMode()) {
			if (isCurrentJob) {
				throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST, "????????????????????????????????????????????????");
			} else {
				throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
						"?????????????????????(" + jobConfig.getJobName() + ")???????????????????????????");
			}
		}
		// ?????????????????????????????????????????????
		if (jobConfig.getShardingTotalCount() != 1) {
			if (isCurrentJob) {
				throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST, "????????????1???????????????????????????");
			} else {
				throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
						"?????????????????????(" + jobConfig.getJobName() + ")??????????????????1");
			}
		}
	}

	private void validateUpStreamBasic(JobConfig jobConfig, boolean isCurrentJob) throws SaturnJobConsoleException {
		// ?????????passive???????????????????????????
		JobType jobType = JobType.getJobType(jobConfig.getJobType());
		if (!JobType.isPassive(jobType)) {
			if (isCurrentJob) {
				throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST, "????????????????????????????????????????????????");
			} else {
				throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
						"?????????????????????(" + jobConfig.getJobName() + ")??????????????????");
			}
		}
	}

	private void validateAndUpdateStream(JobConfig jobConfig, Set<String> stream, List<JobConfig> unSystemJobs,
			Set<JobConfig> streamChangedJobs, boolean isDownStream) throws SaturnJobConsoleException {
		String jobName = jobConfig.getJobName();
		for (String elem : stream) {
			if (elem.equals(jobName)) {
				if (isDownStream) {
					throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST, "????????????(" + elem + ")????????????????????????");
				} else {
					throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST, "????????????(" + elem + ")????????????????????????");
				}
			}
			boolean found = false;
			for (JobConfig otherJob : unSystemJobs) {
				if (elem.equals(otherJob.getJobName())) {
					if (isDownStream) {
						validateUpStreamBasic(otherJob, false);
						otherJob.setUpStream(appendToStream(jobName, otherJob.getUpStream()));
						streamChangedJobs.add(otherJob);
					} else {
						validateDownStreamBasic(otherJob, false);
						otherJob.setDownStream(appendToStream(jobName, otherJob.getDownStream()));
						streamChangedJobs.add(otherJob);
					}
					found = true;
					break;
				}
			}
			if (!found) {
				if (isDownStream) {
					throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST, "????????????(" + elem + ")?????????");
				} else {
					throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST, "????????????(" + elem + ")?????????");
				}
			}
		}
		for (JobConfig otherJob : unSystemJobs) {
			String otherJobName = otherJob.getJobName();
			if (otherJobName.equals(jobName)) {
				continue;
			}
			if (stream.contains(otherJobName)) {
				continue;
			}
			if (isDownStream) {
				String upStream = removeFromStreamIfNecessary(jobName, otherJob.getUpStream());
				if (upStream != null) {
					otherJob.setUpStream(upStream);
					streamChangedJobs.add(otherJob);
				}
			} else {
				String downStream = removeFromStreamIfNecessary(jobName, otherJob.getDownStream());
				if (downStream != null) {
					otherJob.setDownStream(downStream);
					streamChangedJobs.add(otherJob);
				}
			}
		}
	}

	private String appendToStream(String jobName, String stream) {
		Set<String> streamSet = parseStreamToList(stream);
		if (StringUtils.isNotBlank(jobName)) {
			streamSet.add(jobName);
		}
		return formatStream(streamSet);
	}

	private String removeFromStreamIfNecessary(String jobName, String stream) {
		Set<String> streamSet = parseStreamToList(stream);
		if (StringUtils.isNotBlank(jobName)) {
			if (streamSet.remove(jobName)) {
				return formatStream(streamSet);
			}
		}
		return null;
	}

	private String formatStream(Set<String> streamSet) {
		StringBuilder sb = new StringBuilder();
		for (String temp : streamSet) {
			if (StringUtils.isNotBlank(temp)) {
				sb.append(temp).append(',');
			}
		}
		int length = sb.length();
		if (length > 0) {
			sb.deleteCharAt(sb.length() - 1);
		}
		return sb.toString();
	}

	private Set<String> getAncestors(String namespace, JobConfig jobConfig, List<JobConfig> unSystemJobs,
			Stack<String> onePathRecords, boolean throwExceptionWhenHasARing) throws SaturnJobConsoleException {
		onePathRecords.push(jobConfig.getJobName());
		Set<String> ancestors = new HashSet<>();
		Set<String> upStream = parseStreamToList(jobConfig.getUpStream());
		for (String parent : upStream) {
			for (JobConfig otherJobConfig : unSystemJobs) {
				if (parent.equals(otherJobConfig.getJobName())) {
					if (onePathRecords.search(parent) != -1) {
						onePathRecords.push(parent);
						if (throwExceptionWhenHasARing) {
							throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
									String.format("??????(%s)??????????????????????????????: %s", namespace, onePathRecords));
						} else {
							log.error("{} job arrange error, because it includes a ring: {}", namespace,
									onePathRecords);
							onePathRecords.pop();
						}
					}
					if (!ancestors.contains(parent)) {
						ancestors.add(parent);
						ancestors.addAll(getAncestors(namespace, otherJobConfig, unSystemJobs, onePathRecords,
								throwExceptionWhenHasARing));
					}
					break;
				}
			}
		}
		onePathRecords.pop();
		return ancestors;
	}

	private Set<String> getDescendants(String namespace, JobConfig jobConfig, List<JobConfig> unSystemJobs,
			Stack<String> onePathRecords, boolean throwExceptionWhenHasARing) throws SaturnJobConsoleException {
		onePathRecords.push(jobConfig.getJobName());
		Set<String> descendants = new HashSet<>();
		Set<String> downStream = parseStreamToList(jobConfig.getDownStream());
		for (String child : downStream) {
			for (JobConfig otherJobConfig : unSystemJobs) {
				if (child.equals(otherJobConfig.getJobName())) {
					if (onePathRecords.search(child) != -1) {
						onePathRecords.push(child);
						if (throwExceptionWhenHasARing) {
							throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
									String.format("??????(%s)??????????????????????????????: %", namespace, onePathRecords));
						} else {
							log.error("{} job arrange error, because it includes a ring: {}", namespace,
									onePathRecords);
							onePathRecords.pop();
						}
					}
					if (!descendants.contains(child)) {
						descendants.add(child);
						descendants.addAll(getDescendants(namespace, otherJobConfig, unSystemJobs, onePathRecords,
								throwExceptionWhenHasARing));
					}
					break;
				}
			}
		}
		onePathRecords.pop();
		return descendants;
	}

	private void validateCronFieldOfJobConfig(JobConfig jobConfig) throws SaturnJobConsoleException {
		if (JobType.isCron(JobType.getJobType(jobConfig.getJobType()))) {
			// cron???????????????
			if (jobConfig.getCron() == null || jobConfig.getCron().trim().isEmpty()) {
				throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST, "??????cron?????????cron???????????????");
			}
			// cron?????????????????????
			try {
				CronExpression.validateExpression(jobConfig.getCron());
			} catch (ParseException e) {
				throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST, "cron?????????????????????" + e);
			}
		} else {
			jobConfig.setCron(""); // ???????????????????????????????????????cron?????????
		}
	}

	private void validateShardingItemFieldOfJobConfig(JobConfig jobConfig) throws SaturnJobConsoleException {
		if (jobConfig.getLocalMode() != null && jobConfig.getLocalMode()) {
			if (jobConfig.getShardingItemParameters() == null) {
				throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST, "????????????????????????????????????????????????");
			} else {
				String[] split = jobConfig.getShardingItemParameters().split(",");
				boolean includeXing = false;
				for (String tmp : split) {
					String[] split2 = tmp.split("=");
					if ("*".equalsIgnoreCase(split2[0].trim())) {
						includeXing = true;
						break;
					}
				}
				if (!includeXing) {
					throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST, "??????????????????????????????????????????????????????*=xx???");
				}
			}
		} else {
			// ????????????????????????????????????
			if (jobConfig.getShardingTotalCount() == null || jobConfig.getShardingTotalCount() < 1) {
				throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST, "??????????????????????????????????????????1");
			}
			if ((jobConfig.getShardingTotalCount() > 0) && (jobConfig.getShardingItemParameters() == null
					|| jobConfig.getShardingItemParameters().trim().isEmpty()
					|| jobConfig.getShardingItemParameters().split(",").length < jobConfig.getShardingTotalCount())) {
				throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST, "????????????????????????????????????");
			}
			validateShardingItemFormat(jobConfig);
		}
	}

	@Transactional(rollbackFor = Exception.class)
	@Override
	public void addJob(String namespace, JobConfig jobConfig, String createdBy) throws SaturnJobConsoleException {
		addOrCopyJob(namespace, jobConfig, null, createdBy);
	}

	@Transactional(rollbackFor = Exception.class)
	@Override
	public void copyJob(String namespace, JobConfig jobConfig, String jobNameCopied, String createdBy)
			throws SaturnJobConsoleException {
		addOrCopyJob(namespace, jobConfig, jobNameCopied, createdBy);
	}

	private void addOrCopyJob(String namespace, JobConfig jobConfig, String jobNameCopied, String createdBy)
			throws SaturnJobConsoleException {
		List<JobConfig> unSystemJobs = getUnSystemJobs(namespace);
		Set<JobConfig> streamChangedJobs = new HashSet<>();
		validateJobConfig(namespace, jobConfig, unSystemJobs, streamChangedJobs);
		// ????????????????????????????????????????????????
		// ??????????????????????????????unSystemJobs??????????????????????????????????????????
		String jobName = jobConfig.getJobName();
		if (currentJobConfigService.findConfigByNamespaceAndJobName(namespace, jobName) != null) {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST, String.format("?????????(%s)????????????", jobName));
		}
		// ??????zk?????????????????????????????????
		CuratorRepository.CuratorFrameworkOp curatorFrameworkOp = registryCenterService
				.getCuratorFrameworkOp(namespace);
		if (curatorFrameworkOp.checkExists(JobNodePath.getJobNodePath(jobName))) {
			if (!removeJobFromZk(jobName, curatorFrameworkOp)) {
				throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
						String.format("?????????(%s)?????????????????????????????????", jobName));
			}
		}
		// ??????????????????????????????????????????
		int maxJobNum = getMaxJobNum();
		if (jobIncExceeds(namespace, maxJobNum, 1)) {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
					String.format("??????????????????????????????(%d)????????????%s????????????", maxJobNum, jobName));
		}
		// ?????????copy?????????????????????????????????????????????????????????????????????????????????
		JobConfig myJobConfig = jobConfig;
		if (jobNameCopied != null) {
			myJobConfig = currentJobConfigService.findConfigByNamespaceAndJobName(namespace, jobNameCopied);
			SaturnBeanUtils.copyPropertiesIgnoreNull(jobConfig, myJobConfig);
		}
		// ??????????????????????????????????????????????????????????????????
		correctConfigValueWhenAddJob(myJobConfig);
		// ???????????????????????????
		currentJobConfigService.create(constructJobConfig4DB(namespace, myJobConfig, createdBy, createdBy));
		// ??????????????????????????????
		for (JobConfig streamChangedJob : streamChangedJobs) {
			currentJobConfigService.updateStream(constructJobConfig4DB(namespace, streamChangedJob, null, createdBy));
		}
		// ????????????????????????zk??????????????????????????????????????????
		createJobConfigToZk(myJobConfig, streamChangedJobs, curatorFrameworkOp);
	}

	private JobConfig4DB constructJobConfig4DB(String namespace, JobConfig jobConfig, String createdBy,
			String updatedBy) {
		JobConfig4DB jobConfig4DB = new JobConfig4DB();
		SaturnBeanUtils.copyProperties(jobConfig, jobConfig4DB);
		Date now = new Date();
		if (StringUtils.isNotBlank(createdBy)) {
			jobConfig4DB.setCreateTime(now);
			jobConfig4DB.setCreateBy(createdBy);
		}
		jobConfig4DB.setLastUpdateTime(now);
		jobConfig4DB.setLastUpdateBy(updatedBy);
		jobConfig4DB.setNamespace(namespace);
		return jobConfig4DB;
	}

	private void correctConfigValueWhenAddJob(JobConfig jobConfig) {
		jobConfig.setDefaultValues();
		jobConfig.setEnabled(false);
		JobType jobType = JobType.getJobType(jobConfig.getJobType());
		if (JobType.isShell(jobType)) {
			jobConfig.setJobClass("");
		}
		if (JobType.isMsg(jobType)) {
			jobConfig.setFailover(false);
			jobConfig.setRerun(false);
		}
		if (JobType.isPassive(jobType)) {
			jobConfig.setRerun(false);
		}
		if (jobConfig.getLocalMode()) {
			jobConfig.setFailover(false);
		}
		boolean enabledReport = getEnabledReport(jobType, jobConfig.getCron(), jobConfig.getTimeZone());
		jobConfig.setEnabledReport(enabledReport);
		if (!enabledReport) {
			jobConfig.setFailover(false);
			jobConfig.setRerun(false);
		}
	}

	@Override
	public int getMaxJobNum() {
		int result = systemConfigService.getIntegerValue(SystemConfigProperties.MAX_JOB_NUM, DEFAULT_MAX_JOB_NUM);
		return result <= 0 ? DEFAULT_MAX_JOB_NUM : result;
	}

	private int getMaxZnodeDataLength() {
		int result = systemConfigService.getIntegerValue(SystemConfigProperties.MAX_ZNODE_DATA_LENGTH,
				DEFAULT_MAX_ZNODE_DATA_LENGTH);
		return result <= 0 ? DEFAULT_MAX_ZNODE_DATA_LENGTH : result;
	}

	@Override
	public boolean jobIncExceeds(String namespace, int maxJobNum, int inc) throws SaturnJobConsoleException {
		if (maxJobNum <= 0) {
			return false;
		}
		int curJobSize = getUnSystemJobs(namespace).size();
		return (curJobSize + inc) > maxJobNum;
	}

	@Override
	public List<JobConfig> getUnSystemJobs(String namespace) throws SaturnJobConsoleException {
		List<JobConfig> unSystemJobs = new ArrayList<>();
		List<JobConfig4DB> jobConfig4DBList = currentJobConfigService.findConfigsByNamespace(namespace);
		if (jobConfig4DBList != null) {
			for (JobConfig4DB jobConfig4DB : jobConfig4DBList) {
				if (!isSystemJob(jobConfig4DB)) {
					JobConfig jobConfig = new JobConfig();
					SaturnBeanUtils.copyProperties(jobConfig4DB, jobConfig);
					unSystemJobs.add(jobConfig);
				}
			}
		}
		return unSystemJobs;
	}

	private boolean isSystemJob(JobConfig jobConfig) {
		String jobMode = jobConfig.getJobMode();
		return StringUtils.isNotBlank(jobMode) && jobMode.startsWith(JobMode.SYSTEM_PREFIX);
	}

	@Override
	public List<JobConfig4DB> getUnSystemJobsWithCondition(String namespace, Map<String, Object> condition, int page,
			int size) throws SaturnJobConsoleException {
		List<JobConfig4DB> jobConfig4DBList = getJobConfigByStatusWithCondition(namespace, condition, page, size);
		if (CollectionUtils.isEmpty(jobConfig4DBList)) {
			return new ArrayList<>();
		}

		Iterator<JobConfig4DB> iterator = jobConfig4DBList.iterator();
		while (iterator.hasNext()) {
			if (isSystemJob(iterator.next())) {
				iterator.remove();
			}
		}
		return jobConfig4DBList;
	}

	private List<JobConfig4DB> getJobConfigByStatusWithCondition(String namespace, Map<String, Object> condition,
			int page, int size) throws SaturnJobConsoleException {
		JobStatus jobStatus = (JobStatus) condition.get("jobStatus");
		if (jobStatus == null) {
			return currentJobConfigService.findConfigsByNamespaceWithCondition(namespace, condition,
					PageableUtil.generatePageble(page, size));
		}

		List<JobConfig4DB> jobConfig4DBList = new ArrayList<>();
		List<JobConfig4DB> enabledJobConfigList = currentJobConfigService.findConfigsByNamespaceWithCondition(namespace,
				condition, null);
		for (JobConfig4DB jobConfig4DB : enabledJobConfigList) {
			JobStatus currentJobStatus = getJobStatus(namespace, jobConfig4DB.getJobName());
			if (jobStatus.equals(currentJobStatus)) {
				jobConfig4DBList.add(jobConfig4DB);
			}
		}
		return jobConfig4DBList;
	}

	@Override
	public int countUnSystemJobsWithCondition(String namespace, Map<String, Object> condition)
			throws SaturnJobConsoleException {
		return currentJobConfigService.countConfigsByNamespaceWithCondition(namespace, condition);
	}

	@Override
	public int countEnabledUnSystemJobs(String namespace) throws SaturnJobConsoleException {
		return currentJobConfigService.countEnabledUnSystemJobsByNamespace(namespace);
	}

	@Override
	public List<String> getUnSystemJobNames(String namespace) throws SaturnJobConsoleException {
		List<String> unSystemJobs = new ArrayList<>();
		List<JobConfig4DB> jobConfig4DBList = currentJobConfigService.findConfigsByNamespace(namespace);
		if (jobConfig4DBList != null) {
			for (JobConfig4DB jobConfig4DB : jobConfig4DBList) {
				if (!(StringUtils.isNotBlank(jobConfig4DB.getJobMode())
						&& jobConfig4DB.getJobMode().startsWith(JobMode.SYSTEM_PREFIX))) {
					unSystemJobs.add(jobConfig4DB.getJobName());
				}
			}
		}
		return unSystemJobs;
	}

	@Override
	public List<String> getJobNames(String namespace) throws SaturnJobConsoleException {
		List<String> jobNames = currentJobConfigService.findConfigNamesByNamespace(namespace);
		return jobNames != null ? jobNames : Lists.<String>newArrayList();
	}

	@Override
	public void persistJobFromDB(String namespace, JobConfig jobConfig) throws SaturnJobConsoleException {
		jobConfig.setDefaultValues();
		CuratorRepository.CuratorFrameworkOp curatorFrameworkOp = registryCenterService
				.getCuratorFrameworkOp(namespace);
		saveJobConfigToZk(jobConfig, curatorFrameworkOp);
	}

	@Override
	public void persistJobFromDB(JobConfig jobConfig, CuratorFrameworkOp curatorFrameworkOp) {
		jobConfig.setDefaultValues();
		saveJobConfigToZk(jobConfig, curatorFrameworkOp);
	}

	/**
	 * ???????????????????????????true???<br>
	 * ???????????????????????????cron???INTERVAL_TIME_OF_ENABLED_REPORT????????????????????????????????? see #286
	 */
	private boolean getEnabledReport(JobType jobType, String cron, String timeZone) {
		if (JobType.isPassive(jobType)) {
			return true;
		}

		if (!JobType.isCron(jobType)) {
			return false;
		}

		boolean enabledReport = true;
		try {
			Integer intervalTimeConfigured = systemConfigService.getIntegerValue(
					SystemConfigProperties.INTERVAL_TIME_OF_ENABLED_REPORT, DEFAULT_INTERVAL_TIME_OF_ENABLED_REPORT);
			if (intervalTimeConfigured == null) {
				log.warn("unexpected error, get INTERVAL_TIME_OF_ENABLED_REPORT null");
				intervalTimeConfigured = DEFAULT_INTERVAL_TIME_OF_ENABLED_REPORT;
			}
			CronExpression cronExpression = new CronExpression(cron);
			cronExpression.setTimeZone(TimeZone.getTimeZone(timeZone));
			Date lastNextTime = cronExpression.getNextValidTimeAfter(new Date());
			if (lastNextTime != null) {
				for (int i = 0; i < 5; i++) {
					Date nextTime = cronExpression.getNextValidTimeAfter(lastNextTime);
					if (nextTime == null) {
						break;
					}
					long interval = nextTime.getTime() - lastNextTime.getTime();
					if (interval < intervalTimeConfigured * 1000) {
						enabledReport = false;
						break;
					}
					lastNextTime = nextTime;
				}
			}
		} catch (ParseException e) {
			log.warn(e.getMessage(), e);
		}

		return enabledReport;
	}

	private void createJobConfigToZk(JobConfig jobConfig, Set<JobConfig> streamChangedJobs,
			CuratorRepository.CuratorFrameworkOp curatorFrameworkOp) throws SaturnJobConsoleException {
		try {
			String jobName = jobConfig.getJobName();
			// ????????????????????????config??????
			curatorFrameworkOp.create(JobNodePath.getConfigNodePath(jobName), "");
			CuratorFrameworkOp.CuratorTransactionOp curatorTransactionOp = curatorFrameworkOp.inTransaction();
			// ??????????????????????????????????????????????????????zk??????????????????
			Collection<JobConfig> streamChangedJobsNew = removeDuplicateByJobName(streamChangedJobs);
			// ??????????????????????????????
			for (JobConfig streamChangedJob : streamChangedJobsNew) {
				String changedJobName = streamChangedJob.getJobName();
				if (!curatorFrameworkOp.checkExists(JobNodePath.getConfigNodePath(changedJobName))) {
					// ?????????????????????????????????zk????????????????????????????????????
					log.warn("the job({}) config node is not existing in zk", changedJobName);
					continue;
				}
				curatorTransactionOp
						.replaceIfChanged(JobNodePath.getConfigNodePath(changedJobName, CONFIG_ITEM_UPSTREAM),
								streamChangedJob.getUpStream())
						.replaceIfChanged(JobNodePath.getConfigNodePath(changedJobName, CONFIG_ITEM_DOWNSTREAM),
								streamChangedJob.getDownStream());
			}
			// ????????????
			curatorTransactionOp
					.create(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_ENABLED), jobConfig.getEnabled())
					.create(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_DESCRIPTION), jobConfig.getDescription())
					.create(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_CUSTOM_CONTEXT),
							jobConfig.getCustomContext())
					.create(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_JOB_TYPE), jobConfig.getJobType())
					.create(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_JOB_MODE), jobConfig.getJobMode())
					.create(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_SHARDING_ITEM_PARAMETERS),
							jobConfig.getShardingItemParameters())
					.create(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_JOB_PARAMETER),
							jobConfig.getJobParameter())
					.create(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_QUEUE_NAME), jobConfig.getQueueName())
					.create(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_CHANNEL_NAME),
							jobConfig.getChannelName())
					.create(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_FAILOVER), jobConfig.getFailover())
					.create(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_MONITOR_EXECUTION), "true")
					.create(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_TIMEOUT_4_ALARM_SECONDS),
							jobConfig.getTimeout4AlarmSeconds())
					.create(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_TIMEOUT_SECONDS),
							jobConfig.getTimeoutSeconds())
					.create(JobNodePath.getConfigNodePath(jobName, CONFIG_DISABLE_TIMEOUT_SECONDS),
							jobConfig.getDisableTimeoutSeconds())
					.create(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_TIME_ZONE), jobConfig.getTimeZone())
					.create(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_CRON), jobConfig.getCron())
					.create(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_PAUSE_PERIOD_DATE),
							jobConfig.getPausePeriodDate())
					.create(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_PAUSE_PERIOD_TIME),
							jobConfig.getPausePeriodTime())
					.create(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_PROCESS_COUNT_INTERVAL_SECONDS),
							jobConfig.getProcessCountIntervalSeconds())
					.create(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_SHARDING_TOTAL_COUNT),
							jobConfig.getShardingTotalCount())
					.create(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_SHOW_NORMAL_LOG),
							jobConfig.getShowNormalLog())
					.create(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_LOAD_LEVEL), jobConfig.getLoadLevel())
					.create(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_JOB_DEGREE), jobConfig.getJobDegree())
					.create(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_ENABLED_REPORT),
							jobConfig.getEnabledReport())
					.create(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_PREFER_LIST), jobConfig.getPreferList())
					.create(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_USE_DISPREFER_LIST),
							jobConfig.getUseDispreferList())
					.create(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_LOCAL_MODE), jobConfig.getLocalMode())
					.create(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_USE_SERIAL), jobConfig.getUseSerial())
					.create(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_DEPENDENCIES),
							jobConfig.getDependencies())
					.create(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_GROUPS), jobConfig.getGroups())
					.create(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_RERUN), jobConfig.getRerun())
					.create(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_UPSTREAM), jobConfig.getUpStream())
					.create(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_DOWNSTREAM), jobConfig.getDownStream())
					.create(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_JOB_CLASS), jobConfig.getJobClass());
			// ??????????????? jobClass????????????????????????executor????????????????????????????????????

			// ????????????
			curatorTransactionOp.commit();
		} catch (Exception e) {
			log.error("create job to zk failed", e);
			throw new SaturnJobConsoleException(e);
		}
	}

	private void saveJobConfigToZk(JobConfig jobConfig, CuratorRepository.CuratorFrameworkOp curatorFrameworkOp) {
		String jobName = jobConfig.getJobName();
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_ENABLED),
				jobConfig.getEnabled());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_DESCRIPTION),
				jobConfig.getDescription());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_CUSTOM_CONTEXT),
				jobConfig.getCustomContext());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_JOB_TYPE),
				jobConfig.getJobType());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_JOB_MODE),
				jobConfig.getJobMode());
		curatorFrameworkOp.fillJobNodeIfNotExist(
				JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_SHARDING_ITEM_PARAMETERS),
				jobConfig.getShardingItemParameters());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_JOB_PARAMETER),
				jobConfig.getJobParameter());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_QUEUE_NAME),
				jobConfig.getQueueName());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_CHANNEL_NAME),
				jobConfig.getChannelName());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_FAILOVER),
				jobConfig.getFailover());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_MONITOR_EXECUTION),
				"true");
		curatorFrameworkOp.fillJobNodeIfNotExist(
				JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_TIMEOUT_4_ALARM_SECONDS),
				jobConfig.getTimeout4AlarmSeconds());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_TIMEOUT_SECONDS),
				jobConfig.getTimeoutSeconds());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, CONFIG_DISABLE_TIMEOUT_SECONDS),
				jobConfig.getDisableTimeoutSeconds());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_TIME_ZONE),
				jobConfig.getTimeZone());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_CRON),
				jobConfig.getCron());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_PAUSE_PERIOD_DATE),
				jobConfig.getPausePeriodDate());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_PAUSE_PERIOD_TIME),
				jobConfig.getPausePeriodTime());
		curatorFrameworkOp.fillJobNodeIfNotExist(
				JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_PROCESS_COUNT_INTERVAL_SECONDS),
				jobConfig.getProcessCountIntervalSeconds());
		curatorFrameworkOp.fillJobNodeIfNotExist(
				JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_SHARDING_TOTAL_COUNT),
				jobConfig.getShardingTotalCount());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_SHOW_NORMAL_LOG),
				jobConfig.getShowNormalLog());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_LOAD_LEVEL),
				jobConfig.getLoadLevel());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_JOB_DEGREE),
				jobConfig.getJobDegree());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_ENABLED_REPORT),
				jobConfig.getEnabledReport());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_PREFER_LIST),
				jobConfig.getPreferList());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_USE_DISPREFER_LIST),
				jobConfig.getUseDispreferList());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_LOCAL_MODE),
				jobConfig.getLocalMode());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_USE_SERIAL),
				jobConfig.getUseSerial());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_DEPENDENCIES),
				jobConfig.getDependencies());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_GROUPS),
				jobConfig.getGroups());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_RERUN),
				jobConfig.getRerun());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_UPSTREAM),
				jobConfig.getUpStream());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_DOWNSTREAM),
				jobConfig.getDownStream());
		curatorFrameworkOp.fillJobNodeIfNotExist(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_JOB_CLASS),
				jobConfig.getJobClass());
		// ??????????????? jobClass????????????????????????executor????????????????????????????????????
	}

	@Override
	public List<BatchJobResult> importJobs(String namespace, MultipartFile file, String createdBy)
			throws SaturnJobConsoleException {
		try {
			Workbook workbook = Workbook.getWorkbook(file.getInputStream());

			Sheet[] sheets = workbook.getSheets();
			List<JobConfig> jobConfigList = new ArrayList<>();
			// ?????????????????????????????????????????????????????????????????????
			// ????????????????????????????????????????????????
			for (int i = 0; i < sheets.length; i++) {
				Sheet sheet = sheets[i];
				int rows = sheet.getRows();
				for (int row = 1; row < rows; row++) {
					Cell[] rowCells = sheet.getRow(row);
					// ?????????????????????????????????????????????????????????
					if (!isBlankRow(rowCells)) {
						jobConfigList.add(convertJobConfig(i + 1, row + 1, rowCells));
					}
				}
			}
			int maxJobNum = getMaxJobNum();
			if (jobIncExceeds(namespace, maxJobNum, jobConfigList.size())) {
				throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
						String.format("??????????????????????????????(%d)???????????????", maxJobNum));
			}
			return doCreateJobFromImportFile(namespace, jobConfigList, createdBy);
		} catch (SaturnJobConsoleException e) {
			throw e;
		} catch (Exception e) {
			throw new SaturnJobConsoleException(e);
		}
	}

	protected List<BatchJobResult> doCreateJobFromImportFile(String namespace, List<JobConfig> jobConfigList,
			String createdBy) throws SaturnJobConsoleException {
		Map<String, BatchJobResult> resultMap = new LinkedHashMap<>();
		List<JobConfig> jobConfigUpdatedList = new ArrayList<>();
		for (JobConfig jobConfig : jobConfigList) {
			String jobName = jobConfig.getJobName();
			BatchJobResult batchJobResult = new BatchJobResult();
			batchJobResult.setJobName(jobName);
			try {
				// ???????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
				JobConfig jobConfigUpdated = null;
				if (StringUtils.isNotBlank(jobConfig.getUpStream())
						|| StringUtils.isNotBlank(jobConfig.getDownStream())) {
					jobConfigUpdated = new JobConfig();
					jobConfigUpdated.setJobName(jobName);
					jobConfigUpdated.setUpStream(jobConfig.getUpStream());
					jobConfigUpdated.setDownStream(jobConfig.getDownStream());
					jobConfig.setUpStream(null);
					jobConfig.setDownStream(null);
				}
				addJob(namespace, jobConfig, createdBy);
				batchJobResult.setSuccess(true);
				if (jobConfigUpdated != null) {
					jobConfigUpdatedList.add(jobConfigUpdated);
				}
			} catch (SaturnJobConsoleException e) {
				batchJobResult.setSuccess(false);
				batchJobResult.setMessage(e.getMessage());
				log.warn(e.getMessage(), e);
			} catch (Exception e) {
				batchJobResult.setSuccess(false);
				batchJobResult.setMessage(e.toString());
				log.warn(e.getMessage(), e);
			}
			resultMap.put(jobName, batchJobResult);
		}
		for (JobConfig jobConfig : jobConfigUpdatedList) {
			BatchJobResult batchJobResult = resultMap.get(jobConfig.getJobName());
			try {
				updateJobConfig(namespace, jobConfig, createdBy);
			} catch (SaturnJobConsoleException e) {
				batchJobResult.appendMessage(e.getMessage());
				log.warn(e.getMessage(), e);
			} catch (Exception e) {
				batchJobResult.appendMessage(e.toString());
				log.warn(e.getMessage(), e);
			}
		}
		return new ArrayList<>(resultMap.values());
	}

	private boolean isBlankRow(Cell[] rowCells) {
		for (int i = 0; i < rowCells.length; i++) {
			if (!CellType.EMPTY.equals(rowCells[i].getType())) {
				return false;
			}
		}
		return true;
	}

	private JobConfig convertJobConfig(int sheetNumber, int rowNumber, Cell[] rowCells)
			throws SaturnJobConsoleException {
		String jobName = getContents(rowCells, 0);
		if (jobName == null || jobName.trim().isEmpty()) {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
					createExceptionMessage(sheetNumber, rowNumber, 1, "??????????????????"));
		}
		if (!jobName.matches("[0-9a-zA-Z_]*")) {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
					createExceptionMessage(sheetNumber, rowNumber, 1, "?????????????????????????????????0-9???????????????a-z???????????????A-Z????????????_???"));
		}
		JobConfig jobConfig = new JobConfig();
		jobConfig.setJobName(jobName);

		String jobType = getContents(rowCells, 1);
		if (jobType == null || jobType.trim().isEmpty()) {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
					createExceptionMessage(sheetNumber, rowNumber, 2, "?????????????????????"));
		}
		JobType jobTypeObj = JobType.getJobType(jobType);
		if (jobTypeObj == JobType.UNKNOWN_JOB) {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
					createExceptionMessage(sheetNumber, rowNumber, 2, "?????????????????????"));
		}
		jobConfig.setJobType(jobType);

		String jobClass = getContents(rowCells, 2);
		if (JobType.isJava(jobTypeObj) && (jobClass == null || jobClass.trim().isEmpty())) {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
					createExceptionMessage(sheetNumber, rowNumber, 3, "??????java?????????????????????????????????"));
		}
		jobConfig.setJobClass(jobClass);

		String cron = getContents(rowCells, 3);
		if (JobType.isCron(jobTypeObj)) {
			if (cron == null || cron.trim().isEmpty()) {
				throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
						createExceptionMessage(sheetNumber, rowNumber, 4, "??????cron?????????cron??????????????????"));
			}
			cron = cron.trim();
			try {
				CronExpression.validateExpression(cron);
			} catch (ParseException e) {
				throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
						createExceptionMessage(sheetNumber, rowNumber, 4, "cron????????????????????????" + e));
			}
		} else {
			cron = "";// ???????????????????????????????????????cron?????????
		}

		jobConfig.setCron(cron);

		jobConfig.setDescription(getContents(rowCells, 4));

		jobConfig.setLocalMode(Boolean.valueOf(getContents(rowCells, 5)));

		int shardingTotalCount = 1;
		if (jobConfig.getLocalMode()) {
			jobConfig.setShardingTotalCount(shardingTotalCount);
		} else {
			String tmp = getContents(rowCells, 6);
			if (tmp != null && !tmp.trim().isEmpty()) {
				try {
					shardingTotalCount = Integer.parseInt(tmp);
				} catch (NumberFormatException e) {
					throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
							createExceptionMessage(sheetNumber, rowNumber, 7, "??????????????????" + e));
				}
			} else {
				throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
						createExceptionMessage(sheetNumber, rowNumber, 7, "???????????????"));
			}
			if (shardingTotalCount < 1) {
				throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
						createExceptionMessage(sheetNumber, rowNumber, 7, "?????????????????????1"));
			}
			jobConfig.setShardingTotalCount(shardingTotalCount);
		}

		int timeoutSeconds = 0;
		try {
			String tmp = getContents(rowCells, 7);
			if (tmp != null && !tmp.trim().isEmpty()) {
				timeoutSeconds = Integer.parseInt(tmp.trim());
			}
		} catch (NumberFormatException e) {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
					createExceptionMessage(sheetNumber, rowNumber, 8, "?????????Kill??????/????????????????????????" + e));
		}
		jobConfig.setTimeoutSeconds(timeoutSeconds);

		jobConfig.setJobParameter(getContents(rowCells, 8));

		String shardingItemParameters = getContents(rowCells, 9);
		if (jobConfig.getLocalMode()) {
			if (shardingItemParameters == null || shardingItemParameters.trim().isEmpty()) {
				throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
						createExceptionMessage(sheetNumber, rowNumber, 10, "????????????????????????????????????????????????"));
			} else {
				String[] split = shardingItemParameters.split(",");
				boolean includeXing = false;
				for (String tmp : split) {
					String[] split2 = tmp.split("=");
					if ("*".equalsIgnoreCase(split2[0].trim())) {
						includeXing = true;
						break;
					}
				}
				if (!includeXing) {
					throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
							createExceptionMessage(sheetNumber, rowNumber, 10, "??????????????????????????????????????????????????????*=xx???"));
				}
			}
		} else if ((shardingTotalCount > 0)
				&& (shardingItemParameters == null || shardingItemParameters.trim().isEmpty()
						|| shardingItemParameters.split(",").length < shardingTotalCount)) {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
					createExceptionMessage(sheetNumber, rowNumber, 10, "???????????????????????????????????????"));
		}
		jobConfig.setShardingItemParameters(shardingItemParameters);

		jobConfig.setQueueName(getContents(rowCells, 10));
		jobConfig.setChannelName(getContents(rowCells, 11));
		jobConfig.setPreferList(getContents(rowCells, 12));
		jobConfig.setUseDispreferList(!Boolean.parseBoolean(getContents(rowCells, 13)));

		int processCountIntervalSeconds = 300;
		try {
			String tmp = getContents(rowCells, 14);
			if (tmp != null && !tmp.trim().isEmpty()) {
				processCountIntervalSeconds = Integer.parseInt(tmp.trim());
			}
		} catch (NumberFormatException e) {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
					createExceptionMessage(sheetNumber, rowNumber, 15, "?????????????????????????????????????????????" + e));
		}
		jobConfig.setProcessCountIntervalSeconds(processCountIntervalSeconds);

		int loadLevel = 1;
		try {
			String tmp = getContents(rowCells, 15);
			if (tmp != null && !tmp.trim().isEmpty()) {
				loadLevel = Integer.parseInt(tmp.trim());
			}
		} catch (NumberFormatException e) {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
					createExceptionMessage(sheetNumber, rowNumber, 16, "???????????????" + e));
		}
		jobConfig.setLoadLevel(loadLevel);

		jobConfig.setShowNormalLog(Boolean.valueOf(getContents(rowCells, 16)));

		jobConfig.setPausePeriodDate(getContents(rowCells, 17));

		jobConfig.setPausePeriodTime(getContents(rowCells, 18));

		jobConfig.setUseSerial(Boolean.valueOf(getContents(rowCells, 19)));

		int jobDegree = 0;
		try {
			String tmp = getContents(rowCells, 20);
			if (tmp != null && !tmp.trim().isEmpty()) {
				jobDegree = Integer.parseInt(tmp.trim());
			}
		} catch (NumberFormatException e) {
			throw new SaturnJobConsoleException(createExceptionMessage(sheetNumber, rowNumber, 21, "???????????????????????????" + e));
		}
		jobConfig.setJobDegree(jobDegree);

		// ???21????????????????????????????????????????????????????????????????????????setEnabledReport??????????????????addJob

		String jobMode = getContents(rowCells, 22);

		if (jobMode != null && jobMode.startsWith(com.vip.saturn.job.console.domain.JobMode.SYSTEM_PREFIX)) {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
					createExceptionMessage(sheetNumber, rowNumber, 23, "?????????????????????????????????????????????"));
		}
		jobConfig.setJobMode(jobMode);

		String dependencies = getContents(rowCells, 23);
		if (dependencies != null && !dependencies.matches("[0-9a-zA-Z_,]*")) {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
					createExceptionMessage(sheetNumber, rowNumber, 24, "???????????????????????????????????????0-9???????????????a-z???????????????A-Z????????????_???????????????,"));
		}
		jobConfig.setDependencies(dependencies);

		jobConfig.setGroups(getContents(rowCells, 24));

		int timeout4AlarmSeconds = 0;
		try {
			String tmp = getContents(rowCells, 25);
			if (tmp != null && !tmp.trim().isEmpty()) {
				timeout4AlarmSeconds = Integer.parseInt(tmp.trim());
			}
		} catch (NumberFormatException e) {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
					createExceptionMessage(sheetNumber, rowNumber, 26, "?????????????????????????????????" + e));
		}
		jobConfig.setTimeout4AlarmSeconds(timeout4AlarmSeconds);

		String timeZone = getContents(rowCells, 26);
		if (timeZone == null || timeZone.trim().length() == 0) {
			timeZone = SaturnConstants.TIME_ZONE_ID_DEFAULT;
		} else {
			timeZone = timeZone.trim();
			if (!SaturnConstants.TIME_ZONE_IDS.contains(timeZone)) {
				throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
						createExceptionMessage(sheetNumber, rowNumber, 27, "????????????"));
			}
		}
		jobConfig.setTimeZone(timeZone);

		Boolean failover = null;
		String failoverStr = getContents(rowCells, 27);
		if (StringUtils.isNotBlank(failoverStr)) {
			failover = Boolean.valueOf(failoverStr.trim());
			if (failover) {
				if (jobConfig.getLocalMode()) {
					throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
							createExceptionMessage(sheetNumber, rowNumber, 28, "?????????????????????failover"));
				}
				if (JobType.isMsg(jobTypeObj)) {
					throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
							createExceptionMessage(sheetNumber, rowNumber, 28, "?????????????????????failover"));
				}
				// ????????????????????????????????????????????????false
				// ??????????????????????????????????????????????????????????????????setEnabledReport??????????????????addJob
			}
		}
		jobConfig.setFailover(failover);

		Boolean rerun = null;
		String rerunStr = getContents(rowCells, 28);
		if (StringUtils.isNotBlank(rerunStr)) {
			rerun = Boolean.valueOf(rerunStr.trim());
			if (rerun) {
				if (JobType.isMsg(jobTypeObj)) {
					throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
							createExceptionMessage(sheetNumber, rowNumber, 29, "?????????????????????rerun"));
				}
				if (JobType.isPassive(jobTypeObj)) {
					throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
							createExceptionMessage(sheetNumber, rowNumber, 29, "?????????????????????rerun"));
				}
				// ????????????????????????????????????????????????false
				// ??????????????????????????????????????????????????????????????????setEnabledReport??????????????????addJob
			}
		}
		jobConfig.setRerun(rerun);

		String upStream = getContents(rowCells, 29);
		if (upStream != null && !upStream.matches("[0-9a-zA-Z_,]*")) {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
					createExceptionMessage(sheetNumber, rowNumber, 30, "????????????????????????????????????0-9???????????????a-z???????????????A-Z????????????_???????????????,"));
		}
		jobConfig.setUpStream(upStream);

		String downStream = getContents(rowCells, 30);
		if (downStream != null && !downStream.matches("[0-9a-zA-Z_,]*")) {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
					createExceptionMessage(sheetNumber, rowNumber, 31, "????????????????????????????????????0-9???????????????a-z???????????????A-Z????????????_???????????????,"));
		}
		jobConfig.setDownStream(downStream);

		int disableTimeoutSeconds = 0;
		try {
			String tmp = getContents(rowCells, 31);
			if (tmp != null && !tmp.trim().isEmpty()) {
				disableTimeoutSeconds = Integer.parseInt(tmp.trim());
			}
		} catch (NumberFormatException e) {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
					createExceptionMessage(sheetNumber, rowNumber, 32, "???????????????????????????????????????" + e));
		}
		jobConfig.setDisableTimeoutSeconds(disableTimeoutSeconds);

		return jobConfig;
	}

	private String getContents(Cell[] rowCell, int column) {
		if (rowCell.length > column) {
			return rowCell[column].getContents();
		}
		return null;
	}

	private String createExceptionMessage(int sheetNumber, int rowNumber, int columnNumber, String message) {
		return "?????????????????????????????????????????????:" + sheetNumber + "?????????:" + rowNumber + "?????????:" + columnNumber + "??????????????????" + message;
	}

	@Override
	public File exportJobs(String namespace) throws SaturnJobConsoleException {
		try {
			File tmp = new File(SaturnConstants.CACHES_FILE_PATH,
					"tmp_exportFile_" + System.currentTimeMillis() + "_" + random.nextInt(1000) + ".xls");
			if (!tmp.exists()) {
				FileUtils.forceMkdir(tmp.getParentFile());
				tmp.createNewFile();
			}
			WritableWorkbook writableWorkbook = Workbook.createWorkbook(tmp);
			WritableSheet sheet1 = writableWorkbook.createSheet("Sheet1", 0);
			setExcelHeader(sheet1);
			List<JobConfig> unSystemJobs = getUnSystemJobs(namespace);
			// sort by jobName
			Collections.sort(unSystemJobs, new Comparator<JobConfig>() {
				@Override
				public int compare(JobConfig o1, JobConfig o2) {
					return o1.getJobName().compareTo(o2.getJobName());
				}
			});
			setExcelContent(namespace, sheet1, unSystemJobs);

			writableWorkbook.write();
			writableWorkbook.close();

			return tmp;
		} catch (Exception e) {
			throw new SaturnJobConsoleException(e);
		}
	}

	@Override
	public File exportSelectedJobs(String namespace, List<String> jobList) throws SaturnJobConsoleException {
		try {
			File tmp = new File(SaturnConstants.CACHES_FILE_PATH,
					"tmp_exportFile_" + System.currentTimeMillis() + "_" + random.nextInt(1000) + ".xls");
			if (!tmp.exists()) {
				FileUtils.forceMkdir(tmp.getParentFile());
				tmp.createNewFile();
			}
			WritableWorkbook writableWorkbook = Workbook.createWorkbook(tmp);
			WritableSheet sheet1 = writableWorkbook.createSheet("Sheet1", 0);
			setExcelHeader(sheet1);
			// ????????????????????????????????????????????????????????????????????????????????????????????????
			List<JobConfig> targetJobs = filterTargetJobs(jobList, getUnSystemJobs(namespace));
			// sort by jobName
			Collections.sort(targetJobs, new Comparator<JobConfig>() {
				@Override
				public int compare(JobConfig o1, JobConfig o2) {
					return o1.getJobName().compareTo(o2.getJobName());
				}
			});
			setExcelContent(namespace, sheet1, targetJobs);

			writableWorkbook.write();
			writableWorkbook.close();

			return tmp;
		} catch (Exception e) {
			throw new SaturnJobConsoleException(e);
		}
	}

	private List<JobConfig> filterTargetJobs(List<String> jobList, List<JobConfig> jobConfigList) {
		List<JobConfig> result = new ArrayList<>();
		for (JobConfig jobConfig : jobConfigList) {
			if (jobList.contains(jobConfig.getJobName())) {
				result.add(jobConfig);
			}
		}
		return result;
	}

	protected void setExcelContent(String namespace, WritableSheet sheet1, List<JobConfig> unSystemJobs)
			throws SaturnJobConsoleException, WriteException {
		if (unSystemJobs != null && !unSystemJobs.isEmpty()) {
			CuratorFrameworkOp curatorFrameworkOp = registryCenterService.getCuratorFrameworkOp(namespace);
			for (int i = 0; i < unSystemJobs.size(); i++) {
				String jobName = unSystemJobs.get(i).getJobName();
				sheet1.addCell(new Label(0, i + 1, jobName));
				sheet1.addCell(new Label(1, i + 1,
						curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_JOB_TYPE))));
				sheet1.addCell(new Label(2, i + 1,
						curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_JOB_CLASS))));
				sheet1.addCell(new Label(3, i + 1,
						curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_CRON))));
				sheet1.addCell(new Label(4, i + 1,
						curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_DESCRIPTION))));
				sheet1.addCell(new Label(5, i + 1,
						curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_LOCAL_MODE))));
				sheet1.addCell(new Label(6, i + 1, curatorFrameworkOp
						.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_SHARDING_TOTAL_COUNT))));
				sheet1.addCell(new Label(7, i + 1, curatorFrameworkOp
						.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_TIMEOUT_SECONDS))));
				sheet1.addCell(new Label(8, i + 1,
						curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_JOB_PARAMETER))));
				sheet1.addCell(new Label(9, i + 1, curatorFrameworkOp
						.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_SHARDING_ITEM_PARAMETERS))));
				sheet1.addCell(new Label(10, i + 1,
						curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_QUEUE_NAME))));
				sheet1.addCell(new Label(11, i + 1,
						curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_CHANNEL_NAME))));
				sheet1.addCell(new Label(12, i + 1,
						curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_PREFER_LIST))));
				String useDispreferList = curatorFrameworkOp
						.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_USE_DISPREFER_LIST));
				if (useDispreferList != null) {
					useDispreferList = String.valueOf(!Boolean.parseBoolean(useDispreferList));
				}
				sheet1.addCell(new Label(13, i + 1, useDispreferList));
				sheet1.addCell(new Label(14, i + 1, curatorFrameworkOp
						.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_PROCESS_COUNT_INTERVAL_SECONDS))));
				sheet1.addCell(new Label(15, i + 1,
						curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_LOAD_LEVEL))));
				sheet1.addCell(new Label(16, i + 1, curatorFrameworkOp
						.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_SHOW_NORMAL_LOG))));
				sheet1.addCell(new Label(17, i + 1, curatorFrameworkOp
						.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_PAUSE_PERIOD_DATE))));
				sheet1.addCell(new Label(18, i + 1, curatorFrameworkOp
						.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_PAUSE_PERIOD_TIME))));
				sheet1.addCell(new Label(19, i + 1, curatorFrameworkOp
						.getData(JobNodePath.getConfigNodePath(jobName, JobServiceImpl.CONFIG_ITEM_USE_SERIAL))));
				sheet1.addCell(new Label(20, i + 1,
						curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_JOB_DEGREE))));
				sheet1.addCell(new Label(21, i + 1, curatorFrameworkOp
						.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_ENABLED_REPORT))));
				sheet1.addCell(new Label(22, i + 1,
						curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_JOB_MODE))));
				sheet1.addCell(new Label(23, i + 1,
						curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_DEPENDENCIES))));
//				sheet1.addCell(new Label(24, i + 1,
//						curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_GROUPS))));
				// ????????????????????????????????????db???????????????zk????????????Excel???????????????????????????????????????????????????db?????????
				sheet1.addCell(new Label(24, i + 1, unSystemJobs.get(i).getGroups()));
				sheet1.addCell(new Label(25, i + 1, curatorFrameworkOp
						.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_TIMEOUT_4_ALARM_SECONDS))));
				sheet1.addCell(new Label(26, i + 1,
						curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_TIME_ZONE))));
				sheet1.addCell(new Label(27, i + 1,
						curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_FAILOVER))));
				sheet1.addCell(new Label(28, i + 1,
						curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_RERUN))));
				sheet1.addCell(new Label(29, i + 1,
						curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_UPSTREAM))));
				sheet1.addCell(new Label(30, i + 1,
						curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_DOWNSTREAM))));
				sheet1.addCell(new Label(31, i + 1,
						curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_DISABLE_TIMEOUT_SECONDS))));
				sheet1.addCell(new Label(32, i + 1,
						curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_ENABLED))));
			}
		}
	}

	protected void setExcelHeader(WritableSheet sheet1) throws WriteException {
		sheet1.addCell(new Label(0, 0, "????????????"));
		sheet1.addCell(new Label(1, 0, "????????????"));
		sheet1.addCell(new Label(2, 0, "???????????????"));
		sheet1.addCell(new Label(3, 0, "cron?????????"));
		sheet1.addCell(new Label(4, 0, "????????????"));

		Label localModeLabel = new Label(5, 0, "????????????");
		setCellComment(localModeLabel, "?????????????????????????????????false???????????????????????????????????????????????????true");
		sheet1.addCell(localModeLabel);

		Label shardingTotalCountLabel = new Label(6, 0, "?????????");
		setCellComment(shardingTotalCountLabel, "?????????????????????");
		sheet1.addCell(shardingTotalCountLabel);

		Label timeoutSecondsLabel = new Label(7, 0, "???????????????Kill??????/???????????????");
		setCellComment(timeoutSecondsLabel, "0???????????????");
		sheet1.addCell(timeoutSecondsLabel);

		sheet1.addCell(new Label(8, 0, "???????????????"));
		sheet1.addCell(new Label(9, 0, "???????????????/???????????????"));
		sheet1.addCell(new Label(10, 0, "Queue???"));
		sheet1.addCell(new Label(11, 0, "?????????????????????Channel"));

		Label preferListLabel = new Label(12, 0, "??????Executor");
		setCellComment(preferListLabel, "??????executorName???????????????????????????????????????");
		sheet1.addCell(preferListLabel);

		Label usePreferListOnlyLabel = new Label(13, 0, "???????????????Executor");
		setCellComment(usePreferListOnlyLabel, "?????????false");
		sheet1.addCell(usePreferListOnlyLabel);

		sheet1.addCell(new Label(14, 0, "????????????????????????????????????"));
		sheet1.addCell(new Label(15, 0, "??????"));
		sheet1.addCell(new Label(16, 0, "???????????????????????????"));
		sheet1.addCell(new Label(17, 0, "???????????????"));
		sheet1.addCell(new Label(18, 0, "???????????????"));

		Label useSerialLabel = new Label(19, 0, "????????????");
		setCellComment(useSerialLabel, "?????????false");
		sheet1.addCell(useSerialLabel);

		Label jobDegreeLabel = new Label(20, 0, "??????????????????");
		setCellComment(jobDegreeLabel, "0:????????????,1:???????????????,2:????????????,3:????????????,4:????????????,5:????????????");
		sheet1.addCell(jobDegreeLabel);

		Label enabledReportLabel = new Label(21, 0, "??????????????????");
		setCellComment(enabledReportLabel, "??????????????????????????????true?????????????????????????????????false");
		sheet1.addCell(enabledReportLabel);

		Label jobModeLabel = new Label(22, 0, "????????????");
		setCellComment(jobModeLabel, "??????????????????????????????");
		sheet1.addCell(jobModeLabel);

		Label dependenciesLabel = new Label(23, 0, "???????????????");
		setCellComment(dependenciesLabel, "???????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????");
		sheet1.addCell(dependenciesLabel);

		Label groupsLabel = new Label(24, 0, "????????????");
		setCellComment(groupsLabel, "????????????????????????????????????????????????????????????????????????????????????????????????");
		sheet1.addCell(groupsLabel);

		Label timeout4AlarmSecondsLabel = new Label(25, 0, "??????????????????????????????");
		setCellComment(timeout4AlarmSecondsLabel, "0???????????????");
		sheet1.addCell(timeout4AlarmSecondsLabel);

		Label timeZoneLabel = new Label(26, 0, "??????");
		setCellComment(timeZoneLabel, "??????????????????");
		sheet1.addCell(timeZoneLabel);

		sheet1.addCell(new Label(27, 0, "failover"));

		sheet1.addCell(new Label(28, 0, "????????????"));

		Label upStream = new Label(29, 0, "????????????");
		setCellComment(upStream, "???????????????????????????????????????????????????????????????????????????????????????????????????");
		sheet1.addCell(upStream);

		Label downStream = new Label(30, 0, "????????????");
		setCellComment(downStream, "???????????????????????????????????????????????????????????????????????????????????????????????????");
		sheet1.addCell(downStream);

		Label disableTimeoutSecondsLabel = new Label(31, 0, "??????????????????????????????");
		setCellComment(disableTimeoutSecondsLabel, "0???????????????");
		sheet1.addCell(disableTimeoutSecondsLabel);

		sheet1.addCell(new Label(32, 0, "????????????"));
	}

	protected void setCellComment(WritableCell cell, String comment) {
		WritableCellFeatures cellFeatures = new WritableCellFeatures();
		cellFeatures.setComment(comment);
		cell.setCellFeatures(cellFeatures);
	}

	@Override
	public ArrangeLayout getArrangeLayout(String namespace) throws SaturnJobConsoleException {
		ArrangeLayout arrangeLayout = new ArrangeLayout();
		// get all ArrangeNodes
		Map<String, ArrangeNode> nodeMap = new HashMap<>();
		List<JobConfig> unSystemJobs = getUnSystemJobs(namespace);
		Map<String, JobConfig> unSystemJobsMap = new HashMap<>();
		for (JobConfig jobConfig : unSystemJobs) {
			String jobName = jobConfig.getJobName();
			unSystemJobsMap.put(jobName, jobConfig);
			ArrangeNode node = nodeMap.get(jobName);
			if (node == null) {
				node = new ArrangeNode();
				node.setName(jobName);
				nodeMap.put(jobName, node);
			}
			if (StringUtils.isNotBlank(jobConfig.getDownStream())) {
				for (String split : jobConfig.getDownStream().split(",")) {
					String temp = split.trim();
					if (StringUtils.isNotBlank(temp)) {
						node.getChildren().add(temp);
					}
				}
			}
		}
		Collection<ArrangeNode> nodes = nodeMap.values();
		// set paths
		for (ArrangeNode node : nodes) {
			String name = node.getName();
			for (String child : node.getChildren()) {
				ArrangePath path = new ArrangePath();
				path.setSource(name);
				path.setTarget(child);
				arrangeLayout.getPaths().add(path);
			}
		}
		Collections.sort(arrangeLayout.getPaths(), new Comparator<ArrangePath>() {
			@Override
			public int compare(ArrangePath o1, ArrangePath o2) {
				int compare1 = o1.getSource().compareTo(o2.getSource());
				return compare1 != 0 ? compare1 : o1.getTarget().compareTo(o2.getTarget());
			}
		});
		// set levels
		for (ArrangeNode node : nodes) {
			int maxLevel = getMaxArrangeNodeLevel(node, 0, nodes, new Stack<String>());
			if (maxLevel > node.getLevel()) {
				node.setLevel(maxLevel);
			}
		}
		int maxLevel = 0;
		for (ArrangeNode node : nodes) {
			maxLevel = Math.max(maxLevel, node.getLevel());
		}
		for (int i = 0; i <= maxLevel; i++) {
			arrangeLayout.getLevels().add(new ArrayList<ArrangeLevel>());
		}
		for (ArrangeNode node : nodes) {
			int level = node.getLevel();
			if (level == 0 && node.getChildren().isEmpty()) {
				continue;
			}
			ArrangeLevel arrangeLevel = new ArrangeLevel();
			SaturnBeanUtils.copyProperties(node, arrangeLevel);
			arrangeLevel.setDescription(unSystemJobsMap.get(node.getName()).getDescription());
			arrangeLevel.setJobStatus(getJobStatus(namespace, unSystemJobsMap.get(node.getName())));
			arrangeLayout.getLevels().get(level).add(arrangeLevel);
		}
		for (int i = 0; i <= maxLevel; i++) {
			Collections.sort(arrangeLayout.getLevels().get(i), new Comparator<ArrangeLevel>() {
				@Override
				public int compare(ArrangeLevel o1, ArrangeLevel o2) {
					return o1.getName().compareTo(o2.getName());
				}
			});
		}
		return arrangeLayout;
	}

	private int getMaxArrangeNodeLevel(ArrangeNode currentNode, int level, Collection<ArrangeNode> nodes,
			Stack<String> onePathRecords) throws SaturnJobConsoleException {
		String currentName = currentNode.getName();
		onePathRecords.push(currentName);
		int maxLevel = level;
		for (ArrangeNode node : nodes) {
			if (node.getChildren().contains(currentName)) {
				String name = node.getName();
				if (onePathRecords.search(name) != -1) {
					onePathRecords.push(name);
					throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST, "???????????????????????????????????????????????????: " + onePathRecords);
				}
				maxLevel = Math.max(maxLevel, getMaxArrangeNodeLevel(node, level + 1, nodes, onePathRecords));
			}
		}
		onePathRecords.pop();
		return maxLevel;
	}

	@Override
	public JobConfig getJobConfigFromZK(String namespace, String jobName) throws SaturnJobConsoleException {
		CuratorRepository.CuratorFrameworkOp curatorFrameworkOp = registryCenterService
				.getCuratorFrameworkOp(namespace);
		JobConfig result = new JobConfig();
		result.setJobName(jobName);
		result.setJobType(curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_JOB_TYPE)));
		result.setJobClass(curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_JOB_CLASS)));
		// ??????????????????msg_job???
		if (StringUtils.isBlank(result.getJobType())) {
			if (result.getJobClass().indexOf("script") >= 0) {
				result.setJobType(JobType.SHELL_JOB.name());
			} else {
				result.setJobType(JobType.JAVA_JOB.name());
			}
		}
		result.setShardingTotalCount(Integer.valueOf(
				curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_SHARDING_TOTAL_COUNT))));
		String timeZone = curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_TIME_ZONE));
		if (Strings.isNullOrEmpty(timeZone)) {
			result.setTimeZone(SaturnConstants.TIME_ZONE_ID_DEFAULT);
		} else {
			result.setTimeZone(timeZone);
		}
		result.setCron(curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_CRON)));
		result.setPausePeriodDate(
				curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_PAUSE_PERIOD_DATE)));
		result.setPausePeriodTime(
				curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_PAUSE_PERIOD_TIME)));
		result.setShardingItemParameters(curatorFrameworkOp
				.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_SHARDING_ITEM_PARAMETERS)));
		result.setJobParameter(
				curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_JOB_PARAMETER)));
		result.setProcessCountIntervalSeconds(Integer.valueOf(curatorFrameworkOp
				.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_PROCESS_COUNT_INTERVAL_SECONDS))));
		String timeout4AlarmSecondsStr = curatorFrameworkOp
				.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_TIMEOUT_4_ALARM_SECONDS));
		if (Strings.isNullOrEmpty(timeout4AlarmSecondsStr)) {
			result.setTimeout4AlarmSeconds(0);
		} else {
			result.setTimeout4AlarmSeconds(Integer.valueOf(timeout4AlarmSecondsStr));
		}
		result.setTimeoutSeconds(Integer.valueOf(
				curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_TIMEOUT_SECONDS))));
		String disableTimeoutSecondsStr = curatorFrameworkOp
				.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_DISABLE_TIMEOUT_SECONDS));
		if (Strings.isNullOrEmpty(disableTimeoutSecondsStr)) {
			result.setDisableTimeoutSeconds(0);
		} else {
			result.setDisableTimeoutSeconds(Integer.valueOf(disableTimeoutSecondsStr));
		}
		String lv = curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_LOAD_LEVEL));
		if (Strings.isNullOrEmpty(lv)) {
			result.setLoadLevel(1);
		} else {
			result.setLoadLevel(Integer.valueOf(lv));
		}
		String jobDegree = curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_JOB_DEGREE));
		if (Strings.isNullOrEmpty(jobDegree)) {
			result.setJobDegree(0);
		} else {
			result.setJobDegree(Integer.valueOf(jobDegree));
		}
		result.setEnabled(Boolean
				.valueOf(curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_ENABLED))));// ??????????????????
		result.setPreferList(
				curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_PREFER_LIST)));
		String useDispreferList = curatorFrameworkOp
				.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_USE_DISPREFER_LIST));
		if (Strings.isNullOrEmpty(useDispreferList)) {
			result.setUseDispreferList(null);
		} else {
			result.setUseDispreferList(Boolean.valueOf(useDispreferList));
		}
		result.setLocalMode(Boolean
				.valueOf(curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_LOCAL_MODE))));
		result.setDependencies(
				curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_DEPENDENCIES)));
		result.setGroups(curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_GROUPS)));
		result.setDescription(
				curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_DESCRIPTION)));
		result.setJobMode(curatorFrameworkOp
				.getData(JobNodePath.getConfigNodePath(jobName, JobServiceImpl.CONFIG_ITEM_JOB_MODE)));
		result.setUseSerial(Boolean.valueOf(curatorFrameworkOp
				.getData(JobNodePath.getConfigNodePath(jobName, JobServiceImpl.CONFIG_ITEM_USE_SERIAL))));
		result.setQueueName(curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_QUEUE_NAME)));
		result.setChannelName(
				curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_CHANNEL_NAME)));
		if (!curatorFrameworkOp
				.checkExists(JobNodePath.getConfigNodePath(jobName, JobServiceImpl.CONFIG_ITEM_SHOW_NORMAL_LOG))) {
			curatorFrameworkOp.create(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_SHOW_NORMAL_LOG));
		}
		String enabledReport = curatorFrameworkOp
				.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_ENABLED_REPORT));
		Boolean enabledReportValue = Boolean.valueOf(enabledReport);
		if (Strings.isNullOrEmpty(enabledReport)) {
			enabledReportValue = true;
		}
		result.setEnabledReport(enabledReportValue);
		result.setShowNormalLog(Boolean.valueOf(
				curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_SHOW_NORMAL_LOG))));
		return result;
	}

	@Override
	public JobConfig getJobConfig(String namespace, String jobName) throws SaturnJobConsoleException {
		JobConfig4DB jobConfig4DB = currentJobConfigService.findConfigByNamespaceAndJobName(namespace, jobName);
		if (jobConfig4DB == null) {
			throw new SaturnJobConsoleException(ERROR_CODE_NOT_EXISTED, String.format("?????????(%s)?????????", jobName));
		}
		JobConfig jobConfig = new JobConfig();
		SaturnBeanUtils.copyProperties(jobConfig4DB, jobConfig);
		return jobConfig;
	}

	@Override
	public JobStatus getJobStatus(String namespace, String jobName) throws SaturnJobConsoleException {
		JobConfig4DB jobConfig = currentJobConfigService.findConfigByNamespaceAndJobName(namespace, jobName);
		if (jobConfig == null) {
			throw new SaturnJobConsoleException(ERROR_CODE_NOT_EXISTED, "????????????????????????" + jobName + "???????????????????????????????????????");
		}
		CuratorRepository.CuratorFrameworkOp curatorFrameworkOp = registryCenterService
				.getCuratorFrameworkOp(namespace);
		return getJobStatus(jobName, curatorFrameworkOp, jobConfig.getEnabled());
	}

	@Override
	public JobStatus getJobStatus(String namespace, JobConfig jobConfig) throws SaturnJobConsoleException {
		CuratorRepository.CuratorFrameworkOp curatorFrameworkOp = registryCenterService
				.getCuratorFrameworkOp(namespace);
		return getJobStatus(jobConfig.getJobName(), curatorFrameworkOp, jobConfig.getEnabled());
	}

	@Override
	public boolean isJobShardingAllocatedExecutor(String namespace, String jobName) throws SaturnJobConsoleException {
		CuratorRepository.CuratorFrameworkOp curatorFrameworkOp = registryCenterService
				.getCuratorFrameworkOp(namespace);
		String executorsPath = JobNodePath.getServerNodePath(jobName);
		List<String> executors = curatorFrameworkOp.getChildren(executorsPath);
		if (CollectionUtils.isEmpty(executors)) {
			return false;
		}
		for (String executor : executors) {
			String sharding = curatorFrameworkOp.getData(JobNodePath.getServerNodePath(jobName, executor, "sharding"));
			if (StringUtils.isNotBlank(sharding)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public List<String> getJobServerList(String namespace, String jobName) throws SaturnJobConsoleException {
		CuratorRepository.CuratorFrameworkOp curatorFrameworkOp = registryCenterService
				.getCuratorFrameworkOp(namespace);
		String executorsPath = JobNodePath.getServerNodePath(jobName);
		List<String> executors = curatorFrameworkOp.getChildren(executorsPath);
		if (executors == null || CollectionUtils.isEmpty(executors)) {
			return Lists.newArrayList();
		}

		return executors;
	}

	@Override
	public GetJobConfigVo getJobConfigVo(String namespace, String jobName) throws SaturnJobConsoleException {
		JobConfig4DB jobConfig4DB = currentJobConfigService.findConfigByNamespaceAndJobName(namespace, jobName);
		if (jobConfig4DB == null) {
			throw new SaturnJobConsoleException(ERROR_CODE_NOT_EXISTED, String.format("?????????(%s)?????????", jobName));
		}
		GetJobConfigVo getJobConfigVo = new GetJobConfigVo();
		JobConfig jobConfig = new JobConfig();
		SaturnBeanUtils.copyProperties(jobConfig4DB, jobConfig);
		jobConfig.setDefaultValues();
		getJobConfigVo.copyFrom(jobConfig);

		getJobConfigVo.setTimeZonesProvided(Arrays.asList(TimeZone.getAvailableIDs()));
		getJobConfigVo.setPreferListProvided(getCandidateExecutors(namespace, jobName));
		getJobConfigVo.setUpStreamProvided(getCandidateUpStream(namespace, jobConfig));
		getJobConfigVo.setDownStreamProvided(getCandidateDownStream(namespace, jobConfig));

		CuratorRepository.CuratorFrameworkOp curatorFrameworkOp = registryCenterService
				.getCuratorFrameworkOp(namespace);
		getJobConfigVo
				.setStatus(getJobStatus(getJobConfigVo.getJobName(), curatorFrameworkOp, getJobConfigVo.getEnabled()));

		return getJobConfigVo;
	}

	private List<String> getCandidateDownStream(String namespace, JobConfig jobConfig)
			throws SaturnJobConsoleException {
		List<String> candidateDownStream = new ArrayList<>();
		if (!canBeUpStream(jobConfig)) {
			return candidateDownStream;
		}
		Set<String> downStream = parseStreamToList(jobConfig.getDownStream());
		List<JobConfig> unSystemJobs = getUnSystemJobs(namespace);
		Set<String> ancestors = getAncestors(namespace, jobConfig, unSystemJobs, new Stack<String>(), false);
		for (JobConfig otherJob : unSystemJobs) {
			String otherJobName = otherJob.getJobName();
			if (!jobConfig.getJobName().equals(otherJobName) && !downStream.contains(otherJobName)
					&& !ancestors.contains(otherJobName) && canBeDownStream(otherJob)) {
				candidateDownStream.add(otherJobName);
			}
		}
		return candidateDownStream;
	}

	private boolean canBeDownStream(JobConfig jobConfig) {
		return JobType.isPassive(JobType.getJobType(jobConfig.getJobType()));
	}

	private List<String> getCandidateUpStream(String namespace, JobConfig jobConfig) throws SaturnJobConsoleException {
		List<String> candidateUpStream = new ArrayList<>();
		if (!canBeDownStream(jobConfig)) {
			return candidateUpStream;
		}
		Set<String> upStream = parseStreamToList(jobConfig.getUpStream());
		List<JobConfig> unSystemJobs = getUnSystemJobs(namespace);
		Set<String> descendants = getDescendants(namespace, jobConfig, unSystemJobs, new Stack<String>(), false);
		for (JobConfig otherJob : unSystemJobs) {
			String otherJobName = otherJob.getJobName();
			if (jobConfig.getJobName().equals(otherJobName)) {
				continue;
			}
			if (upStream.contains(otherJobName)) {
				continue;
			}
			if (descendants.contains(otherJobName)) {
				continue;
			}
			if (canBeUpStream(otherJob)) {
				candidateUpStream.add(otherJobName);
			}
		}
		return candidateUpStream;
	}

	private boolean canBeUpStream(JobConfig jobConfig) {
		JobType jobType = JobType.getJobType(jobConfig.getJobType());
		if (!JobType.isCron(jobType) && !JobType.isPassive(jobType)) {
			return false;
		}
		if (jobConfig.getLocalMode() == Boolean.TRUE) {
			return false;
		}
		if (jobConfig.getShardingTotalCount() != null && jobConfig.getShardingTotalCount() > 1) {
			return false;
		}
		return true;
	}

	@Transactional(rollbackFor = Exception.class)
	@Override
	public void updateJobConfig(String namespace, JobConfig jobConfig, String updatedBy)
			throws SaturnJobConsoleException {
		JobConfig4DB oldJobConfig4DB = currentJobConfigService.findConfigByNamespaceAndJobName(namespace,
				jobConfig.getJobName());
		if (oldJobConfig4DB == null) {
			throw new SaturnJobConsoleException(ERROR_CODE_NOT_EXISTED,
					String.format("?????????(%s)?????????", jobConfig.getJobName()));
		}
		// ?????????????????????????????????????????????????????????????????????????????????????????????????????????
		JobConfig4DB newJobConfig4DB = new JobConfig4DB();
		SaturnBeanUtils.copyProperties(oldJobConfig4DB, newJobConfig4DB);
		SaturnBeanUtils.copyPropertiesIgnoreNull(jobConfig, newJobConfig4DB);
		// ???????????????????????????????????????????????????????????????????????????????????????
		if (oldJobConfig4DB.equals(newJobConfig4DB)) {
			return;
		}
		// ??????????????????????????????????????????????????????????????????
		correctConfigValueWhenUpdateJob(newJobConfig4DB);
		// ??????????????????
		List<JobConfig> unSystemJobs = getUnSystemJobs(namespace);
		Set<JobConfig> streamChangedJobs = new HashSet<>();
		validateJobConfig(namespace, newJobConfig4DB, unSystemJobs, streamChangedJobs);
		// ???????????????????????????
		currentJobConfigService.updateNewAndSaveOld2History(newJobConfig4DB, oldJobConfig4DB, updatedBy);
		// ??????????????????????????????
		for (JobConfig streamChangedJob : streamChangedJobs) {
			currentJobConfigService.updateStream(constructJobConfig4DB(namespace, streamChangedJob, null, updatedBy));
		}
		// ?????????????????????zk??????????????????????????????????????????
		updateJobConfigToZk(newJobConfig4DB, streamChangedJobs, registryCenterService.getCuratorFrameworkOp(namespace));
	}

	private void correctConfigValueWhenUpdateJob(JobConfig jobConfig) {
		// ???????????????????????????????????????????????????
		jobConfig.setDefaultValues();
		// ???????????????failover???rerun
		JobType jobType = JobType.getJobType(jobConfig.getJobType());
		if (JobType.isMsg(jobType)) {
			jobConfig.setFailover(false);
			jobConfig.setRerun(false);
		}
		// ???????????????rerun
		if (JobType.isPassive(jobType)) {
			jobConfig.setRerun(false);
		}
		// ???????????????failover
		if (jobConfig.getLocalMode()) {
			jobConfig.setFailover(false);
		}
		// ??????????????????failover???rerun
		if (!jobConfig.getEnabledReport()) {
			jobConfig.setFailover(false);
			jobConfig.setRerun(false);
		}
	}

	private void updateJobConfigToZk(JobConfig jobConfig, Set<JobConfig> streamChangedJobs,
			CuratorRepository.CuratorFrameworkOp curatorFrameworkOp) throws SaturnJobConsoleException {
		try {
			String jobName = jobConfig.getJobName();
			// ??????????????????????????????execution??????
			if (jobConfig.getEnabledReport() == Boolean.FALSE) {
				log.info("the switch of enabledReport set to false, now deleting the execution zk node");
				String executionNodePath = JobNodePath.getExecutionNodePath(jobName);
				if (curatorFrameworkOp.checkExists(executionNodePath)) {
					curatorFrameworkOp.deleteRecursive(executionNodePath);
				}
			}
			CuratorFrameworkOp.CuratorTransactionOp curatorTransactionOp = curatorFrameworkOp.inTransaction();
			// ??????????????????????????????????????????????????????zk??????????????????
			Collection<JobConfig> streamChangedJobsNew = removeDuplicateByJobName(streamChangedJobs);
			// ??????????????????????????????
			for (JobConfig streamChangedJob : streamChangedJobsNew) {
				String changedJobName = streamChangedJob.getJobName();
				if (!curatorFrameworkOp.checkExists(JobNodePath.getConfigNodePath(changedJobName))) {
					// ?????????????????????????????????zk????????????????????????????????????
					log.warn("the job({}) config node is not existing in ZK", changedJobName);
					continue;
				}
				curatorTransactionOp
						.replaceIfChanged(JobNodePath.getConfigNodePath(changedJobName, CONFIG_ITEM_UPSTREAM),
								streamChangedJob.getUpStream())
						.replaceIfChanged(JobNodePath.getConfigNodePath(changedJobName, CONFIG_ITEM_DOWNSTREAM),
								streamChangedJob.getDownStream());
			}
			// ????????????
			curatorTransactionOp
					.replaceIfChanged(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_ENABLED),
							jobConfig.getEnabled())
					.replaceIfChanged(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_DESCRIPTION),
							jobConfig.getDescription())
					.replaceIfChanged(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_CUSTOM_CONTEXT),
							jobConfig.getCustomContext())
					.replaceIfChanged(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_JOB_TYPE),
							jobConfig.getJobType())
					.replaceIfChanged(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_JOB_MODE),
							jobConfig.getJobMode())
					.replaceIfChanged(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_SHARDING_ITEM_PARAMETERS),
							jobConfig.getShardingItemParameters())
					.replaceIfChanged(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_JOB_PARAMETER),
							jobConfig.getJobParameter())
					.replaceIfChanged(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_QUEUE_NAME),
							jobConfig.getQueueName())
					.replaceIfChanged(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_CHANNEL_NAME),
							jobConfig.getChannelName())
					.replaceIfChanged(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_FAILOVER),
							jobConfig.getFailover())
					.replaceIfChanged(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_MONITOR_EXECUTION), "true")
					.replaceIfChanged(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_TIMEOUT_4_ALARM_SECONDS),
							jobConfig.getTimeout4AlarmSeconds())
					.replaceIfChanged(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_TIMEOUT_SECONDS),
							jobConfig.getTimeoutSeconds())
					.replaceIfChanged(JobNodePath.getConfigNodePath(jobName, CONFIG_DISABLE_TIMEOUT_SECONDS),
							jobConfig.getDisableTimeoutSeconds())
					.replaceIfChanged(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_TIME_ZONE),
							jobConfig.getTimeZone())
					.replaceIfChanged(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_CRON), jobConfig.getCron())
					.replaceIfChanged(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_PAUSE_PERIOD_DATE),
							jobConfig.getPausePeriodDate())
					.replaceIfChanged(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_PAUSE_PERIOD_TIME),
							jobConfig.getPausePeriodTime())
					.replaceIfChanged(
							JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_PROCESS_COUNT_INTERVAL_SECONDS),
							jobConfig.getProcessCountIntervalSeconds())
					.replaceIfChanged(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_SHARDING_TOTAL_COUNT),
							jobConfig.getShardingTotalCount())
					.replaceIfChanged(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_SHOW_NORMAL_LOG),
							jobConfig.getShowNormalLog())
					.replaceIfChanged(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_LOAD_LEVEL),
							jobConfig.getLoadLevel())
					.replaceIfChanged(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_JOB_DEGREE),
							jobConfig.getJobDegree())
					.replaceIfChanged(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_ENABLED_REPORT),
							jobConfig.getEnabledReport())
					.replaceIfChanged(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_PREFER_LIST),
							jobConfig.getPreferList())
					.replaceIfChanged(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_USE_DISPREFER_LIST),
							jobConfig.getUseDispreferList())
					.replaceIfChanged(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_LOCAL_MODE),
							jobConfig.getLocalMode())
					.replaceIfChanged(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_USE_SERIAL),
							jobConfig.getUseSerial())
					.replaceIfChanged(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_DEPENDENCIES),
							jobConfig.getDependencies())
					.replaceIfChanged(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_GROUPS), jobConfig.getGroups())
					.replaceIfChanged(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_RERUN), jobConfig.getRerun())
					.replaceIfChanged(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_UPSTREAM),
							jobConfig.getUpStream())
					.replaceIfChanged(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_DOWNSTREAM),
							jobConfig.getDownStream())
					.replaceIfChanged(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_JOB_CLASS),
							jobConfig.getJobClass());

			// ????????????
			curatorTransactionOp.commit();
		} catch (Exception e) {
			log.error("update job to zk failed", e);
			throw new SaturnJobConsoleException(e);
		}
	}

	private Collection<JobConfig> removeDuplicateByJobName(Set<JobConfig> streamChangedJobs) {
		Map<String, JobConfig> streamChangedJobsMap = new HashMap<>();
		for (JobConfig streamChangedJob : streamChangedJobs) {
			String jobName = streamChangedJob.getJobName();
			if (streamChangedJobsMap.containsKey(jobName)) {
				log.warn("the DB have duplicated jobName({})", jobName);
			} else {
				streamChangedJobsMap.put(jobName, streamChangedJob);
			}
		}
		return streamChangedJobsMap.values();
	}

	@Override
	public List<String> getAllJobNamesFromZK(String namespace) throws SaturnJobConsoleException {
		CuratorRepository.CuratorFrameworkOp curatorFrameworkOp = registryCenterService
				.getCuratorFrameworkOp(namespace);
		String jobsNodePath = JobNodePath.get$JobsNodePath();
		List<String> jobs = curatorFrameworkOp.getChildren(jobsNodePath);
		if (jobs == null) {
			return Lists.newArrayList();
		}

		List<String> allJobs = new ArrayList<>();
		for (String job : jobs) {
			// ??????config???????????????????????????????????????????????????????????????????????????????????????
			if (curatorFrameworkOp.checkExists(JobNodePath.getConfigNodePath(job))) {
				allJobs.add(job);
			}
		}
		Collections.sort(allJobs);
		return allJobs;
	}

	@Transactional(rollbackFor = Exception.class)
	@Override
	public void updateJobCron(String namespace, String jobName, String cron, Map<String, String> customContext,
			String updatedBy) throws SaturnJobConsoleException {
		String cron0 = cron;
		if (cron0 != null && !cron0.trim().isEmpty()) {
			try {
				cron0 = cron0.trim();
				CronExpression.validateExpression(cron0);
			} catch (ParseException e) {
				throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST, "The cron expression is invalid: " + cron);
			}
		} else {
			cron0 = "";
		}
		CuratorRepository.CuratorFrameworkOp curatorFrameworkOp = registryCenterService
				.getCuratorFrameworkOp(namespace);
		if (curatorFrameworkOp.checkExists(JobNodePath.getConfigNodePath(jobName))) {
			String newCustomContextStr = null;

			String oldCustomContextStr = curatorFrameworkOp
					.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_CUSTOM_CONTEXT));
			Map<String, String> oldCustomContextMap = toCustomContext(oldCustomContextStr);
			if (customContext != null && !customContext.isEmpty()) {
				oldCustomContextMap.putAll(customContext);
				newCustomContextStr = toCustomContext(oldCustomContextMap);
				if (newCustomContextStr.length() > 8000) {
					throw new SaturnJobConsoleException("The all customContext is out of db limit (Varchar[8000])");
				}
				if (newCustomContextStr.getBytes().length > 1024 * 1024) {
					throw new SaturnJobConsoleException("The all customContext is out of zk limit memory(1M)");
				}
			}

			String newCron = null;
			String oldCron = curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_CRON));
			if (cron0 != null && oldCron != null && !cron0.equals(oldCron.trim())) {
				newCron = cron0;
			}
			if (newCustomContextStr != null || newCron != null) {
				saveCronToDb(jobName, curatorFrameworkOp, newCustomContextStr, newCron, updatedBy);
			}
			if (newCustomContextStr != null) {
				curatorFrameworkOp.update(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_CUSTOM_CONTEXT),
						newCustomContextStr);
			}
			if (newCron != null) {
				curatorFrameworkOp.update(JobNodePath.getConfigNodePath(jobName, CONFIG_ITEM_CRON), newCron);
			}
		} else {
			throw new SaturnJobConsoleException(ERROR_CODE_NOT_EXISTED, "The job does not exists: " + jobName);
		}
	}

	private void saveCronToDb(String jobName, CuratorRepository.CuratorFrameworkOp curatorFrameworkOp,
			String newCustomContextStr, String newCron, String updatedBy) throws SaturnJobConsoleException {
		String namespace = curatorFrameworkOp.getCuratorFramework().getNamespace();
		JobConfig4DB jobConfig4DB = currentJobConfigService.findConfigByNamespaceAndJobName(namespace, jobName);
		if (jobConfig4DB == null) {
			String errorMsg = "???DB???????????????????????????, namespace???" + namespace + " jobName:" + jobName;
			log.error(errorMsg);
			throw new SaturnJobConsoleHttpException(HttpStatus.INTERNAL_SERVER_ERROR.value(), errorMsg);
		}
		JobConfig4DB newJobConfig4DB = new JobConfig4DB();
		SaturnBeanUtils.copyProperties(jobConfig4DB, newJobConfig4DB);
		if (newCustomContextStr != null) {
			newJobConfig4DB.setCustomContext(newCustomContextStr);
		}
		if (newCron != null) {
			newJobConfig4DB.setCron(newCron);
		}
		currentJobConfigService.updateNewAndSaveOld2History(newJobConfig4DB, jobConfig4DB, updatedBy);
	}

	/**
	 * ???str??????map
	 *
	 * @param customContextStr str?????????
	 * @return ??????????????????map
	 */
	private Map<String, String> toCustomContext(String customContextStr) {
		Map<String, String> customContext = null;
		if (customContextStr != null) {
			customContext = JsonUtils.fromJSON(customContextStr, customContextType);
		}
		if (customContext == null) {
			customContext = new HashMap<>();
		}
		return customContext;
	}

	/**
	 * ???map??????str?????????
	 *
	 * @param customContextMap ??????????????????map
	 * @return ??????????????????str
	 */
	private String toCustomContext(Map<String, String> customContextMap) {
		String result = JsonUtils.toJSON(customContextMap);
		if (result == null) {
			result = "";
		}
		return result.trim();
	}

	@Override
	public List<JobServer> getJobServers(String namespace, String jobName) throws SaturnJobConsoleException {
		CuratorRepository.CuratorFrameworkOp curatorFrameworkOp = registryCenterService
				.getCuratorFrameworkOp(namespace);
		String serverNodePath = JobNodePath.getServerNodePath(jobName);
		List<String> executors = curatorFrameworkOp.getChildren(serverNodePath);
		List<JobServer> result = new ArrayList<>();
		if (executors != null && !executors.isEmpty()) {
			String leaderIp = curatorFrameworkOp.getData(JobNodePath.getLeaderNodePath(jobName, "election/host"));
			JobStatus jobStatus = getJobStatus(namespace, jobName);
			for (String each : executors) {
				JobServer jobServer = getJobServer(jobName, leaderIp, each, curatorFrameworkOp);
				jobServer.setJobStatus(jobStatus);
				result.add(jobServer);
			}
		}
		return result;
	}

	@Override
	public List<JobServerStatus> getJobServersStatus(String namespace, String jobName)
			throws SaturnJobConsoleException {
		CuratorRepository.CuratorFrameworkOp curatorFrameworkOp = registryCenterService
				.getCuratorFrameworkOp(namespace);
		List<String> executors = getJobServerList(namespace, jobName);
		List<JobServerStatus> result = new ArrayList<>();
		if (executors != null && !executors.isEmpty()) {
			for (String each : executors) {
				result.add(getJobServerStatus(jobName, each, curatorFrameworkOp));
			}
		}

		return result;
	}

	private JobServerStatus getJobServerStatus(String jobName, String executorName,
			CuratorFrameworkOp curatorFrameworkOp) {
		JobServerStatus result = new JobServerStatus();
		result.setExecutorName(executorName);
		result.setJobName(jobName);
		result.setServerStatus(getJobServerStatus0(jobName, executorName, curatorFrameworkOp));
		return result;
	}

	private ServerStatus getJobServerStatus0(String jobName, String executorName,
			CuratorFrameworkOp curatorFrameworkOp) {
		String status = curatorFrameworkOp.getData(JobNodePath.getServerNodePath(jobName, executorName, "status"));
		return ServerStatus.getServerStatus(status);
	}

	private JobServer getJobServer(String jobName, String leaderIp, String executorName,
			CuratorRepository.CuratorFrameworkOp curatorFrameworkOp) {
		JobServer result = new JobServer();
		result.setExecutorName(executorName);
		result.setIp(curatorFrameworkOp.getData(JobNodePath.getServerNodePath(jobName, executorName, "ip")));
		result.setVersion(curatorFrameworkOp.getData(JobNodePath.getServerNodePath(jobName, executorName, "version")));
		String processSuccessCount = curatorFrameworkOp
				.getData(JobNodePath.getServerNodePath(jobName, executorName, "processSuccessCount"));
		result.setProcessSuccessCount(null == processSuccessCount ? 0 : Integer.parseInt(processSuccessCount));
		String processFailureCount = curatorFrameworkOp
				.getData(JobNodePath.getServerNodePath(jobName, executorName, "processFailureCount"));
		result.setProcessFailureCount(null == processFailureCount ? 0 : Integer.parseInt(processFailureCount));
		result.setSharding(
				curatorFrameworkOp.getData(JobNodePath.getServerNodePath(jobName, executorName, "sharding")));
		result.setStatus(getJobServerStatus0(jobName, executorName, curatorFrameworkOp));
		result.setLeader(executorName.equals(leaderIp));
		result.setJobVersion(getJobVersion(jobName, executorName, curatorFrameworkOp));
		result.setContainer(curatorFrameworkOp.checkExists(ExecutorNodePath.getExecutorTaskNodePath(executorName)));

		return result;
	}

	private String getJobVersion(String jobName, String executorName,
			CuratorRepository.CuratorFrameworkOp curatorFrameworkOp) {
		String jobVersion = curatorFrameworkOp
				.getData(JobNodePath.getServerNodePath(jobName, executorName, "jobVersion"));
		return jobVersion == null ? "" : jobVersion;
	}

	@Override
	public void runAtOnce(String namespace, String jobName) throws SaturnJobConsoleException {
		JobStatus jobStatus = getJobStatus(namespace, jobName);
		if (!JobStatus.READY.equals(jobStatus)) {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
					String.format("?????????(%s)?????????READY???????????????????????????", jobName));
		}
		List<JobServerStatus> jobServersStatus = getJobServersStatus(namespace, jobName);
		if (jobServersStatus != null && !jobServersStatus.isEmpty()) {
			boolean hasOnlineExecutor = false;
			CuratorFrameworkOp curatorFrameworkOp = registryCenterService.getCuratorFrameworkOp(namespace);
			for (JobServerStatus jobServerStatus : jobServersStatus) {
				if (ServerStatus.ONLINE.equals(jobServerStatus.getServerStatus())) {
					hasOnlineExecutor = true;
					String executorName = jobServerStatus.getExecutorName();
					String path = JobNodePath.getRunOneTimePath(jobName, executorName);
					if (curatorFrameworkOp.checkExists(path)) {
						curatorFrameworkOp.delete(path);
					}
					curatorFrameworkOp.create(path, "null");
					log.info("runAtOnce namespace:{}, jobName:{}, executorName:{}", namespace, jobName, executorName);
				}
			}
			if (!hasOnlineExecutor) {
				throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST, "??????ONLINE???executor?????????????????????");
			}
		} else {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
					String.format("??????executor???????????????(%s)?????????????????????", jobName));
		}
	}

	@Override
	public void stopAtOnce(String namespace, String jobName) throws SaturnJobConsoleException {
		JobStatus jobStatus = getJobStatus(namespace, jobName);
		if (!JobStatus.STOPPING.equals(jobStatus)) {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
					String.format("?????????(%s)?????????STOPPING???????????????????????????", jobName));
		}
		List<String> jobServerList = getJobServerList(namespace, jobName);
		if (jobServerList != null && !jobServerList.isEmpty()) {
			CuratorFrameworkOp curatorFrameworkOp = registryCenterService.getCuratorFrameworkOp(namespace);
			for (String executorName : jobServerList) {
				String path = JobNodePath.getStopOneTimePath(jobName, executorName);
				if (curatorFrameworkOp.checkExists(path)) {
					curatorFrameworkOp.delete(path);
				}
				curatorFrameworkOp.create(path);
				log.info("stopAtOnce namespace:{}, jobName:{}, executorName:{}", namespace, jobName, executorName);
			}
		} else {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
					String.format("??????executor???????????????(%s)?????????????????????", jobName));
		}
	}

	@Override
	public List<ExecutionInfo> getExecutionStatus(String namespace, String jobName) throws SaturnJobConsoleException {
		CuratorFrameworkOp curatorFrameworkOp = registryCenterService.getCuratorFrameworkOp(namespace);
		JobConfig jobConfig = getJobConfig(namespace, jobName);
		if (!jobConfig.getEnabled() && JobStatus.STOPPED.equals(getJobStatus(jobName, curatorFrameworkOp, false))) {
			return Lists.newArrayList();
		}

		// update report node and sleep for 500ms
		updateReportNodeAndWait(jobName, curatorFrameworkOp, 500L);
		// ??????execution???????????????????????????List
		String executionNodePath = JobNodePath.getExecutionNodePath(jobName);
		List<String> shardItems = curatorFrameworkOp.getChildren(executionNodePath);
		if (shardItems == null || shardItems.isEmpty()) {
			return Lists.newArrayList();
		}

		List<ExecutionInfo> result = Lists.newArrayList();
		Map<String, String> itemExecutorMap = buildItem2ExecutorMap(jobName, curatorFrameworkOp);
		for (Map.Entry<String, String> itemExecutorEntry : itemExecutorMap.entrySet()) {
			result.add(buildExecutionInfo(jobName, itemExecutorEntry.getKey(), itemExecutorEntry.getValue(),
					curatorFrameworkOp, jobConfig));
		}

		// ??????????????????running????????????????????????????????????failover??????
		for (String shardItem : shardItems) {
			if (itemExecutorMap.containsKey(shardItem)) {
				// ?????????????????????????????????
				continue;
			}
			String runningNodePath = JobNodePath.getExecutionNodePath(jobName, shardItem, "running");
			boolean running = curatorFrameworkOp.checkExists(runningNodePath);
			if (running) {
				result.add(buildExecutionInfo(jobName, shardItem, null, curatorFrameworkOp, jobConfig));
			}
		}

		Collections.sort(result);

		return result;
	}

	@Override
	public String getExecutionLog(String namespace, String jobName, String jobItem) throws SaturnJobConsoleException {
		CuratorFrameworkOp curatorFrameworkOp = registryCenterService.getCuratorFrameworkOp(namespace);
		String jobLogNodePath = JobNodePath.getExecutionNodePath(jobName, jobItem, "jobLog");
		Stat stat = curatorFrameworkOp.getStat(jobLogNodePath);
		if (stat.getDataLength() > getMaxZnodeDataLength()) {
			log.warn("job log of job={} item={} exceed max length, will not display the original log", jobName,
					jobItem);
			return ERR_MSG_TOO_LONG_TO_DISPLAY;
		}

		return curatorFrameworkOp.getData(jobLogNodePath);
	}

	@Override
	public List<JobConfig4DB> getJobsByQueue(String queue) {
		return currentJobConfigService.findConfigByQueue(queue);
	}

	private void updateReportNodeAndWait(String jobName, CuratorFrameworkOp curatorFrameworkOp, long sleepInMill) {
		curatorFrameworkOp.update(JobNodePath.getReportPath(jobName), System.currentTimeMillis());
		try {
			Thread.sleep(sleepInMill);
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
	}

	private ExecutionInfo buildExecutionInfo(String jobName, String shardItem, String executorName,
			CuratorFrameworkOp curatorFrameworkOp, JobConfig jobConfig) {
		ExecutionInfo executionInfo = new ExecutionInfo();
		executionInfo.setJobName(jobName);
		executionInfo.setItem(Integer.parseInt(shardItem));

		setExecutorNameAndStatus(jobName, shardItem, executorName, curatorFrameworkOp, executionInfo, jobConfig);

		// jobMsg
		String jobMsg = curatorFrameworkOp.getData(JobNodePath.getExecutionNodePath(jobName, shardItem, "jobMsg"));
		executionInfo.setJobMsg(jobMsg);

		// timeZone
		String timeZoneStr = jobConfig.getTimeZone();
		if (StringUtils.isBlank(timeZoneStr)) {
			timeZoneStr = SaturnConstants.TIME_ZONE_ID_DEFAULT;
		}
		executionInfo.setTimeZone(timeZoneStr);
		TimeZone timeZone = TimeZone.getTimeZone(timeZoneStr);
		// last begin time
		String lastBeginTime = curatorFrameworkOp
				.getData(JobNodePath.getExecutionNodePath(jobName, shardItem, "lastBeginTime"));
		executionInfo.setLastBeginTime(SaturnConsoleUtils.parseMillisecond2DisplayTime(lastBeginTime, timeZone));
		// next fire time, ignore if jobType is Msg
		JobType jobType = JobType.getJobType(jobConfig.getJobType());
		if (JobType.isCron(jobType)) {
			String nextFireTime = curatorFrameworkOp
					.getData(JobNodePath.getExecutionNodePath(jobName, shardItem, "nextFireTime"));
			executionInfo.setNextFireTime(SaturnConsoleUtils.parseMillisecond2DisplayTime(nextFireTime, timeZone));
		} else {
			executionInfo.setNextFireTime(null);
		}
		// last complete time
		String lastCompleteTime = curatorFrameworkOp
				.getData(JobNodePath.getExecutionNodePath(jobName, shardItem, "lastCompleteTime"));
		if (lastCompleteTime != null) {
			long lastCompleteTimeLong = Long.parseLong(lastCompleteTime);
			if (lastBeginTime == null) {
				executionInfo.setLastCompleteTime(
						SaturnConsoleUtils.parseMillisecond2DisplayTime(lastCompleteTime, timeZone));
			} else {
				long lastBeginTimeLong = Long.parseLong(lastBeginTime);
				if (lastCompleteTimeLong >= lastBeginTimeLong) {
					executionInfo.setLastCompleteTime(
							SaturnConsoleUtils.parseMillisecond2DisplayTime(lastCompleteTime, timeZone));

					executionInfo.setLastTimeConsumedInSec((lastCompleteTimeLong - lastBeginTimeLong) / 1000d);
				}
			}
		}

		return executionInfo;
	}

	private void setExecutorNameAndStatus(String jobName, String shardItem, String executorName,
			CuratorFrameworkOp curatorFrameworkOp, ExecutionInfo executionInfo, JobConfig jobConfig) {
		boolean isEnabledReport = jobConfig.getEnabledReport();
		if (!isEnabledReport) {
			executionInfo.setExecutorName(executorName);
			executionInfo.setStatus(ExecutionStatus.BLANK);
			return;
		}

		boolean isCompleted = false;
		String completedNodePath = JobNodePath.getCompletedNodePath(jobName, shardItem);
		String completedData = curatorFrameworkOp.getData(completedNodePath);
		if (completedData != null) {
			isCompleted = true;
			executionInfo.setExecutorName(StringUtils.isNotBlank(completedData) ? completedData : executorName);
			// ???????????????????????????????????????failed??????timeout
		}

		String failedNodePath = JobNodePath.getFailedNodePath(jobName, shardItem);
		if (curatorFrameworkOp.checkExists(failedNodePath)) {
			if (isCompleted) {
				executionInfo.setStatus(ExecutionStatus.FAILED);
			} else {
				log.warn(ERR_MSG_PENDING_STATUS, jobName, shardItem, executorName,
						"no completed node found but only failed node");
				executionInfo.setExecutorName(executorName);
				executionInfo.setStatus(ExecutionStatus.PENDING);
			}
			return;
		}

		String timeoutNodePath = JobNodePath.getTimeoutNodePath(jobName, shardItem);
		if (curatorFrameworkOp.checkExists(timeoutNodePath)) {
			if (isCompleted) {
				executionInfo.setStatus(ExecutionStatus.TIMEOUT);
			} else {
				log.warn(ERR_MSG_PENDING_STATUS, jobName, shardItem, executorName,
						"no completed node found but only timeout node");
				executionInfo.setExecutorName(executorName);
				executionInfo.setStatus(ExecutionStatus.PENDING);
			}
			return;
		}

		// ??????completed????????????timeout/failed????????????????????????????????????
		if (isCompleted) {
			executionInfo.setStatus(ExecutionStatus.COMPLETED);
			return;
		}

		boolean isRunning = false;
		String runningNodePath = JobNodePath.getRunningNodePath(jobName, shardItem);
		String runningData = curatorFrameworkOp.getData(runningNodePath);
		if (runningData != null) {
			isRunning = true;
			executionInfo.setExecutorName(StringUtils.isBlank(runningData) ? executorName : runningData);
			long mtime = curatorFrameworkOp.getMtime(runningNodePath);
			executionInfo.setTimeConsumed((new Date().getTime() - mtime) / 1000);
			executionInfo.setStatus(ExecutionStatus.RUNNING);
			// ?????????????????????????????????????????????failover
		}

		String failoverNodePath = JobNodePath.getFailoverNodePath(jobName, shardItem);
		String failoverData = curatorFrameworkOp.getData(failoverNodePath);
		if (failoverData != null) {
			// ?????????failover???????????????executorName
			executionInfo.setExecutorName(failoverData);
			executionInfo.setFailover(true);
			// ?????????failover?????????running?????????????????????????????????pending??????
			if (!isRunning) {
				log.warn(ERR_MSG_PENDING_STATUS, jobName, shardItem, executorName,
						"no running node found but only failover node");
				executionInfo.setStatus(ExecutionStatus.PENDING);
			}

			return;
		}

		if (!isRunning) {
			log.warn(ERR_MSG_PENDING_STATUS, jobName, shardItem, executorName,
					"no running node or completed node found");
			executionInfo.setStatus(ExecutionStatus.PENDING);
		}
	}

	private Map<String, String> buildItem2ExecutorMap(String jobName,
			CuratorRepository.CuratorFrameworkOp curatorFrameworkOp) {

		String serverNodePath = JobNodePath.getServerNodePath(jobName);
		List<String> servers = curatorFrameworkOp.getChildren(serverNodePath);

		if (servers == null || servers.isEmpty()) {
			return Maps.newHashMap();
		}

		Map<String, String> resultMap = new HashMap<>();
		for (String server : servers) {
			resolveShardingData(jobName, curatorFrameworkOp, resultMap, server);
		}
		return resultMap;
	}

	private void resolveShardingData(String jobName, CuratorFrameworkOp curatorFrameworkOp,
			Map<String, String> resultMap, String server) {
		String shardingData = curatorFrameworkOp.getData(JobNodePath.getServerSharding(jobName, server));
		if (StringUtils.isBlank(shardingData)) {
			return;
		}

		String[] shardingValues = shardingData.split(",");
		for (String value : shardingValues) {
			if (StringUtils.isBlank(value)) {
				continue;
			}

			resultMap.put(value.trim(), server);
		}
	}

	private void validateShardingItemFormat(JobConfig jobConfig) throws SaturnJobConsoleException {
		String parameters = jobConfig.getShardingItemParameters();
		String[] kvs = parameters.trim().split(",");
		for (int i = 0; i < kvs.length; i++) {
			String keyAndValue = kvs[i];
			if (!keyAndValue.contains("=")) {
				throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST, String.format("????????????'%s'????????????", keyAndValue));
			}
			String key = keyAndValue.trim().split("=")[0].trim();
			boolean isNumeric = StringUtils.isNumeric(key);
			if (!isNumeric) {
				throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST,
						String.format("????????????'%s'????????????", jobConfig.getShardingItemParameters()));
			}
		}
	}

	/**
	 * ???????????????????????????
	 * 
	 * @param namespace
	 * @param jobNames      ?????????????????????????????????
	 * @param oldGroupNames ???????????????????????????
	 * @param newGroupNames ???????????????????????????
	 * @param userName      ?????????
	 */
	@Transactional(rollbackFor = Exception.class)
	@Override
	public void batchSetGroups(String namespace, List<String> jobNames, List<String> oldGroupNames,
			List<String> newGroupNames, String userName) throws SaturnJobConsoleException {
		if (CollectionUtils.isEmpty(jobNames)) {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST, "???????????????????????????");
		}
		if (CollectionUtils.isEmpty(oldGroupNames) && CollectionUtils.isEmpty(newGroupNames)) {
			return;
		}

		List<String> oldGroupNamesTemp = null;
		List<String> newGroupNamesTemp = null;

		Pattern pattern = Pattern.compile("[`!@#$%^???@#???%??????&*??????|{}???????????????????????????????????? ]");
		if (!CollectionUtils.isEmpty(oldGroupNames)) {
			for (String groupName : oldGroupNames) {
				validateGroupName(groupName, pattern);
			}
			oldGroupNamesTemp = new ArrayList<>(oldGroupNames);
		}
		if (!CollectionUtils.isEmpty(newGroupNames)) {
			for (String groupName : newGroupNames) {
				validateGroupName(groupName, pattern);
			}
			newGroupNamesTemp = new ArrayList<>(newGroupNames);
		}

		// ?????????????????????????????????????????????
		if (!CollectionUtils.isEmpty(oldGroupNames) && !CollectionUtils.isEmpty(newGroupNames)) {
			Collections.sort(oldGroupNames);
			Collections.sort(newGroupNames);
			if (oldGroupNames.toString().equals(newGroupNames.toString())) {
				return;
			}
		}

		// ??? oldGroupNamesTemp ??? newGroupNamesTemp ?????????(oldGroupNamesTemp
		// ????????????newGroupNamesTemp ?????????????????????)?????????????????????
		if (!CollectionUtils.isEmpty(oldGroupNamesTemp) && !CollectionUtils.isEmpty(newGroupNamesTemp)) {
			oldGroupNamesTemp.removeAll(newGroupNamesTemp);
		}
		// ????????????????????????????????????????????????????????????????????????
		if (!CollectionUtils.isEmpty(oldGroupNamesTemp)) {
			for (String groupName : oldGroupNamesTemp) {
				currentJobConfigService.batchSetGroups(namespace, jobNames, groupName, userName);
			}
		}

		// ??? newGroupNames ??? oldGroupNames ?????????(newGroupNames ????????????oldGroupNames
		// ?????????????????????)?????????????????????
		if (!CollectionUtils.isEmpty(newGroupNames) && !CollectionUtils.isEmpty(oldGroupNames)) {
			newGroupNames.removeAll(oldGroupNames);
		}
		// ??????????????????????????????????????????????????????????????????????????????????????????????????????
		if (!CollectionUtils.isEmpty(newGroupNames)) {
			for (String groupName : newGroupNames) {
				currentJobConfigService.addToGroups(namespace, jobNames, groupName, userName);
			}
		}
	}

}
