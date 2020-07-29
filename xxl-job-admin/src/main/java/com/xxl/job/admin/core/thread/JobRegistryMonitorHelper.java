package com.xxl.job.admin.core.thread;

import com.xxl.job.admin.core.model.XxlJobGroup;
import com.xxl.job.admin.core.model.XxlJobRegistry;
import com.xxl.job.admin.core.schedule.XxlJobDynamicScheduler;
import com.xxl.job.core.enums.RegistryConfig;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * job registry instance
 * @author xuxueli 2016-10-02 19:10:24
 */
public class JobRegistryMonitorHelper {
	private static Logger logger = LoggerFactory.getLogger(JobRegistryMonitorHelper.class);

	private static JobRegistryMonitorHelper instance = new JobRegistryMonitorHelper();
	public static JobRegistryMonitorHelper getInstance(){
		return instance;
	}

	private Thread registryThread;
	private volatile boolean toStop = false;
	public void start(){
		registryThread = new Thread(new Runnable() {
			// - xxl_job_lock：任务调度锁表；
			// - xxl_job_group：执行器信息表，维护任务执行器信息；
			// - xxl_job_info：调度扩展信息表： 用于保存XXL-JOB调度任务的扩展信息，如任务分组、任务名、机器地址、执行器、执行入参和报警邮件等等；
			// - xxl_job_log：调度日志表： 用于保存XXL-JOB任务调度的历史信息，如调度结果、执行结果、调度入参、调度机器和执行器等等；
			// - xxl_job_log_report：调度日志报表：用户存储XXL-JOB任务调度日志的报表，调度中心报表功能页面会用到；
			// - xxl_job_logglue：任务GLUE日志：用于保存GLUE更新历史，用于支持GLUE的版本回溯功能；
			// - xxl_job_registry：执行器注册表，维护在线的执行器和调度中心机器地址信息；
			// - xxl_job_user：系统用户表；
			@Override
			public void run() {
				// 每30秒更新一次
				while (!toStop) {
					try {
						// auto registry group
						// 查询自动注册的执行器
						List<XxlJobGroup> groupList = XxlJobDynamicScheduler.xxlJobGroupDao.findByAddressType(0);
						if (CollectionUtils.isNotEmpty(groupList)) {

							// remove dead address (admin/executor)
							// 删除更新时间小于当前时间-90s的注册信息
							XxlJobDynamicScheduler.xxlJobRegistryDao.removeDead(RegistryConfig.DEAD_TIMEOUT);

							// fresh online address (admin/executor)
							// key 执行器名称 value 执行器注册地址
							HashMap<String, List<String>> appAddressMap = new HashMap<String, List<String>>();
							// 获取更新时间大于当前时间-90s的注册信息
							List<XxlJobRegistry> list = XxlJobDynamicScheduler.xxlJobRegistryDao.findAll(RegistryConfig.DEAD_TIMEOUT);
							if (list != null) {
								for (XxlJobRegistry item: list) {
									if (RegistryConfig.RegistType.EXECUTOR.name().equals(item.getRegistryGroup())) {
										String appName = item.getRegistryKey();
										List<String> registryList = appAddressMap.get(appName);
										if (registryList == null) {
											registryList = new ArrayList<String>();
										}

										if (!registryList.contains(item.getRegistryValue())) {
											registryList.add(item.getRegistryValue());
										}
										appAddressMap.put(appName, registryList);
									}
								}
							}

							// fresh group address
							// 根据注册信息更新执行器的IP
							for (XxlJobGroup group: groupList) {
								List<String> registryList = appAddressMap.get(group.getAppName());
								String addressListStr = null;
								if (CollectionUtils.isNotEmpty(registryList)) {
									Collections.sort(registryList);
									addressListStr = StringUtils.join(registryList, ",");
								}
								group.setAddressList(addressListStr);
								XxlJobDynamicScheduler.xxlJobGroupDao.update(group);
							}
						}
					} catch (Exception e) {
						logger.error("job registry instance error:{}", e);
					}
					try {
						TimeUnit.SECONDS.sleep(RegistryConfig.BEAT_TIMEOUT);
					} catch (InterruptedException e) {
						logger.error("job registry instance error:{}", e);
					}
				}
			}
		});
		registryThread.setDaemon(true);
		registryThread.start();
	}

	public void toStop(){
		toStop = true;
		// interrupt and wait
		registryThread.interrupt();
		try {
			registryThread.join();
		} catch (InterruptedException e) {
			logger.error(e.getMessage(), e);
		}
	}

}
