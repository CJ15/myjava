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

package com.vip.saturn.job.console.controller.gui;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.vip.saturn.job.console.aop.annotation.Audit;
import com.vip.saturn.job.console.aop.annotation.AuditParam;
import com.vip.saturn.job.console.controller.SuccessResponseEntity;
import com.vip.saturn.job.console.domain.*;
import com.vip.saturn.job.console.exception.SaturnJobConsoleException;
import com.vip.saturn.job.console.mybatis.entity.JobConfig4DB;
import com.vip.saturn.job.console.service.AlarmStatisticsService;
import com.vip.saturn.job.console.service.JobService;
import com.vip.saturn.job.console.utils.*;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;

import static com.vip.saturn.job.console.exception.SaturnJobConsoleException.ERROR_CODE_BAD_REQUEST;

/**
 * Job overview related operations.
 */
@RequestMapping("/console/namespaces/{namespace:.+}/jobs")
public class JobOverviewController extends AbstractGUIController {

	private static final Logger log = LoggerFactory.getLogger(JobOverviewController.class);

	private static final String QUERY_CONDITION_STATUS = "status";

	private static final String QUERY_CONDITION_GROUP = "groups";

	@Resource
	private JobService jobService;

	@Resource
	private AlarmStatisticsService alarmStatisticsService;

	/**
	 * ??????????????????????????????????????????????????????
	 * @param namespace ??????
	 * @return ????????????
	 */
	@ApiResponses(value = {@ApiResponse(code = 200, message = "Success/Fail", response = RequestResult.class)})
	@GetMapping
	public SuccessResponseEntity getJobsWithCondition(final HttpServletRequest request, @PathVariable String namespace,
			@RequestParam Map<String, Object> condition, @RequestParam(required = false, defaultValue = "1") int page,
			@RequestParam(required = false, defaultValue = "25") int size) throws SaturnJobConsoleException {
		if (condition.containsKey(QUERY_CONDITION_STATUS)) {
			String statusStr = checkAndGetParametersValueAsString(condition, QUERY_CONDITION_STATUS, false);
			JobStatus jobStatus = JobStatus.getJobStatus(statusStr);
			return new SuccessResponseEntity(getJobOverviewByStatusAndPage(namespace, jobStatus, condition, page, size));
		}
		return new SuccessResponseEntity(getJobOverviewByPage(namespace, condition, page, size));
	}

	/**
	 * ???????????????????????????????????????????????????????????????
	 * @param namespace ??????
	 * @return ??????????????????????????????????????????????????????
	 */
	@ApiResponses(value = {@ApiResponse(code = 200, message = "Success/Fail", response = RequestResult.class)})
	@GetMapping(value = "/counts")
	public SuccessResponseEntity countJobsStatus(final HttpServletRequest request, @PathVariable String namespace)
			throws SaturnJobConsoleException {
		return new SuccessResponseEntity(countJobOverviewVo(namespace));
	}

	/**
	 * ?????????????????????????????????
	 * @param namespace ??????
	 * @return ??????????????????
	 */
	@ApiResponses(value = {@ApiResponse(code = 200, message = "Success/Fail", response = RequestResult.class)})
	@GetMapping(value = "/names")
	public SuccessResponseEntity getJobNames(@PathVariable String namespace) throws SaturnJobConsoleException {
		return new SuccessResponseEntity(jobService.getJobNames(namespace));
	}

	@ApiResponses(value = {@ApiResponse(code = 200, message = "Success/Fail", response = RequestResult.class)})
	@GetMapping(value = "/sharding/status")
	public SuccessResponseEntity getJobsShardingStatus(@PathVariable String namespace,
			@RequestParam(required = false) List<String> jobNames) throws SaturnJobConsoleException {
		Map<String, String> jobShardingMap = new HashMap<>();
		if (!CollectionUtils.isEmpty(jobNames)) {
			for (String jobName : jobNames) {
				JobStatus jobStatus = jobService.getJobStatus(namespace, jobName);
				boolean isAllocated = !JobStatus.STOPPED.equals(jobStatus) && jobService
						.isJobShardingAllocatedExecutor(namespace, jobName);
				if (isAllocated) {
					jobShardingMap.put(jobName, "?????????");
				} else {
					jobShardingMap.put(jobName, "?????????");
				}
			}
		}
		return new SuccessResponseEntity(jobShardingMap);
	}


