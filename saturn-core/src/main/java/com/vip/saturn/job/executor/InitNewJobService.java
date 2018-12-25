package com.vip.saturn.job.executor;

import com.google.common.collect.Maps;
import com.vip.saturn.job.basic.JobScheduler;
import com.vip.saturn.job.exception.JobException;
import com.vip.saturn.job.exception.JobInitAlarmException;
import com.vip.saturn.job.internal.config.ConfigurationNode;
import com.vip.saturn.job.internal.config.JobConfiguration;
import com.vip.saturn.job.internal.storage.JobNodePath;
import com.vip.saturn.job.reg.base.CoordinatorRegistryCenter;
import com.vip.saturn.job.threads.SaturnThreadFactory;
import com.vip.saturn.job.utils.AlarmUtils;
import com.vip.saturn.job.utils.LogEvents;
import com.vip.saturn.job.utils.LogUtils;
import com.vip.saturn.job.utils.SystemEnvProperties;
import org.apache.commons.lang3.StringUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.TreeCache;
import org.apache.curator.framework.recipes.cache.TreeCacheEvent;
import org.apache.curator.framework.recipes.cache.TreeCacheListener;
import org.apache.curator.utils.CloseableExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

import static com.vip.saturn.job.executor.SaturnExecutorService.WAIT_JOBCLASS_ADDED_COUNT;
import static com.vip.saturn.job.utils.SystemEnvProperties.VIP_SATURN_DISABLE_CLASS_NOT_FOUND_GROUP_NAME;

/**
 * @author hebelala
 */
public class InitNewJobService {

	private static final Logger log = LoggerFactory.getLogger(InitNewJobService.class);
	/**
	 * record the alarm message hashcode, permanently saved, used for just raising alarm one time for one type exception
	 */
	private static final ConcurrentMap<String, ConcurrentMap<String, Set<Integer>>> JOB_INIT_FAILED_RECORDS = new ConcurrentHashMap<>();
	private SaturnExecutorService saturnExecutorService;
	private String executorName;
	private CoordinatorRegistryCenter regCenter;
	private TreeCache treeCache;
	private ExecutorService executorService;
	private List<String> jobNames = new ArrayList<>();

	public InitNewJobService(SaturnExecutorService saturnExecutorService) {
		this.saturnExecutorService = saturnExecutorService;
		this.executorName = saturnExecutorService.getExecutorName();
		this.regCenter = saturnExecutorService.getCoordinatorRegistryCenter();
		JOB_INIT_FAILED_RECORDS.putIfAbsent(executorName, new ConcurrentHashMap<String, Set<Integer>>());
	}

	public void start() throws Exception {
		treeCache = TreeCache.newBuilder((CuratorFramework) regCenter.getRawClient(), JobNodePath.ROOT).setExecutor(
				new CloseableExecutorService(Executors
						.newSingleThreadExecutor(new SaturnThreadFactory(executorName + "-$Jobs-watcher", false)),
						true)).setMaxDepth(1).build();
		executorService = Executors
				.newSingleThreadExecutor(new SaturnThreadFactory(executorName + "-initNewJob-thread", false));
		treeCache.getListenable().addListener(new InitNewJobListener(), executorService);
		treeCache.start();
	}

	public void shutdown() {
		try {
			if (treeCache != null) {
				treeCache.close();
			}
		} catch (Throwable t) {
			LogUtils.error(log, LogEvents.ExecutorEvent.INIT_OR_SHUTDOWN, t.toString(), t);
		}
		try {
			if (executorService != null && !executorService.isTerminated()) {
				executorService.shutdownNow();
				int count = 0;
				while (!executorService.awaitTermination(50, TimeUnit.MILLISECONDS)) {
					if (++count == 4) {
						LogUtils.info(log, LogEvents.ExecutorEvent.INIT_OR_SHUTDOWN,
								"InitNewJob executorService try to shutdown now");
						count = 0;
					}
					executorService.shutdownNow();
				}
			}
		} catch (Throwable t) {
			LogUtils.error(log, LogEvents.ExecutorEvent.INIT_OR_SHUTDOWN, t.toString(), t);
		}
	}

	public boolean removeJobName(String jobName) {
		return jobNames.remove(jobName);
	}

	class InitNewJobListener implements TreeCacheListener {

		@Override
		public void childEvent(CuratorFramework client, TreeCacheEvent event) throws Exception {
			if (event == null) {
				return;
			}

			ChildData data = event.getData();
			if (data == null) {
				return;
			}

			String path = data.getPath();
			if (path == null || path.equals(JobNodePath.ROOT)) {
				return;
			}

			TreeCacheEvent.Type type = event.getType();
			if (type == null || !type.equals(TreeCacheEvent.Type.NODE_ADDED)) {
				return;
			}

			String jobName = StringUtils.substringAfterLast(path, "/");
			String jobClassPath = JobNodePath.getNodeFullPath(jobName, ConfigurationNode.JOB_CLASS);
			// wait 5 seconds at most until jobClass created.
			for (int i = 0; i < WAIT_JOBCLASS_ADDED_COUNT; i++) {
				if (!regCenter.isExisted(jobClassPath)) {
					Thread.sleep(200L);
					continue;
				}

				LogUtils.info(log, jobName, "new job: {} 's jobClass created event received", jobName);

				if (!jobNames.contains(jobName)) {
					if (initJobScheduler(jobName)) {
						jobNames.add(jobName);
						LogUtils.info(log, jobName, "the job {} initialize successfully", jobName);
					}
				} else {
					LogUtils.warn(log, jobName,
							"the job {} is unnecessary to initialize, because it's already existing", jobName);
				}
				break;
			}
		}

