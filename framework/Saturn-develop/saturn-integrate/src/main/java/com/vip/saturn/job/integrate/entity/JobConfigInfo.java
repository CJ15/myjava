/**
 * Copyright 2016 vip.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 *  the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 * </p>
 **/

package com.vip.saturn.job.integrate.entity;

public class JobConfigInfo {

	private String namespace;

	private String jobName;

	private String perferList;

	public JobConfigInfo() {

	}

	public JobConfigInfo(String namespace, String jobName, String perferList) {
		this.namespace = namespace;
		this.jobName = jobName;
		this.perferList = perferList;
	}

	public String getNamespace() {
		return namespace;
	}

	public void setNamespace(String namespace) {
		this.namespace = namespace;
	}

	public String getJobName() {
		return jobName;
	}

	public void setJobName(String jobName) {
		this.jobName = jobName;
	}

	public String getPerferList() {
		return perferList;
	}

	public void setPerferList(String perferList) {
		this.perferList = perferList;
	}

	@Override
	public String toString() {
		return "JobConfigInfo [namespace=" + namespace + ", jobName=" + jobName + ", perferList=" + perferList + "]";
	}
}