	private JobOverviewVo getJobOverviewByPage(String namespace, Map<String, Object> condition, int page, int size)
			throws SaturnJobConsoleException {
		JobOverviewVo jobOverviewVo = new JobOverviewVo();
		try {
			preHandleCondition(condition);

			List<JobConfig4DB> unSystemJobs = jobService.getUnSystemJobsWithCondition(namespace, condition, page, size);
			if (unSystemJobs == null || unSystemJobs.isEmpty()) {
				jobOverviewVo.setJobs(Lists.<JobOverviewJobVo>newArrayList());
				jobOverviewVo.setTotalNumber(0);
				return jobOverviewVo;
			}

			List<JobOverviewJobVo> jobOverviewList = updateJobOverviewDetail(namespace, unSystemJobs, null);

			jobOverviewVo.setJobs(jobOverviewList);
			jobOverviewVo.setTotalNumber(jobService.countUnSystemJobsWithCondition(namespace, condition));
		} catch (SaturnJobConsoleException e) {
			throw e;
		} catch (Exception e) {
			throw new SaturnJobConsoleException(e);
		}

		return jobOverviewVo;
	}

	private JobOverviewVo getJobOverviewByStatusAndPage(String namespace, JobStatus jobStatus,
			Map<String, Object> condition, int page, int size) throws SaturnJobConsoleException {
		JobOverviewVo jobOverviewVo = new JobOverviewVo();
		try {
			preHandleStatusAndCondition(condition, jobStatus);

			List<JobConfig4DB> unSystemJobs = jobService.getUnSystemJobsWithCondition(namespace, condition, page, size);
			if (unSystemJobs == null || unSystemJobs.isEmpty()) {
				jobOverviewVo.setJobs(Lists.<JobOverviewJobVo>newArrayList());
				jobOverviewVo.setTotalNumber(0);
				return jobOverviewVo;
			}

			Pageable pageable = PageableUtil.generatePageble(page, size);
			// ??? jobStatus ???null??????????????????????????????????????????????????????????????????
			List<JobConfig4DB> targetJobs = jobStatus == null ? unSystemJobs : getJobSubListByPage(unSystemJobs, pageable);
			List<JobOverviewJobVo> jobOverviewList = updateJobOverviewDetail(namespace, targetJobs, jobStatus);

			jobOverviewVo.setJobs(jobOverviewList);
			jobOverviewVo.setTotalNumber(jobService.countUnSystemJobsWithCondition(namespace, condition));
		} catch (SaturnJobConsoleException e) {
			throw e;
		} catch (Exception e) {
			throw new SaturnJobConsoleException(e);
		}

		return jobOverviewVo;
	}


	private void preHandleCondition(Map<String, Object> condition) {
		if (condition.containsKey(QUERY_CONDITION_GROUP) && SaturnConstants.NO_GROUPS_LABEL
				.equals(condition.get(QUERY_CONDITION_GROUP))) {
			condition.put(QUERY_CONDITION_GROUP, "");
		}
	}

	private void preHandleStatusAndCondition(Map<String, Object> condition, JobStatus jobStatus) {
		condition.put("jobStatus", jobStatus);
		if (JobStatus.STOPPED.equals(jobStatus) || JobStatus.STOPPING.equals(jobStatus)) {
			condition.put("isEnabled", SaturnConstants.JOB_IS_DISABLE);
		} else {
			condition.put("isEnabled", SaturnConstants.JOB_IS_ENABLE);
		}
	}

