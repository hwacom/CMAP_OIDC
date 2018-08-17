package com.cmap.service.impl.jobs;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cmap.Constants;
import com.cmap.Env;
import com.cmap.service.BaseJobService;
import com.cmap.service.StepService;
import com.cmap.service.VersionService;
import com.cmap.service.vo.ConfigInfoVO;
import com.cmap.service.vo.VersionServiceVO;
import com.cmap.utils.impl.ApplicationContextUtil;

@DisallowConcurrentExecution
public class JobUploadBackupConfigFile2FTP implements BaseJobService {
	private static Logger log = LoggerFactory.getLogger(JobUploadBackupConfigFile2FTP.class);

	private VersionService versionService;

	private StepService stepService;

	@Override
	public void execute(JobExecutionContext context) throws JobExecutionException {
		try {
			JobDataMap jMap = context.getJobDetail().getJobDataMap();

			final String ftpName = jMap.getString(Constants.QUARTZ_PARA_FTP_NAME);
			final String ftpHost = jMap.getString(Constants.QUARTZ_PARA_FTP_HOST);
			final String ftpPort = jMap.getString(Constants.QUARTZ_PARA_FTP_PORT);
			final String ftpAccount = jMap.getString(Constants.QUARTZ_PARA_FTP_ACCOUNT);
			final String ftpPassword = jMap.getString(Constants.QUARTZ_PARA_FTP_PASSWORD);

			log.info("ftpName: "+ftpName);
			log.info("ftpHost: "+ftpHost);
			log.info("ftpPort: "+ftpPort);
			log.info("ftpAccount: "+ftpAccount);
			log.info("ftpPassword: "+ftpPassword);

			versionService = (VersionService)ApplicationContextUtil.getBean("versionService");

			/*
			 * 查找當天最新備份資料
			 */
			final String currentDateStr = Constants.FORMAT_YYYY_MM_DD.format(new Date());
			VersionServiceVO vsVO = new VersionServiceVO();
			vsVO.setOrderColumn("group_Name");
			vsVO.setOrderDirection("asc");
			vsVO.setQueryDateBegin1(currentDateStr);
			vsVO.setQueryDateEnd1(currentDateStr);
			vsVO.setJobTrigger(true);

			final boolean newestOnly = (Env.UPLOAD_NEWEST_BACKUP_FILE_ONLY != null && Env.UPLOAD_NEWEST_BACKUP_FILE_ONLY.equals("Y"))
					? true : false;
			vsVO.setQueryNewChkbox(newestOnly);

			List<VersionServiceVO> todayVersionInfo = versionService.findVersionInfo(vsVO, null, null);

			if (todayVersionInfo != null && !todayVersionInfo.isEmpty()) {
				stepService = (StepService)ApplicationContextUtil.getBean("stepService");

				ConfigInfoVO ciVO = new ConfigInfoVO();
				ciVO.settFtpIP(Env.TFTP_HOST_IP);
				ciVO.settFtpPort(Env.TFTP_HOST_PORT);
				ciVO.setFtpIP(Env.FTP_HOST_IP);
				ciVO.setFtpPort(Env.FTP_HOST_PORT);
				ciVO.setFtpAccount(Env.FTP_LOGIN_ACCOUNT);
				ciVO.setFtpPassword(Env.FTP_LOGIN_PASSWORD);

				List<VersionServiceVO> batchVOList = new ArrayList<>();

				for (int i=1; i<=todayVersionInfo.size(); i++) {
					batchVOList.add(todayVersionInfo.get(i-1));

					if ((i == todayVersionInfo.size()) || (i % Env.FILES_UPLOAD_PER_BATCH_COUNT == 0)) {
						stepService.doBackupFileUpload2FTPStep(batchVOList, ciVO, true);

						batchVOList = null;
						batchVOList = new ArrayList<>();
					}
				}
			}

		} catch (Exception e) {
			log.error(e.toString(), e);
		}
	}
}
