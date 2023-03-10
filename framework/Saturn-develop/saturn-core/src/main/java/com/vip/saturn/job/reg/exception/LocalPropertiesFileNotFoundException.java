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

package com.vip.saturn.job.reg.exception;

import java.io.IOException;

/**
 * 找不到本地属性文件所抛出的异常.
 * 
 * 
 */
public final class LocalPropertiesFileNotFoundException extends RegException {

	private static final long serialVersionUID = 316825485808885546L;

	private static final String MSG = "CAN NOT found local properties files: [%s].";

	public LocalPropertiesFileNotFoundException(final String localPropertiesFileName) {
		super(MSG, localPropertiesFileName);
	}

	public LocalPropertiesFileNotFoundException(final IOException cause) {
		super(cause);
	}
}