	private void updateAbnormalJobSizeInOverview(String namespace, JobOverviewVo jobOverviewVo) {
		try {
			List<AbnormalJob> abnormalJobList = alarmStatisticsService.getAbnormalJobListByNamespace(namespace);
			List<DisabledTimeoutAlarmJob> disabledTimeoutJobList = alarmStatisticsService.getDisabledTimeoutJobListByNamespace(namespace);
			jobOverviewVo.setAbnormalNumber(abnormalJobList.size() + disabledTimeoutJobList.size());
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
	}

	private List<JobOverviewJobVo> updateJobOverviewDetail(String namespace, List<JobConfig4DB> unSystemJobs,
			JobStatus jobStatus) {
		List<JobOverviewJobVo> result = Lists.newArrayList();
		for (JobConfig4DB jobConfig : unSystemJobs) {
			try {
				jobConfig.setDefaultValues();

				JobOverviewJobVo jobOverviewJobVo = new JobOverviewJobVo();
				SaturnBeanUtils.copyProperties(jobConfig, jobOverviewJobVo);

				updateJobTypesInOverview(jobConfig, jobOverviewJobVo);

				if (jobStatus == null) {
					jobOverviewJobVo.setStatus(jobService.getJobStatus(namespace, jobConfig));
				} else {
					jobOverviewJobVo.setStatus(jobStatus);
				}
				result.add(jobOverviewJobVo);
			} catch (Exception e) {
				log.error("list job " + jobConfig.getJobName() + " error", e);
			}
		}

		return result;
	}

	private void updateJobTypesInOverview(JobConfig jobConfig, JobOverviewJobVo jobOverviewJobVo) {
		JobType jobType = JobType.getJobType(jobConfig.getJobType());
		if (JobType.UNKNOWN_JOB == jobType) {
			if (jobOverviewJobVo.getJobClass() != null
					&& jobOverviewJobVo.getJobClass().indexOf("SaturnScriptJob") != -1) {
				jobOverviewJobVo.setJobType(JobType.SHELL_JOB.name());
			} else {
				jobOverviewJobVo.setJobType(JobType.JAVA_JOB.name());
			}
		}
	}

	protected List getJobSubListByPage(List unSystemJobs, Pageable pageable) {
		int totalCount = unSystemJobs.size();
		int offset = pageable.getOffset();
		int end = offset + pageable.getPageSize();
		int fromIndex = totalCount >= offset ? offset : -1;
		int toIndex = totalCount >= end ? end : totalCount;
		if (fromIndex == -1 || fromIndex > toIndex) {
			return Lists.newArrayList();
		}
		return unSystemJobs.subList(fromIndex, toIndex);
	}

	private JobOverviewVo countJobOverviewVo(String namespace) throws SaturnJobConsoleException {
		JobOverviewVo jobOverviewVo = new JobOverviewVo();
		jobOverviewVo.setTotalNumber(
				jobService.countUnSystemJobsWithCondition(namespace, Maps.<String, Object>newHashMap()));
		jobOverviewVo.setEnabledNumber(jobService.countEnabledUnSystemJobs(namespace));
		// ???????????????????????????????????????????????????????????????????????????????????????
		updateAbnormalJobSizeInOverview(namespace, jobOverviewVo);
		return jobOverviewVo;
	}

	@ApiResponses(value = {@ApiResponse(code = 200, message = "Success/Fail", response = RequestResult.class)})
	@GetMapping(value = "/groups")
	public SuccessResponseEntity getGroups(final HttpServletRequest request, @PathVariable String namespace)
			throws SaturnJobConsoleException {
		return new SuccessResponseEntity(jobService.getGroups(namespace));
	}

	@ApiResponses(value = {@ApiResponse(code = 200, message = "Success/Fail", response = RequestResult.class)})
	@Audit
	@PostMapping(value = "/{jobName}/enable")
	public SuccessResponseEntity enableJob(final HttpServletRequest request,
			@AuditParam("namespace") @PathVariable String namespace,
			@AuditParam("jobName") @PathVariable String jobName) throws SaturnJobConsoleException {
		assertIsPermitted(PermissionKeys.jobEnable, namespace);
		jobService.enableJob(namespace, jobName, getCurrentLoginUserName());
		return new SuccessResponseEntity();
	}

	@ApiResponses(value = {@ApiResponse(code = 200, message = "Success/Fail", response = RequestResult.class)})
	@Audit
	@PostMapping(value = "/enable")
	public SuccessResponseEntity batchEnableJob(final HttpServletRequest request,
			@AuditParam("namespace") @PathVariable String namespace,
			@AuditParam("jobNames") @RequestParam List<String> jobNames) throws SaturnJobConsoleException {
		assertIsPermitted(PermissionKeys.jobBatchEnable, namespace);
		String userName = getCurrentLoginUserName();
		for (String jobName : jobNames) {
			jobService.enableJob(namespace, jobName, userName);
		}
		return new SuccessResponseEntity();
	}

	@ApiResponses(value = {@ApiResponse(code = 200, message = "Success/Fail", response = RequestResult.class)})
	@Audit
	@PostMapping(value = "/{jobName}/disable")
	public SuccessResponseEntity disableJob(final HttpServletRequest request,
			@AuditParam("namespace") @PathVariable String namespace,
			@AuditParam("jobName") @PathVariable String jobName) throws SaturnJobConsoleException {
		assertIsPermitted(PermissionKeys.jobDisable, namespace);
		jobService.disableJob(namespace, jobName, getCurrentLoginUserName());
		return new SuccessResponseEntity();
	}

	@ApiResponses(value = {@ApiResponse(code = 200, message = "Success/Fail", response = RequestResult.class)})
	@Audit
	@PostMapping(value = "/disable")
	public SuccessResponseEntity batchDisableJob(final HttpServletRequest request,
			@AuditParam("namespace") @PathVariable String namespace,
			@AuditParam("jobNames") @RequestParam List<String> jobNames) throws SaturnJobConsoleException {
		assertIsPermitted(PermissionKeys.jobBatchDisable, namespace);
		String userName = getCurrentLoginUserName();
		for (String jobName : jobNames) {
			jobService.disableJob(namespace, jobName, userName);
		}
		return new SuccessResponseEntity();
	}

	@ApiResponses(value = {@ApiResponse(code = 200, message = "Success/Fail", response = RequestResult.class)})
	@Audit
	@DeleteMapping(value = "/{jobName}")
	public SuccessResponseEntity removeJob(final HttpServletRequest request,
			@AuditParam("namespace") @PathVariable String namespace,
			@AuditParam("jobName") @PathVariable String jobName) throws SaturnJobConsoleException {
		assertIsPermitted(PermissionKeys.jobRemove, namespace);
		jobService.removeJob(namespace, jobName);
		return new SuccessResponseEntity();
	}

	@ApiResponses(value = {@ApiResponse(code = 200, message = "Success/Fail", response = RequestResult.class)})
	@Audit
	@DeleteMapping
	public SuccessResponseEntity batchRemoveJob(final HttpServletRequest request,
			@AuditParam("namespace") @PathVariable String namespace,
			@AuditParam("jobNames") @RequestParam List<String> jobNames) throws SaturnJobConsoleException {
		assertIsPermitted(PermissionKeys.jobBatchRemove, namespace);
		List<String> successJobNames = new ArrayList<>();
		List<String> failJobNames = new ArrayList<>();
		for (String jobName : jobNames) {
			try {
				jobService.removeJob(namespace, jobName);
				successJobNames.add(jobName);
			} catch (Exception e) {
				failJobNames.add(jobName);
				log.info("remove job failed", e);
			}
		}
		if (!failJobNames.isEmpty()) {
			StringBuilder message = new StringBuilder();
			message.append("?????????????????????:").append(successJobNames).append("???").append("?????????????????????:").append(failJobNames);
			throw new SaturnJobConsoleException(message.toString());
		}
		return new SuccessResponseEntity();
	}

	/**
	 * ???????????????????????????Executor
	 */
	@ApiResponses(value = {@ApiResponse(code = 200, message = "Success/Fail", response = RequestResult.class)})
	@Audit
	@PostMapping(value = "/preferExecutors")
	public SuccessResponseEntity batchSetPreferExecutors(final HttpServletRequest request,
			@AuditParam("namespace") @PathVariable String namespace,
			@AuditParam("jobNames") @RequestParam List<String> jobNames,
			@AuditParam("preferList") @RequestParam String preferList) throws SaturnJobConsoleException {
		assertIsPermitted(PermissionKeys.jobBatchSetPreferExecutors, namespace);
		String userName = getCurrentLoginUserName();
		for (String jobName : jobNames) {
			jobService.setPreferList(namespace, jobName, preferList, userName);
		}
		return new SuccessResponseEntity();
	}

	@ApiResponses(value = {@ApiResponse(code = 200, message = "Success/Fail", response = RequestResult.class)})
	@GetMapping(value = "/candidateDownStream")
	public SuccessResponseEntity getCandidateDownStream(final HttpServletRequest request,
			@PathVariable String namespace) throws SaturnJobConsoleException {
		return new SuccessResponseEntity(jobService.getCandidateDownStream(namespace));
	}

	@ApiResponses(value = {@ApiResponse(code = 200, message = "Success/Fail", response = RequestResult.class)})
	@GetMapping(value = "/candidateUpStream")
	public SuccessResponseEntity getCandidateUpStream(final HttpServletRequest request, @PathVariable String namespace)
			throws SaturnJobConsoleException {
		return new SuccessResponseEntity(jobService.getCandidateUpStream(namespace));
	}

	@ApiResponses(value = {@ApiResponse(code = 200, message = "Success/Fail", response = RequestResult.class)})
	@Audit
	@PostMapping(value = "/jobs")
	public SuccessResponseEntity createJob(final HttpServletRequest request,
			@AuditParam("namespace") @PathVariable String namespace, JobConfig jobConfig)
			throws SaturnJobConsoleException {
		assertIsPermitted(PermissionKeys.jobAdd, namespace);
		jobService.addJob(namespace, jobConfig, getCurrentLoginUserName());
		return new SuccessResponseEntity();
	}

	@ApiResponses(value = {@ApiResponse(code = 200, message = "Success/Fail", response = RequestResult.class)})
	@Audit
	@PostMapping(value = "/{jobNameCopied}/copy")
	public SuccessResponseEntity copyJob(final HttpServletRequest request,
			@AuditParam("namespace") @PathVariable String namespace,
			@AuditParam("jobNameCopied") @PathVariable String jobNameCopied, JobConfig jobConfig)
			throws SaturnJobConsoleException {
		assertIsPermitted(PermissionKeys.jobCopy, namespace);
		jobService.copyJob(namespace, jobConfig, jobNameCopied, getCurrentLoginUserName());
		return new SuccessResponseEntity();
	}

	@ApiResponses(value = {@ApiResponse(code = 200, message = "Success/Fail", response = RequestResult.class)})
	@Audit
	@PostMapping(value = "/import")
	public SuccessResponseEntity importJobs(final HttpServletRequest request,
			@AuditParam("namespace") @PathVariable String namespace, @RequestParam("file") MultipartFile file)
			throws SaturnJobConsoleException {
		assertIsPermitted(PermissionKeys.jobImport, namespace);
		if (file.isEmpty()) {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST, "?????????????????????????????????");
		}
		String originalFilename = file.getOriginalFilename();
		if (originalFilename == null || !originalFilename.endsWith(".xls")) {
			throw new SaturnJobConsoleException(ERROR_CODE_BAD_REQUEST, "?????????.xls????????????");
		}
		AuditInfoContext.put("originalFilename", originalFilename);
		return new SuccessResponseEntity(jobService.importJobs(namespace, file, getCurrentLoginUserName()));
	}

