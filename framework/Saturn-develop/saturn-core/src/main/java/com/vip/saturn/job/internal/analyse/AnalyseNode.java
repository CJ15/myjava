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

/**
 * 节点有作业的执行总次数与失败总次数
 */
public final class AnalyseNode {

	public static final String ROOT = "analyse";

	/** 执行总次数 **/
	public static final String PROCESS_COUNT = ROOT + "/processCount";

	/** 失败总次数 **/
	public static final String ERROR_COUNT = ROOT + "/errorCount";

	/** reset 统计数据 **/
	public static final String RESET = ROOT + "/reset";

}