		public Map<String, Object> constructAlarmInfo(String namespace, String jobName, String executorName,
				String alarmMessage) {
			Map<String, Object> alarmInfo = new HashMap<>();

			alarmInfo.put("jobName", jobName);
			alarmInfo.put("executorName", executorName);
			alarmInfo.put("name", "Saturn Event");
			alarmInfo.put("title", String.format("JOB_INIT_FAIL:%s", jobName));
			alarmInfo.put("level", "CRITICAL");
			alarmInfo.put("message", alarmMessage);

			Map<String, String> customFields = Maps.newHashMap();
			customFields.put("sourceType", "saturn");
			customFields.put("domain", namespace);
			alarmInfo.put("additionalInfo", customFields);

			return alarmInfo;
		}

		private boolean initJobScheduler(String jobName) {
			try {
				LogUtils.info(log, jobName, "start to initialize the new job");
				JOB_INIT_FAILED_RECORDS.get(executorName).putIfAbsent(jobName, new HashSet<Integer>());
				JobConfiguration jobConfig = new JobConfiguration(regCenter, jobName);
				if (jobConfig.getSaturnJobClass() == null) {
					throw new JobException(
							"unexpected error, the saturnJobClass cannot be null, jobName is %s, jobType is %s",
							jobName, jobConfig.getJobType());
				}
				if (jobConfig.isDeleting()) {
					String serverNodePath = JobNodePath.getServerNodePath(jobName, executorName);
					regCenter.remove(serverNodePath);
					LogUtils.warn(log, jobName, "the job is on deleting");
					return false;
				}
				JobScheduler scheduler = new JobScheduler(regCenter, jobConfig);
				scheduler.setSaturnExecutorService(saturnExecutorService);
				scheduler.init();
				// clear previous records when initialize job successfully
				JOB_INIT_FAILED_RECORDS.get(executorName).get(jobName).clear();
				return true;
			} catch (JobInitAlarmException e) {
				if (!SystemEnvProperties.VIP_SATURN_DISABLE_JOB_INIT_FAILED_ALARM) {
					// no need to log exception stack as it should be logged in the original happen place
					String groupNameFromSystem = StringUtils.trim(getGroupNameFromSystemPropertyOrEnv());
					String groupNameFromZk = StringUtils.trim(getGroupNameFromZk(jobName));
					boolean needSkipClassNotFoundException = hasTheSameGroupName(groupNameFromSystem, groupNameFromZk);
					if (e.getCause() instanceof ClassNotFoundException
							&& SystemEnvProperties.VIP_SATURN_DISABLE_CLASS_NOT_FOUND_ALARM
							&& needSkipClassNotFoundException) {
						//no need to raiseAlarm if class not found and VIP_SATURN_DISABLE_CLASS_NOT_FOUND_ALARM is true
						LogUtils.debug(log, jobName, "job initialize, class not found");
					} else {
						raiseAlarmForJobInitFailed(jobName, e);
					}
				}
			} catch (Throwable t) {
				LogUtils.warn(log, jobName, "job initialize failed, but will not stop the init process", t);
			}

			return false;
		}

		private boolean hasTheSameGroupName(String groupNameFromSystem, String groupNameFromZk) {
			if (StringUtils.isBlank(groupNameFromSystem) || StringUtils.isBlank(groupNameFromZk)) {
				return false;
			}
			return StringUtils.equals(groupNameFromSystem, groupNameFromZk);
		}

		private String getGroupNameFromSystemPropertyOrEnv() {
			return System.getProperty(VIP_SATURN_DISABLE_CLASS_NOT_FOUND_GROUP_NAME,
					System.getenv(VIP_SATURN_DISABLE_CLASS_NOT_FOUND_GROUP_NAME));
		}

		private String getGroupNameFromZk(String jobName) {
			return regCenter.get(JobNodePath.getConfigNodePath(jobName, "groups"));
		}

		private void raiseAlarmForJobInitFailed(String jobName, JobInitAlarmException jobInitAlarmException) {
			String message = jobInitAlarmException.getMessage();
			int messageHashCode = message.hashCode();
			Set<Integer> records = JOB_INIT_FAILED_RECORDS.get(executorName).get(jobName);
			if (!records.contains(messageHashCode)) {
				try {
					String namespace = regCenter.getNamespace();
					AlarmUtils.raiseAlarm(constructAlarmInfo(namespace, jobName, executorName, message), namespace);
					records.add(messageHashCode);
				} catch (Exception e) {
					LogUtils.error(log, jobName, "exception throws during raise alarm for job init fail", e);
				}
			} else {
				LogUtils.info(log, jobName,
						"job initialize failed but will not raise alarm as such kind of alarm already been raise before");
			}
		}
	}

	public static boolean containsJobInitFailedRecord(String executorName, String jobName, String message) {
		return JOB_INIT_FAILED_RECORDS.get(executorName).get(jobName).contains(message.hashCode());
	}

}
