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

package com.vip.saturn.job.trigger;

import com.vip.saturn.job.basic.AbstractElasticJob;
import com.vip.saturn.job.exception.JobException;
import com.vip.saturn.job.utils.LogEvents;
import com.vip.saturn.job.utils.LogUtils;
import org.quartz.Trigger;
import org.quartz.spi.OperableTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author chembo.huang
 */
public class SaturnWorker implements Runnable {

	static Logger log = LoggerFactory.getLogger(SaturnWorker.class);
	private final Object sigLock = new Object();
	private final AbstractElasticJob job;
	private final Triggered notTriggered;
	private volatile OperableTrigger triggerObj;
	private volatile boolean paused = false;
	private volatile Triggered triggered;
	private AtomicBoolean halted = new AtomicBoolean(false);

	public SaturnWorker(AbstractElasticJob job, Triggered notTriggered, Trigger trigger) {
		this.job = job;
		this.notTriggered = notTriggered;
		this.triggered = notTriggered;
		initTrigger(trigger);
	}

	void reInitTrigger(Trigger trigger) {
		initTrigger(trigger);
		synchronized (sigLock) {
			sigLock.notifyAll();
		}
	}

	private void initTrigger(Trigger trigger) {
		if (trigger == null) {
			return;
		}
		if (!(trigger instanceof OperableTrigger)) {
			throw new JobException("the trigger should be the instance of OperableTrigger");
		}
		this.triggerObj = (OperableTrigger) trigger;
		Date ft = this.triggerObj.computeFirstFireTime(null);
		if (ft == null) {
			LogUtils.warn(log, LogEvents.ExecutorEvent.COMMON,
					"Based on configured schedule, the given trigger {} will never fire.", trigger.getKey(),
					job.getJobName());
		}
	}

	boolean isShutDown() {
		return halted.get();
	}

	void togglePause(boolean pause) {
		synchronized (sigLock) {
			paused = pause;
			sigLock.notifyAll();
		}
	}

	void halt() {
		synchronized (sigLock) {
			halted.set(true);
			sigLock.notifyAll();
		}
	}

	void trigger(Triggered triggered) {
		synchronized (sigLock) {
			this.triggered = triggered == null ? notTriggered : triggered;
			sigLock.notifyAll();
		}
	}

	Date getNextFireTimePausePeriodEffected() {
		if (triggerObj == null) {
			return null;
		}
		triggerObj.updateAfterMisfire(null);
		Date nextFireTime = triggerObj.getNextFireTime();
		while (nextFireTime != null && job.getConfigService().isInPausePeriod(nextFireTime)) {
			nextFireTime = triggerObj.getFireTimeAfter(nextFireTime);
		}
		return nextFireTime;
	}

	@Override
	public void run() {
		while (!halted.get()) {
			try {
				synchronized (sigLock) {
					while (paused && !halted.get()) {
						try {
							sigLock.wait(1000L);
						} catch (InterruptedException ignore) {
						}
					}
					if (halted.get()) {
						break;
					}
				}
				boolean noFireTime = false; // ???????????????????????????????????????false
				long timeUntilTrigger = 1000;
				if (triggerObj != null) {
					triggerObj.updateAfterMisfire(null);
					long now = System.currentTimeMillis();
					Date nextFireTime = triggerObj.getNextFireTime();
					if (nextFireTime != null) {
						timeUntilTrigger = nextFireTime.getTime() - now;
					} else {
						noFireTime = true;
					}
				}

				while (!noFireTime && timeUntilTrigger > 2) {
					synchronized (sigLock) {
						if (halted.get()) {
							break;
						}
						if (triggered.isYes()) {
							break;
						}

						try {
							sigLock.wait(timeUntilTrigger);
						} catch (InterruptedException ignore) {
						}

						if (triggerObj != null) {
							long now = System.currentTimeMillis();
							Date nextFireTime = triggerObj.getNextFireTime();
							if (nextFireTime != null) {
								timeUntilTrigger = nextFireTime.getTime() - now;
							} else {
								noFireTime = true;
							}
						}
					}
				}
				boolean goAhead;
				Triggered currentTriggered = notTriggered;
				// ?????????????????????????????????1.???????????? 2.???????????????
				synchronized (sigLock) {
					goAhead = !halted.get() && !paused;
					// ?????????????????????????????????????????????????????????
					if (triggered.isYes()) {
						currentTriggered = triggered;
						triggered = notTriggered;
					} else if (goAhead) { // ???????????????????????????????????????????????????????????????????????????
						goAhead = goAhead && !noFireTime; // ???????????????????????????????????????????????????????????????
						if (goAhead) { // ???????????????????????????????????????
							if (triggerObj != null) {
								triggerObj.triggered(null);
							}
						} else { // ???????????????????????????????????????????????????????????????????????????CPU????????????????????????cron??????????????????????????????
							try {
								sigLock.wait(1000L);
							} catch (InterruptedException ignore) {
							}
						}
					}
				}
				if (goAhead) {
					job.execute(currentTriggered);
				}

			} catch (RuntimeException e) {
				LogUtils.error(log, job.getJobName(), e.getMessage(), e);
			}
		}

	}

}