	// ?????????????????????
	@ApiResponses(value = {@ApiResponse(code = 200, message = "Success/Fail", response = RequestResult.class)})
	@Audit
	@GetMapping(value = "/export")
	public void exportJobs(final HttpServletRequest request, @AuditParam("namespace") @PathVariable String namespace,
			final HttpServletResponse response) throws SaturnJobConsoleException {
		File exportJobFile = jobService.exportJobs(namespace);
		String currentTime = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
		String exportFileName = namespace + "_allJobs_" + currentTime + ".xls";
		SaturnConsoleUtils.exportFile(response, exportJobFile, exportFileName, true);
	}

	// ?????????????????????
	@ApiResponses(value = {@ApiResponse(code = 200, message = "Success/Fail", response = RequestResult.class)})
	@Audit
	@GetMapping(value = "/exportSelected")
	public void exportSelectedJobs(final HttpServletRequest request, @AuditParam("namespace") @PathVariable String namespace,
			@RequestParam(required = false) List<String> jobList, final HttpServletResponse response) throws SaturnJobConsoleException {
		// assertIsPermitted(PermissionKeys.jobExport, namespace);
		File exportJobFile = jobService.exportSelectedJobs(namespace, jobList);
		String currentTime = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
		String exportFileName = namespace + "_" + currentTime + ".xls";
		SaturnConsoleUtils.exportFile(response, exportJobFile, exportFileName, true);
	}

	@ApiResponses(value = {@ApiResponse(code = 200, message = "Success/Fail", response = RequestResult.class)})
	@GetMapping(value = "/arrangeLayout")
	public SuccessResponseEntity getArrangeLayout(final HttpServletRequest request, @PathVariable String namespace)
			throws SaturnJobConsoleException {
		return new SuccessResponseEntity(jobService.getArrangeLayout(namespace));
	}

	/**
	 * ?????????????????????????????????Executor
	 */
	@ApiResponses(value = {@ApiResponse(code = 200, message = "Success/Fail", response = RequestResult.class)})
	@GetMapping(value = "/{jobName}/executors")
	public SuccessResponseEntity getExecutors(final HttpServletRequest request, @PathVariable String namespace,
			@PathVariable String jobName) throws SaturnJobConsoleException {
		return new SuccessResponseEntity(jobService.getCandidateExecutors(namespace, jobName));
	}

	/**
	 * ???????????????????????????
	 */
	@ApiResponses(value = {@ApiResponse(code = 200, message = "Success/Fail", response = RequestResult.class)})
	@Audit
	@PostMapping(value = "/batchSetGroups")
	public SuccessResponseEntity batchSetGroups(final HttpServletRequest request,
			@AuditParam("namespace") @PathVariable String namespace,
			@AuditParam("jobNames") @RequestParam List<String> jobNames,
			@AuditParam("oldGroupNames") @RequestParam List<String> oldGroupNames,
			@AuditParam("newGroupNames") @RequestParam List<String> newGroupNames) throws SaturnJobConsoleException {
		assertIsPermitted(PermissionKeys.jobBatchSetPreferExecutors, namespace);
		String userName = getCurrentLoginUserName();
		jobService.batchSetGroups(namespace, jobNames, oldGroupNames, newGroupNames, userName);
		return new SuccessResponseEntity();
	}

	@ApiResponses(value = {@ApiResponse(code = 200, message = "Success/Fail", response = RequestResult.class)})
	@Audit
	@PostMapping("/batchRunAtOnce")
	public SuccessResponseEntity batchRunAtOnce(@AuditParam("namespace") @PathVariable String namespace,
			@AuditParam("jobNames") @RequestParam List<String> jobNames) throws SaturnJobConsoleException {
		assertIsPermitted(PermissionKeys.jobBatchRunAtOnce, namespace);
		if (!CollectionUtils.isEmpty(jobNames)) {
			for (String jobName : jobNames) {
				jobService.runAtOnce(namespace, jobName);
			}
		}
		return new SuccessResponseEntity();
	}

}
