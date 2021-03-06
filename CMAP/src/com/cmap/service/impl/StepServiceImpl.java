package com.cmap.service.impl;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cmap.Constants;
import com.cmap.Env;
import com.cmap.annotation.Log;
import com.cmap.comm.enums.ConnectionMode;
import com.cmap.comm.enums.ScriptType;
import com.cmap.comm.enums.Step;
import com.cmap.dao.ConfigVersionInfoDAO;
import com.cmap.dao.DeviceListDAO;
import com.cmap.dao.ScriptListDAO;
import com.cmap.dao.ScriptStepDAO;
import com.cmap.dao.vo.ConfigVersionInfoDAOVO;
import com.cmap.dao.vo.ScriptDAOVO;
import com.cmap.exception.FileOperationException;
import com.cmap.exception.ServiceLayerException;
import com.cmap.model.ConfigVersionInfo;
import com.cmap.model.DeviceDetailInfo;
import com.cmap.model.DeviceDetailMapping;
import com.cmap.model.DeviceList;
import com.cmap.model.ScriptInfo;
import com.cmap.security.SecurityUtil;
import com.cmap.service.StepService;
import com.cmap.service.VersionService;
import com.cmap.service.vo.ConfigInfoVO;
import com.cmap.service.vo.ProvisionServiceVO;
import com.cmap.service.vo.StepServiceVO;
import com.cmap.service.vo.VersionServiceVO;
import com.cmap.utils.ConnectUtils;
import com.cmap.utils.FileUtils;
import com.cmap.utils.impl.CommonUtils;
import com.cmap.utils.impl.FtpFileUtils;
import com.cmap.utils.impl.SshUtils;
import com.cmap.utils.impl.TFtpFileUtils;
import com.cmap.utils.impl.TelnetUtils;

@Service("stepService")
@Transactional
public class StepServiceImpl extends CommonServiceImpl implements StepService {
	@Log
	private static Logger log;

	@Autowired
	private ConfigVersionInfoDAO configVersionInfoDAO;

	@Autowired
	private DeviceListDAO deviceListDAO;

	@Autowired
	@Qualifier("scriptListDefaultDAOImpl")
	private ScriptListDAO scriptListDefaultDAO;

	@Autowired
	@Qualifier("scriptStepActionDAOImpl")
	private ScriptStepDAO scriptStepActionDAO;

	@Autowired
	@Qualifier("scriptStepCheckDAOImpl")
	private ScriptStepDAO scriptStepCheckDAO;

	@Autowired
	private VersionService versionService;

	@Override
	public StepServiceVO doBackupStep(String deviceListId, boolean jobTrigger) {
		StepServiceVO retVO = new StepServiceVO();

		ProvisionServiceVO psMasterVO = new ProvisionServiceVO();
		ProvisionServiceVO psDetailVO = new ProvisionServiceVO();
		ProvisionServiceVO psStepVO = new ProvisionServiceVO();
		ProvisionServiceVO psRetryVO;
		ProvisionServiceVO psDeviceVO;

		final int RETRY_TIMES = StringUtils.isNotBlank(Env.RETRY_TIMES) ? Integer.parseInt(Env.RETRY_TIMES) : 1;
		int round = 1;

		/*
		 * Provision_Log_Master & Step
		 */
		final String userName = jobTrigger ? Env.USER_NAME_JOB : SecurityUtil.getSecurityUser() != null ? SecurityUtil.getSecurityUser().getUsername() : Constants.SYS;
		final String userIp = jobTrigger ? Env.USER_IP_JOB : SecurityUtil.getSecurityUser() != null ? SecurityUtil.getSecurityUser().getUser().getIp() : Constants.UNKNOWN;

		psDetailVO.setUserName(userName);
		psDetailVO.setUserIp(userIp);
		psDetailVO.setBeginTime(new Date());
		psDetailVO.setRemark(jobTrigger ? Env.PROVISION_REASON_OF_JOB : null);
		psStepVO.setBeginTime(new Date());

		retVO.setActionBy(userName);
		retVO.setActionFromIp(userIp);
		retVO.setBeginTime(new Date());

		ConnectUtils connectUtils = null;			// 連線裝置物件

		boolean retryRound = false;
		while (round <= RETRY_TIMES) {
			try {
				Step[] steps = null;
				ConnectionMode deviceMode = null;
				ConnectionMode fileServerMode = null;

				switch (Env.DEFAULT_DEVICE_CONFIG_BACKUP_MODE) {
					case Constants.DEVICE_CONFIG_BACKUP_MODE_TELNET_SSH_FTP:
						steps = Env.BACKUP_BY_TELNET;
						deviceMode = ConnectionMode.SSH;
						fileServerMode = ConnectionMode.FTP;
						break;

					case Constants.DEVICE_CONFIG_BACKUP_MODE_TFTP_SSH_TFTP:
						steps = Env.BACKUP_BY_TFTP;
						deviceMode = ConnectionMode.SSH;
						fileServerMode = ConnectionMode.TFTP;
						break;

					case Constants.DEVICE_CONFIG_BACKUP_MODE_TFTP_TELNET_TFTP:
						steps = Env.BACKUP_BY_TFTP;
						deviceMode = ConnectionMode.TELNET;
						fileServerMode = ConnectionMode.TFTP;
						break;

					case Constants.DEVICE_CONFIG_BACKUP_MODE_FTP_SSH_FTP:
						steps = Env.BACKUP_BY_FTP;
						deviceMode = ConnectionMode.SSH;
						fileServerMode = ConnectionMode.FTP;
						break;

					case Constants.DEVICE_CONFIG_BACKUP_MODE_FTP_TELNET_FTP:
						steps = Env.BACKUP_BY_FTP;
						deviceMode = ConnectionMode.TELNET;
						fileServerMode = ConnectionMode.FTP;
						break;
				}

				List<ScriptDAOVO> scripts = null;

				ConfigInfoVO ciVO = null;					// 裝置相關設定資訊VO
				List<String> outputList = null;				// 命令Output內容List
				List<ConfigInfoVO> outputVOList = null;		// Output VO
				FileUtils fileUtils = null;					// 連線FileServer吳建

				for (Step _step : steps) {

					switch (_step) {
						case LOAD_DEFAULT_SCRIPT:
							try {
								scripts = loadDefaultScript(deviceListId, scripts, ScriptType.BACKUP);

								/*
								 * Provision_Log_Step
								 */
								final String scriptName = (scripts != null && !scripts.isEmpty()) ? scripts.get(0).getScriptName() : null;
								final String scriptCode = (scripts != null && !scripts.isEmpty()) ? scripts.get(0).getScriptCode() : null;

								psStepVO.setScriptCode(scriptCode);
								psStepVO.setRemark(scriptName);

								retVO.setScriptCode(scriptCode);

								break;

							} catch (Exception e) {
								log.error(e.toString(), e);
								throw new ServiceLayerException("讀取腳本資料時失敗 [ 錯誤代碼: LOAD_DEFAULT_SCRIPT ]");
							}

						case FIND_DEVICE_CONNECT_INFO:
							try {
								ciVO = findDeviceConfigInfo(ciVO, deviceListId);
								ciVO.setTimes(String.valueOf(round));

								/*
								 * Provision_Log_Device
								 */
								if (!retryRound) {
									psDeviceVO = new ProvisionServiceVO();
									psDeviceVO.setDeviceListId(deviceListId);
									psDeviceVO.setOrderNum(1);
									psStepVO.getDeviceVO().add(psDeviceVO); // add DeviceVO to StepVO

									retVO.setDeviceName(ciVO.getDeviceName());
									retVO.setDeviceIp(ciVO.getDeviceIp());
								}

								break;

							} catch (Exception e) {
								log.error(e.toString(), e);
								throw new ServiceLayerException("取得設備資訊時失敗 [ 錯誤代碼: FIND_DEVICE_CONNECT_INFO ]");
							}

						case FIND_DEVICE_LOGIN_INFO:
							try {
								findDeviceLoginInfo(deviceListId);
								break;

							} catch (Exception e) {
								log.error(e.toString(), e);
								throw new ServiceLayerException("取得設備登入帳密設定時失敗 [ 錯誤代碼: FIND_DEVICE_LOGIN_INFO ]");
							}

						case CONNECT_DEVICE:
							try {
								connectUtils = connect2Device(connectUtils, deviceMode, ciVO);
								break;

							} catch (Exception e) {
								log.error(e.toString(), e);
								throw new ServiceLayerException("設備連線失敗 [ 錯誤代碼: CONNECT_DEVICE ]");
							}

						case LOGIN_DEVICE:
							try {
								login2Device(connectUtils, ciVO);
								break;

							} catch (Exception e) {
								log.error(e.toString(), e);
								throw new ServiceLayerException("登入設備失敗 [ 錯誤代碼: LOGIN_DEVICE ]");
							}

						case SEND_COMMANDS:
							try {
								outputList = sendCmds(connectUtils, scripts, ciVO, retVO);
								break;

							} catch (Exception e) {
								log.error(e.toString(), e);
								throw new ServiceLayerException("派送設備命令失敗 [ 錯誤代碼: SEND_COMMANDS ]");
							}

						case COMPARE_CONTENTS:
							try {
								outputList = compareContents(ciVO, outputList, fileUtils, fileServerMode, retVO);
								break;

							} catch (Exception e) {
								log.error(e.toString(), e);
								throw new ServiceLayerException("組態檔內容比對過程失敗 [ 錯誤代碼: COMPARE_CONTENTS ]");
							}

						case ANALYZE_CONFIG_INFO:
							try {
								analyzeConfigInfo(ciVO, outputList, jobTrigger);
								break;

							} catch (Exception e) {
								log.error(e.toString(), e);
								throw new ServiceLayerException("分析取得設備明細內容時失敗 [ 錯誤代碼: ANALYZE_CONFIG_INFO] ");
							}

						case DEFINE_OUTPUT_FILE_NAME:
							try {
								defineFileName(ciVO);
								break;

							} catch (Exception e) {
								log.error(e.toString(), e);
								throw new ServiceLayerException("定義組態檔輸出檔名時失敗 [ 錯誤代碼: DEFINE_OUTPUT_FILE_NAME ]");
							}

						case COMPOSE_OUTPUT_VO:
							try {
								outputVOList = composeOutputVO(ciVO, outputList);
								break;

							} catch (Exception e) {
								log.error(e.toString(), e);
								throw new ServiceLayerException("建構組態檔輸出物件時失敗 [ 錯誤代碼: COMPOSE_OUTPUT_VO ]");
							}

						case CONNECT_FILE_SERVER_4_UPLOAD:
							try {
								fileUtils = connect2FileServer(fileUtils, fileServerMode, ciVO);
								break;

							} catch (Exception e) {
								log.error(e.toString(), e);
								throw new ServiceLayerException("File Server 連線失敗 [ 錯誤代碼: CONNECT_FILE_SERVER_4_UPLOAD ]");
							}

						case LOGIN_FILE_SERVER_4_UPLOAD:
							try {
								login2FileServer(fileUtils, ciVO);
								break;

							} catch (Exception e) {
								log.error(e.toString(), e);
								throw new ServiceLayerException("File Server 登入失敗 [ 錯誤代碼: LOGIN_FILE_SERVER_4_UPLOAD ]");
							}

						case UPLOAD_FILE_SERVER:
							try {
								upload2FTP(fileUtils, outputVOList);
								break;

							} catch (Exception e) {
								log.error(e.toString(), e);
								throw new ServiceLayerException("File Server 檔案上傳失敗 [ 錯誤代碼: UPLOAD_FILE_SERVER ]");
							}

						case RECORD_DB_OF_CONFIG_VERSION_INFO:
							try {
								record2DB4ConfigVersionInfo(outputVOList, jobTrigger);
								break;

							} catch (Exception e) {
								log.error(e.toString(), e);
								throw new ServiceLayerException("系統寫入組態備份紀錄時失敗 [ 錯誤代碼: RECORD_DB_OF_CONFIG_VERSION_INFO ]");
							}

						case CLOSE_DEVICE_CONNECTION:
							try {
								closeDeviceConnection(connectUtils);
								break;

							} catch (Exception e) {
								log.error(e.toString(), e);
								throw new ServiceLayerException("關閉與設備間連線時失敗 [ 錯誤代碼: CLOSE_DEVICE_CONNECTION ]");
							}

						case CLOSE_FILE_SERVER_CONNECTION:
							try {
								closeFileServerConnection(fileUtils);
								break;

							} catch (Exception e) {
								log.error(e.toString(), e);
								throw new ServiceLayerException("關閉與 File Server 間連線時失敗 [ 錯誤代碼: CLOSE_FILE_SERVER_CONNECTION ]");
							}

						default:
							break;
					}
				}

				retVO.setSuccess(true);
				break;

			} catch (ServiceLayerException sle) {
				/*
				 * Provision_Log_Retry
				 */
				psRetryVO = new ProvisionServiceVO();
				psRetryVO.setResult(Result.ERROR.toString());
				psRetryVO.setMessage(sle.toString());
				psRetryVO.setRetryOrder(round);
				psStepVO.getRetryVO().add(psRetryVO); // add RetryVO to StepVO

				retVO.setSuccess(false);
				retVO.setResult(Result.ERROR);
				retVO.setMessage(sle.toString());
				retVO.setCmdProcessLog(sle.getMessage());

				retryRound = true;
				round++;

				if (connectUtils != null) {
					try {
						connectUtils.disconnect();
					} catch (Exception e1) {
						log.error(e1.toString(), e1);
					}
				}

			} catch (Exception e) {
				/*
				 * Provision_Log_Retry
				 */
				psRetryVO = new ProvisionServiceVO();
				psRetryVO.setResult(Result.ERROR.toString());
				psRetryVO.setMessage(e.toString());
				psRetryVO.setRetryOrder(round);
				psStepVO.getRetryVO().add(psRetryVO); // add RetryVO to StepVO

				retVO.setSuccess(false);
				retVO.setResult(Result.ERROR);
				retVO.setMessage(e.toString());
				retVO.setCmdProcessLog(e.getMessage());

				retryRound = true;
				round++;

				if (connectUtils != null) {
					try {
						connectUtils.disconnect();
					} catch (Exception e1) {
						log.error(e1.toString(), e1);
					}
				}
			}
		}

		/*
		 * Provision_Log_Step
		 */
		psStepVO.setEndTime(new Date());
		psStepVO.setResult(retVO.getResult().toString());
		psStepVO.setMessage(retVO.getMessage());
		psStepVO.setRetryTimes(round-1);
		psStepVO.setProcessLog(retVO.getCmdProcessLog());

		/*
		 * Provision_Log_Detail
		 */
		psDetailVO.setEndTime(new Date());
		psDetailVO.setResult(retVO.getResult().toString());
		psDetailVO.setMessage(retVO.getMessage());
		psDetailVO.getStepVO().add(psStepVO); // add StepVO to DetailVO

		psMasterVO.getDetailVO().add(psDetailVO); // add DetailVO to MasterVO
		retVO.setPsVO(psMasterVO);

		retVO.setEndTime(new Date());
		retVO.setRetryTimes(round);

		return retVO;
	}

	@Override
	public StepServiceVO doBackupFileUpload2FTPStep(List<VersionServiceVO> vsVOs, ConfigInfoVO ciVO, boolean jobTrigger) {
		StepServiceVO retVO = new StepServiceVO();
		retVO.setJobExcuteResultRecords(Integer.toString(vsVOs.size()));

		final int RETRY_TIMES = StringUtils.isNotBlank(Env.RETRY_TIMES) ? Integer.parseInt(Env.RETRY_TIMES) : 1;
		int round = 1;

		boolean success = true;
		while (round <= RETRY_TIMES) {
			try {
				Step[] steps = null;
				ConnectionMode downloadMode = null;
				ConnectionMode uploadMode = null;

				switch (Env.DEFAULT_BACKUP_FILE_BACKUP_MODE) {
					case Constants.BACKUP_FILE_BACKUP_MODE_NULL_FTP_FTP:
						steps = null;
						downloadMode = ConnectionMode.FTP;
						uploadMode = ConnectionMode.FTP;
						break;

					case Constants.BACKUP_FILE_BACKUP_MODE_STEP_TFTP_FTP:
						steps = Env.BACKUP_FILE_DOWNLOAD_FROM_TFTP_AND_UPLOAD_2_FTP;
						downloadMode = ConnectionMode.TFTP;
						uploadMode = ConnectionMode.FTP;
						break;
				}

				List<ConfigInfoVO> outputVOList = null;		// Output VO
				FileUtils fileUtils = null;					// 連線FileServer物件

				for (Step _step : steps) {
					switch (_step) {
						case CONNECT_FILE_SERVER_4_DOWNLOAD:
							fileUtils = connect2FileServer(fileUtils, downloadMode, ciVO);
							break;

						case DOWNLOAD_FILE:
							outputVOList = downloadFile(fileUtils, vsVOs, ciVO, true);
							break;

						case CONNECT_FILE_SERVER_4_UPLOAD:
							fileUtils = connect2FileServer(fileUtils, uploadMode, ciVO);
							break;

						case LOGIN_FILE_SERVER_4_UPLOAD:
							login2FileServer(fileUtils, ciVO);
							break;

						case UPLOAD_FILE_SERVER:
							upload2FTP(fileUtils, outputVOList);
							break;

						case CLOSE_FILE_SERVER_CONNECTION:
							closeFileServerConnection(fileUtils);
							break;

						default:
							break;
					}
				}

				success = true;

			} catch (Exception e) {
				log.error(e.toString(), e);

				success = false;

			} finally {
				if (success) {
					break;
				} else {
					round++;
				}
			}
		}

		return retVO;
	}

	/**
	 * 關閉與裝置的連線
	 * @param connectUtils
	 */
	private void closeDeviceConnection(ConnectUtils connectUtils) {
		try {
			if (connectUtils != null) {
				connectUtils.disconnect();
			}

		} catch (Exception e) {

		} finally {
			connectUtils = null;
		}
	}

	/**
	 * 關閉與FTP/TFTP的連線
	 * @param fileUtils
	 */
	private void closeFileServerConnection(FileUtils fileUtils) {
		try {
			if (fileUtils != null) {
				fileUtils.disconnect();
			}

		} catch (Exception e) {

		} finally {
			fileUtils = null;
		}
	}

	/**
	 * [Step] 取得預設腳本內容
	 * @param script
	 * @return
	 * @throws ServiceLayerException
	 */
	private List<ScriptDAOVO> loadDefaultScript(String deviceListId, List<ScriptDAOVO> script, ScriptType type) throws ServiceLayerException {
		if (script != null && !script.isEmpty()) {
			return script;
		}

		DeviceList device = null;
		if (!StringUtils.equals(deviceListId, "*")) {
			device = deviceListDAO.findDeviceListByDeviceListId(deviceListId);
		}

		String systemVersion = device != null ? device.getSystemVersion() : Env.MEANS_ALL_SYMBOL;
		final String scriptCode = scriptListDefaultDAO.findDefaultScriptCodeBySystemVersion(type, systemVersion);

		script = scriptStepActionDAO.findScriptStepByScriptInfoIdOrScriptCode(null, scriptCode);

		if (script == null || (script != null && script.isEmpty())) {
			if (!StringUtils.equals(systemVersion, Env.MEANS_ALL_SYMBOL)) {
				script = loadDefaultScript("*", script, type);	//帶入機器系統版本號查不到腳本時，將版本調整為*號後再查找一次預設腳本

			} else {
				throw new ServiceLayerException("未設定[備份]預設腳本");
			}
		}

		return script;
	}

	/**
	 * [Step] 取得預設腳本內容
	 * @param script
	 * @return
	 * @throws ServiceLayerException
	 */
	private List<ScriptDAOVO> loadSpecifiedScript(String scriptInfoId, String scriptCode, Map<String, String> varMap, List<ScriptDAOVO> scripts) throws ServiceLayerException {
		if (scripts != null && !scripts.isEmpty()) {
			return scripts;
		}

		scripts = scriptStepActionDAO.findScriptStepByScriptInfoIdOrScriptCode(scriptInfoId, scriptCode);

		if (scripts == null || (scripts != null && scripts.isEmpty())) {
			log.error("查無腳本資料 >> scriptInfoId: " + scriptInfoId + " , scriptCode: " + scriptCode);
			throw new ServiceLayerException("查無腳本資料，請重新操作");
		}

		for (ScriptDAOVO script : scripts) {
			String cmd = script.getScriptContent();

			if (cmd.indexOf("%") != -1) {
				String[] strSlice = cmd.split("%");

				for (int i=0; i<strSlice.length; i++) {
					if (i % 2 == 0) {
						continue;

					} else {
						String varKey = Env.SCRIPT_VAR_KEY_SYMBOL + strSlice[i] + Env.SCRIPT_VAR_KEY_SYMBOL;

						if (!varMap.containsKey(varKey)) {
							throw new ServiceLayerException("錯誤的腳本變數");

						} else {
							cmd = cmd.replace(varKey, varMap.get(varKey));
							script.setScriptContent(cmd);
						}
					}
				}
			}
		}

		return scripts;
	}

	/**
	 * [Step] 查找設備連線資訊
	 * @param configInfoVO
	 * @param deviceListId
	 * @return
	 * @throws ServiceLayerException
	 */
	private ConfigInfoVO findDeviceConfigInfo(ConfigInfoVO configInfoVO, String deviceListId) throws ServiceLayerException {
		DeviceList device = deviceListDAO.findDeviceListByDeviceListId(deviceListId);

		if (device == null) {
			throw new ServiceLayerException("[device_id: " + deviceListId + "] >> 查無設備資料");
		}

		configInfoVO = new ConfigInfoVO();
		BeanUtils.copyProperties(device, configInfoVO);

		/**
		 * TODO 預留裝置登入帳密BY設備設定
		 */
		configInfoVO.setAccount(Env.DEFAULT_DEVICE_LOGIN_ACCOUNT);
		configInfoVO.setPassword(Env.DEFAULT_DEVICE_LOGIN_PASSWORD);
		configInfoVO.setEnablePassword(Env.DEFAULT_DEVICE_ENABLE_PASSWORD);

		/**
		 * TODO 預留裝置落地檔上傳FTP/TFTP位址BY設備設定
		 */
		configInfoVO.setFtpIP(Env.FTP_HOST_IP);
		configInfoVO.setFtpPort(Env.FTP_HOST_PORT);
		configInfoVO.setFtpAccount(Env.FTP_LOGIN_ACCOUNT);
		configInfoVO.setFtpPassword(Env.FTP_LOGIN_PASSWORD);

		configInfoVO.settFtpIP(Env.TFTP_HOST_IP);
		configInfoVO.settFtpPort(Env.TFTP_HOST_PORT);

		return configInfoVO;
	}

	/**
	 * [Step] 查找設備連線帳密 & 連線方式(TELNET / SSH)
	 * @throws ServiceLayerException
	 */
	private void findDeviceLoginInfo(String deviceListId) throws ServiceLayerException {

	}

	/**
	 * [Step] 連線設備
	 * @param connectUtils
	 * @param _mode
	 * @param ciVO
	 * @return
	 * @throws Exception
	 */
	private ConnectUtils connect2Device(ConnectUtils connectUtils, ConnectionMode _mode, ConfigInfoVO ciVO) throws Exception {
		switch (_mode) {
			case TELNET:
				connectUtils = new TelnetUtils();
				connectUtils.connect(ciVO.getDeviceIp(), null);
				break;

			case SSH:
				connectUtils = new SshUtils();
				connectUtils.connect(ciVO.getDeviceIp(), null);
				break;

			default:
				break;
		}

		return connectUtils;
	}

	/**
	 * [Step] 登入
	 * @param connectUtils
	 * @param ciVO
	 * @throws Exception
	 */
	private void login2Device(ConnectUtils connectUtils, ConfigInfoVO ciVO) throws Exception {
		connectUtils.login(
				StringUtils.isBlank(ciVO.getAccount()) ? Env.DEFAULT_DEVICE_LOGIN_ACCOUNT : ciVO.getAccount(),
						StringUtils.isBlank(ciVO.getPassword()) ? Env.DEFAULT_DEVICE_LOGIN_PASSWORD : ciVO.getPassword()
				);

		/*
		Method method = obj.getClass().getMethod("login", new Class[]{String.class,String.class});
		method.invoke(
				obj,
				new String[] {
							StringUtils.isBlank(ciVO.getAccount()) ? Env.DEFAULT_DEVICE_LOGIN_ACCOUNT : ciVO.getAccount(),
							StringUtils.isBlank(ciVO.getPassword()) ? Env.DEFAULT_DEVICE_LOGIN_PASSWORD : ciVO.getPassword()
						});
		 */
	}

	/**
	 * [Step] 執行腳本指令並取回設定要輸出的指令回傳內容
	 * @param connectUtils
	 * @param scriptList
	 * @param configInfoVO
	 * @return
	 * @throws Exception
	 */
	private List<String> sendCmds(ConnectUtils connectUtils, List<ScriptDAOVO> scriptList,  ConfigInfoVO configInfoVO, StepServiceVO ssVO) throws Exception {
		return connectUtils.sendCommands(scriptList, configInfoVO, ssVO);
	}

	/**
	 * [Step] 跟前一版本比對內容，若內容無差異則不再新增備份檔
	 * @param ciVO
	 * @param outputList
	 * @param fileUtils
	 * @param _mode
	 * @return
	 * @throws Exception
	 */
	private List<String> compareContents(ConfigInfoVO ciVO, List<String> outputList, FileUtils fileUtils, ConnectionMode _mode, StepServiceVO ssVO) throws Exception {
		String type = "";

		boolean haveDiffVersion = false;
		List<String> tmpList = outputList.stream().collect(Collectors.toList());
		ConfigVersionInfoDAOVO daovo;
		for (final String output : tmpList) {
			if (output.indexOf(Env.COMM_SEPARATE_SYMBOL) != -1) {
				type = output.split(Env.COMM_SEPARATE_SYMBOL)[0];
			}

			// 查找此裝置前一版本組態檔資料
			daovo = new ConfigVersionInfoDAOVO();
			daovo.setQueryGroup1(ciVO.getGroupId());
			daovo.setQueryDevice1(ciVO.getDeviceId());
			daovo.setQueryConfigType(type);
			List<Object[]> entityList = configVersionInfoDAO.findConfigVersionInfoByDAOVO4New(daovo, null, null);

			// 當前備份版本正確檔名
			final String nowVersionFileName = StringUtils.replace(ciVO.getConfigFileName(), Env.COMM_SEPARATE_SYMBOL, type);

			// 當前備份版本上傳於temp資料夾內檔名 (若TFTP Server與CMAP系統不是架設在同一台主機時)
			final String nowVersionTempFileName = !Env.TFTP_SERVER_AT_LOCAL ? nowVersionFileName.concat(".").concat(ciVO.getTempFileRandomCode()) : null;

			List<VersionServiceVO> vsVOs;
			if (entityList != null && !entityList.isEmpty()) {
				final ConfigVersionInfo cviEneity = (ConfigVersionInfo)entityList.get(0)[0];
				final DeviceList dlEntity = (DeviceList)entityList.get(0)[1];

				vsVOs = new ArrayList<>();

				//前一版本VO
				VersionServiceVO preVersionVO = new VersionServiceVO();
				preVersionVO.setConfigFileDirPath(dlEntity.getConfigFileDirPath());
				preVersionVO.setFileFullName(cviEneity.getFileFullName());
				vsVOs.add(preVersionVO);

				//當下備份上傳版本VO
				VersionServiceVO nowVersionVO = new VersionServiceVO();

				/*
				 * 若TFTP Server與CMAP系統不是架設在同一台主機上
				 * Config file從Device上傳時會先放置於temp資料夾內(Env.TFTP_TEMP_DIR_PATH)
				 * 比對版本內容時抓取的檔名(FileFullName)也必須調整為temp資料夾內檔名(nowVersionTempFileName，有加上時間細數碼)
				 */
				nowVersionVO.setConfigFileDirPath(
						Env.TFTP_SERVER_AT_LOCAL ? ciVO.getConfigFileDirPath() : Env.TFTP_TEMP_DIR_PATH);
				nowVersionVO.setFileFullName(
						!Env.TFTP_SERVER_AT_LOCAL ? nowVersionTempFileName : nowVersionFileName);
				vsVOs.add(nowVersionVO);

				VersionServiceVO compareRetVO = versionService.compareConfigFiles(vsVOs);

				if (StringUtils.isBlank(compareRetVO.getDiffPos())) {
					/*
					 * 版本內容比對相同:
					 * (1)若[Env.TFTP_SERVER_AT_LOCAL=true]，刪除本機已上傳的檔案;若為false則不處理(另外設定系統排程定期清整temp資料夾內檔案)
					 * (2)移除List內容
					 */
					outputList.remove(output);

					if (Env.TFTP_SERVER_AT_LOCAL) {
						//TODO:刪除本機已上傳的檔案
						deleteLocalFile(ciVO);
					}

				} else {
					haveDiffVersion = true;
					/*
					 * 版本內容不同:
					 * (1)若[Env.TFTP_SERVER_AT_LOCAL=false]，將檔案從TFTP temp資料夾copy到Device對應目錄;若為true則不需再作處理
					 */
					if (Env.TFTP_SERVER_AT_LOCAL == null || (Env.TFTP_SERVER_AT_LOCAL != null && !Env.TFTP_SERVER_AT_LOCAL)) {
						final String sourceDirPath = Env.TFTP_TEMP_DIR_PATH;
						final String targetDirPath = ciVO.getConfigFileDirPath().concat((StringUtils.isNotBlank(Env.TFTP_DIR_PATH_SEPARATE_SYMBOL) ? Env.TFTP_DIR_PATH_SEPARATE_SYMBOL : File.separator)).concat(nowVersionFileName);
						ciVO.setFileFullName(nowVersionTempFileName);

						fileUtils.moveFiles(ciVO, sourceDirPath, targetDirPath);
					}
				}

			} else {
				haveDiffVersion = true;
				/*
				 * 若沒有前一版本(系統首次備份):
				 * (1)若[Env.TFTP_SERVER_AT_LOCAL=false]，將檔案從TFTP temp資料夾copy到Device對應目錄;若為true則不需再作處理
				 */
				if (Env.TFTP_SERVER_AT_LOCAL == null || (Env.TFTP_SERVER_AT_LOCAL != null && !Env.TFTP_SERVER_AT_LOCAL)) {
					final String sourceDirPath = Env.TFTP_TEMP_DIR_PATH;
					final String targetDirPath = ciVO.getConfigFileDirPath().concat((StringUtils.isNotBlank(Env.TFTP_DIR_PATH_SEPARATE_SYMBOL) ? Env.TFTP_DIR_PATH_SEPARATE_SYMBOL : File.separator)).concat(nowVersionFileName);
					ciVO.setFileFullName(nowVersionTempFileName);

					fileUtils.moveFiles(ciVO, sourceDirPath, targetDirPath);
				}
			}
		}

		if (!haveDiffVersion) {
			ssVO.setResult(Result.NO_DIFFERENT);
			ssVO.setMessage("版本無差異");
		} else {
			ssVO.setResult(Result.SUCCESS);
		}

		return outputList;
	}

	private List<String> getConfigContent(ConfigInfoVO configInfoVO) {
		List<String> retList = new ArrayList<String>();

		try {
			FileUtils fileUtils = null;
			String _hostIp = null;
			Integer _hostPort = null;
			String _loginAccount = null;
			String _loginPassword = null;

			// Step1. 建立FileServer傳輸物件
			switch (Env.FILE_TRANSFER_MODE) {
			case FTP:
				fileUtils = new FtpFileUtils();
				_hostIp = Env.FTP_HOST_IP;
				_hostPort = Env.FTP_HOST_PORT;
				_loginAccount = Env.FTP_LOGIN_ACCOUNT;
				_loginPassword = Env.FTP_LOGIN_PASSWORD;
				break;

			case TFTP:
				fileUtils = new TFtpFileUtils();
				_hostIp = Env.TFTP_HOST_IP;
				_hostPort = Env.TFTP_HOST_PORT;
				break;
			}

			// Step2. FTP連線
			fileUtils.connect(_hostIp, _hostPort);

			// Step3. FTP登入
			fileUtils.login(_loginAccount, _loginPassword);

			// Step3. 移動作業目錄至指定的裝置
			fileUtils.changeDir(configInfoVO.getConfigFileDirPath(), false);

			// Step4. 下載指定的Config落地檔
			retList = fileUtils.downloadFiles(configInfoVO);

		} catch (Exception e) {
			log.error(e.toString(), e);
		}

		return retList;
	}

	/**
	 * [Step] 解析組態檔內容取得特定資料寫入 Device_Detail_Info for 後續功能使用
	 * @param configInfoVO
	 * @param outputList
	 */
	private void analyzeConfigInfo(ConfigInfoVO configInfoVO, List<String> outputList, boolean jobTrigger) {

		if (outputList == null || (outputList != null && outputList.isEmpty())) {
			/*
			 * outputList 為空表示版本無差異，此時不做內容解析
			 */

		} else {
			try {
				List<DeviceDetailMapping> entities = deviceListDAO.findDeviceDetailMapping(null);

				if (entities != null && !entities.isEmpty()) {
					final String userName = jobTrigger ? Env.USER_NAME_JOB : SecurityUtil.getSecurityUser().getUsername();

					/*
					 * 版本有差異情況下，先刪除前一次分析取出的內容
					 */
					deviceListDAO.deleteDeviceDetailInfoByInfoName(null, configInfoVO.getGroupId(), configInfoVO.getDeviceId(), null, currentTimestamp(), userName);

					List<String> configContent = getConfigContent(configInfoVO);

					List<DeviceDetailInfo> insertEntities = new ArrayList<>();
					Map<String, Integer> orderMap = new HashMap<>();
					Map<String, DeviceDetailInfo> analyzeInfoName = new HashMap<>();

					for (final String configStr : configContent) {

						DeviceDetailInfo ddi = null;
						for (DeviceDetailMapping entity : entities) {
							final String sourceStrng = entity.getSourceString();
							final String splitBy = entity.getSplitBy();
							final Integer getValueIndex = entity.getGetValueIndex();
							final String targetInfoName = entity.getTargetInfoName();
							final String targetInfoRemark = entity.getTargetInfoRemark();
							final String deviceListId = configInfoVO.getDeviceListId();
							final String groupId = configInfoVO.getGroupId();
							final String deviceId = configInfoVO.getDeviceId();
							final Timestamp updateTime = currentTimestamp();

							if (StringUtils.startsWith(configStr, sourceStrng)) {
								String[] tmpArray = StringUtils.split(configStr, splitBy);

								String getTargetValue = null;
								if (tmpArray.length > getValueIndex) {
									getTargetValue = tmpArray[getValueIndex];
								}

								if (getTargetValue == null) {
									continue;
								}

								Integer targetInfoOrder = 1;
								if (orderMap.containsKey(targetInfoName)) {
									targetInfoOrder = orderMap.get(targetInfoName);
									targetInfoOrder++;
								}

								List<DeviceDetailInfo> info = deviceListDAO.findDeviceDetailInfo(deviceListId, groupId, deviceId, targetInfoName);

								if (info != null && !info.isEmpty()) {
									continue;
								}

								ddi = new DeviceDetailInfo();
								ddi.setInfoId(UUID.randomUUID().toString());
								ddi.setDeviceListId(deviceListId);
								ddi.setGroupId(groupId);
								ddi.setDeviceId(deviceId);
								ddi.setInfoName(targetInfoName);
								ddi.setInfoValue(getTargetValue);
								ddi.setInfoOrder(targetInfoOrder);
								ddi.setInfoRemark(targetInfoRemark);
								ddi.setCreateTime(updateTime);
								ddi.setCreateBy(userName);
								ddi.setUpdateTime(updateTime);
								ddi.setUpdateBy(userName);

								insertEntities.add(ddi);
								orderMap.put(targetInfoName, targetInfoOrder);

								if (!analyzeInfoName.containsKey(targetInfoName)) {
									analyzeInfoName.put(targetInfoName, ddi);
								}
							}
						}
					}

					/*
					//先刪除舊資料
					for (DeviceDetailInfo ddi : analyzeInfoName.values()) {
						deviceListDAO.deleteDeviceDetailInfoByInfoName(ddi.getDeviceListId(), ddi.getInfoName(), ddi.getUpdateTime(), ddi.getUpdateBy());
					}
					*/

					//再新增新資料
					if (insertEntities != null && !insertEntities.isEmpty()) {
						deviceListDAO.insertEntities(insertEntities);
					}
				}

			} catch (Exception e) {
				log.error("更新設備明細資料時異常 >>> "+e.toString(), e);
			}
		}
	}

	/**
	 * [Step] 定義輸出檔案名稱
	 * @param configInfoVO
	 * @throws ServiceLayerException
	 */
	private void defineFileName(ConfigInfoVO configInfoVO) throws ServiceLayerException {
		ConfigVersionInfoDAOVO cviDAOVO = new ConfigVersionInfoDAOVO();
		cviDAOVO.setQueryGroup1(configInfoVO.getGroupId());
		cviDAOVO.setQueryDevice1(configInfoVO.getDeviceId());
		cviDAOVO.setQueryDateBegin1(Constants.FORMAT_YYYY_MM_DD.format(new Date()));
		cviDAOVO.setQueryDateEnd1(Constants.FORMAT_YYYY_MM_DD.format(new Date()));
		List<Object[]> modelList = configVersionInfoDAO.findConfigVersionInfoByDAOVO4New(cviDAOVO, null, null);

		int seqNo = 1;
		if (modelList != null && !modelList.isEmpty()) {
			ConfigVersionInfo cvi = (ConfigVersionInfo)modelList.get(0)[0];
			String currentSeq = StringUtils.isNotBlank(cvi.getConfigVersion())
					? cvi.getConfigVersion().substring(cvi.getConfigVersion().length()-3, cvi.getConfigVersion().length())
							: "0";

					seqNo += Integer.valueOf(currentSeq);
		}

		String fileName = CommonUtils.composeConfigFileName(configInfoVO, seqNo);
		String tFtpTargetFilePath =
				(Env.TFTP_SERVER_AT_LOCAL ? configInfoVO.getConfigFileDirPath() : Env.TFTP_TEMP_DIR_PATH).concat((StringUtils.isNotBlank(Env.TFTP_DIR_PATH_SEPARATE_SYMBOL) ? Env.TFTP_DIR_PATH_SEPARATE_SYMBOL : File.separator)).concat(fileName);

		/*
		 * 若 TFTP Server 與 CMAP系統 不是架設在同一台主機上
		 * 因組態檔案名稱時間戳記僅有到「分」，若同一分鐘內備份多次，會因為檔名重複而命令執行失敗
		 * 因此，若此條件下，將上傳到temp資料夾的檔案名稱加上時間細數碼
		 */
		String tFtpTempFileRandomCode = "";
		String tempFilePath = tFtpTargetFilePath;
		if (Env.ENABLE_TEMP_FILE_RANDOM_CODE) {
			long miles = System.currentTimeMillis();
			long seconds = TimeUnit.MILLISECONDS.toSeconds(miles) - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(miles));
			tFtpTempFileRandomCode = String.valueOf(seconds).concat(".").concat(configInfoVO.getTimes());
			tempFilePath = tempFilePath.concat(".").concat(tFtpTempFileRandomCode);
		}

		configInfoVO.setConfigFileName(fileName);
		configInfoVO.settFtpFilePath(tFtpTargetFilePath);
		configInfoVO.setTempFileRandomCode(tFtpTempFileRandomCode);
		configInfoVO.setTempFilePath(tempFilePath);
	}

	/**
	 * [Step] 查找此群組+設備今日是否已有備份紀錄，決定此次備份檔流水
	 * @param configInfoVO
	 * @param outputList
	 * @return
	 * @throws ServiceLayerException
	 * @throws CloneNotSupportedException
	 */
	private List<ConfigInfoVO> composeOutputVO(ConfigInfoVO configInfoVO, List<String> outputList) throws ServiceLayerException, CloneNotSupportedException {
		List<ConfigInfoVO> voList = new ArrayList<>();
		String type = "";
		String content = "";

		ConfigInfoVO vo;
		for (String output : outputList) {
			if (output.indexOf(Env.COMM_SEPARATE_SYMBOL) != -1) {
				type = output.split(Env.COMM_SEPARATE_SYMBOL)[0];
				content = output.split(Env.COMM_SEPARATE_SYMBOL)[1];
			} else {
				content = output;
			}

			vo = (ConfigInfoVO)configInfoVO.clone();
			vo.setConfigType(type);
			vo.setConfigContent(content);

			String configFileName = vo.getConfigFileName();
			if (configFileName.indexOf(Env.COMM_SEPARATE_SYMBOL) != -1) {
				configFileName = StringUtils.replace(configFileName, Env.COMM_SEPARATE_SYMBOL, type);
				vo.setConfigFileName(configFileName);
			}

			voList.add(vo);
		}

		return voList;
	}

	/**
	 * [Step] 建立FTP/TFTP連線
	 * @param fileUtils
	 * @param _mode
	 * @param ciVO
	 * @return
	 * @throws Exception
	 */
	private FileUtils connect2FileServer(FileUtils fileUtils, ConnectionMode _mode, ConfigInfoVO ciVO) throws Exception {
		switch (_mode) {
			case FTP:
				// By FTP
				fileUtils = new FtpFileUtils();
				fileUtils.connect(ciVO.getFtpIP(), ciVO.getFtpPort());
				break;

			case TFTP:
				// By TFTP
				fileUtils = new TFtpFileUtils();
				fileUtils.connect(ciVO.gettFtpIP(), ciVO.gettFtpPort());

			default:
				break;
		}

		return fileUtils;
	}

	/**
	 * 刪除本機檔案
	 * @param ciVO
	 * @return
	 * @throws FileOperationException
	 */
	private boolean deleteLocalFile(ConfigInfoVO ciVO) throws FileOperationException {
		try {
			final String filePath = Env.TFTP_LOCAL_ROOT_DIR_PATH.concat(ciVO.getConfigFileDirPath()).concat(ciVO.getFileFullName());

			Path path = Paths.get(filePath);
			if (Files.isRegularFile(path) & Files.isReadable(path) & Files.isExecutable(path)) {
				Files.delete(path);
				return true;

			} else {
				throw new FileOperationException("[組態檔內容相同，但無法刪除檔案] >> " + filePath);
			}

		} catch (Exception e) {
			throw new FileOperationException("[組態檔內容相同，但刪除檔案過程異常] >> " + e.toString());
		}
	}

	/**
	 * 移動本機檔案
	 * @param ciVO
	 * @return
	 * @throws FileOperationException
	 */
	private boolean moveLocalFile(ConfigInfoVO ciVO) throws FileOperationException {
		try {
			final String source = Env.TFTP_LOCAL_ROOT_DIR_PATH.concat(Env.TFTP_TEMP_DIR_PATH).concat(ciVO.getFileFullName());
			final String target = Env.TFTP_LOCAL_ROOT_DIR_PATH.concat(ciVO.getConfigFileDirPath()).concat(ciVO.getFileFullName());

			final Path sourcePath = Paths.get(source);
			final Path targetPath = Paths.get(target);

			if (Files.isRegularFile(sourcePath) & Files.isReadable(sourcePath) & Files.isExecutable(sourcePath)) {
				Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
				return true;

			} else {
				throw new FileOperationException("[組態檔內容相同，但無法移動檔案] >> " + source);
			}

		} catch (Exception e) {
			throw new FileOperationException("[組態檔內容相同，但移動檔案過程異常] >> " + e.toString());
		}
	}

	/**
	 * [Step] 登入FTP
	 * @param fileUtils
	 * @param ciVO
	 * @throws Exception
	 */
	private void login2FileServer(FileUtils fileUtils, ConfigInfoVO ciVO) throws Exception {
		fileUtils.login(
				StringUtils.isBlank(ciVO.getFtpAccount()) ? Env.FTP_LOGIN_ACCOUNT : ciVO.getFtpAccount(),
						StringUtils.isBlank(ciVO.getFtpPassword()) ? Env.FTP_LOGIN_PASSWORD : ciVO.getFtpPassword()
				);
	}

	/**
	 * [Step] 輸出檔案透過FTP落地保存
	 * @param ftpUtils
	 * @param ciVOList
	 * @throws Exception
	 */
	private void upload2FTP(FileUtils ftpUtils, List<ConfigInfoVO> ciVOList) throws Exception {

		String remoteFileDirPath = "";
		if (ciVOList != null && !ciVOList.isEmpty()) {
			// 8-4. 上傳檔案
			for (ConfigInfoVO ciVO : ciVOList) {
				remoteFileDirPath = ciVO.getRemoteFileDirPath();

				if (Env.ENABLE_REMOTE_BACKUP_USE_TODAY_ROOT_DIR) {
					SimpleDateFormat sdf = new SimpleDateFormat(Env.DIR_PATH_OF_CURRENT_DATE_FORMAT);
					remoteFileDirPath = sdf.format(new Date()).concat(Env.FTP_DIR_SEPARATE_SYMBOL).concat(remoteFileDirPath);
				}

				// 8-3. 移動作業目錄至指定的裝置
				ftpUtils.changeDir(remoteFileDirPath, true);

				String configFileName = new String(ciVO.getConfigFileName().getBytes("UTF-8"),"iso-8859-1");

				ftpUtils.uploadFiles(
						configFileName,
						IOUtils.toInputStream(ciVO.getConfigContent(), Constants.CHARSET_UTF8)
						);
			}
		}
	}

	/**
	 * [Step] 寫入DB資料
	 * @param ciVOList
	 * @param jobTrigger
	 */
	private void record2DB4ConfigVersionInfo(List<ConfigInfoVO> ciVOList, boolean jobTrigger) {
		for (ConfigInfoVO ciVO : ciVOList) {
			configVersionInfoDAO.insertConfigVersionInfo(CommonUtils.composeModelEntityByConfigInfoVO(ciVO, jobTrigger));
		}
	}

	/**
	 * [Step] 從FTP/TFTP下載資料
	 * @param fileUtils
	 * @param vsVOs
	 * @param ciVO
	 * @param returnFileString
	 * @return
	 * @throws Exception
	 */
	private List<ConfigInfoVO> downloadFile(FileUtils fileUtils, List<VersionServiceVO> vsVOs, ConfigInfoVO ciVO, boolean returnFileString) throws Exception {
		List<ConfigInfoVO> ciVOList = new ArrayList<>();

		ConfigInfoVO tmpVO = null;
		int i = 1;
		for (VersionServiceVO vsVO : vsVOs) {
			tmpVO = (ConfigInfoVO)ciVO.clone();
			tmpVO.setConfigFileDirPath(vsVO.getConfigFileDirPath());
			tmpVO.setRemoteFileDirPath(vsVO.getRemoteFileDirPath());
			tmpVO.setFileFullName(vsVO.getFileFullName());

			if (returnFileString) {
				final String fileContent = fileUtils.downloadFilesString(tmpVO);
				tmpVO.setConfigContent(fileContent);

			} else {
				final List<String> fileContentList = fileUtils.downloadFiles(ciVO);
				tmpVO.setConfigContentList(fileContentList);
			}

			tmpVO.setConfigFileName(vsVO.getFileFullName());

			ciVOList.add(tmpVO);
			i++;
		}
		return ciVOList;
	}

	@Override
	public StepServiceVO doScript(String deviceListId, ScriptInfo scriptInfo, Map<String, String> varMap, boolean jobTrigger) {
		StepServiceVO processVO = new StepServiceVO();

		ProvisionServiceVO psMasterVO = new ProvisionServiceVO();
		ProvisionServiceVO psDetailVO = new ProvisionServiceVO();
		ProvisionServiceVO psStepVO = new ProvisionServiceVO();
		ProvisionServiceVO psRetryVO;
		ProvisionServiceVO psDeviceVO;

		final int RETRY_TIMES = StringUtils.isNotBlank(Env.RETRY_TIMES) ? Integer.parseInt(Env.RETRY_TIMES) : 1;
		int round = 1;

		/*
		 * Provision_Log_Master & Step
		 */
		final String userName = jobTrigger ? Env.USER_NAME_JOB : SecurityUtil.getSecurityUser() != null ? SecurityUtil.getSecurityUser().getUsername() : Constants.SYS;
		final String userIp = jobTrigger ? Env.USER_IP_JOB : SecurityUtil.getSecurityUser() != null ? SecurityUtil.getSecurityUser().getUser().getIp() : Constants.UNKNOWN;

		psDetailVO.setUserName(userName);
		psDetailVO.setUserIp(userIp);
		psDetailVO.setBeginTime(new Date());
		psDetailVO.setRemark(jobTrigger ? Env.PROVISION_REASON_OF_JOB : null);
		psStepVO.setBeginTime(new Date());

		processVO.setActionBy(userName);
		processVO.setActionFromIp(userIp);
		processVO.setBeginTime(new Date());

		ConnectUtils connectUtils = null;			// 連線裝置物件

		boolean retryRound = false;
		while (round <= RETRY_TIMES) {
			try {
				Step[] steps = null;
				ConnectionMode deviceMode = Constants.DEFAULT_DEVICE_CONNECTION_MODE;

				steps = Env.SEND_SCRIPT;

				List<ScriptDAOVO> scripts = null;
				ConfigInfoVO ciVO = null;					// 裝置相關設定資訊VO

				for (Step _step : steps) {
					switch (_step) {
						case LOAD_SPECIFIED_SCRIPT:
							try {
								psStepVO.setScriptCode(scriptInfo.getScriptCode());
								processVO.setScriptCode(scriptInfo.getScriptCode());

								scripts = loadSpecifiedScript(scriptInfo.getScriptInfoId(), scriptInfo.getScriptCode(), varMap, scripts);

								/*
								 * Provision_Log_Step
								 */
								final String scriptName = (scripts != null && !scripts.isEmpty()) ? scripts.get(0).getScriptName() : null;
								psStepVO.setRemark(scriptName);

								break;

							} catch (Exception e) {
								log.error(e.toString(), e);
								throw new ServiceLayerException("讀取腳本資料時失敗 [ 錯誤代碼: LOAD_SPECIFIED_SCRIPT ]");
							}

						case FIND_DEVICE_CONNECT_INFO:
							try {
								ciVO = findDeviceConfigInfo(ciVO, deviceListId);
								ciVO.setTimes(String.valueOf(round));

								/*
								 * Provision_Log_Device
								 */
								if (!retryRound) {
									psDeviceVO = new ProvisionServiceVO();
									psDeviceVO.setDeviceListId(deviceListId);
									psDeviceVO.setOrderNum(1);
									psStepVO.getDeviceVO().add(psDeviceVO); // add DeviceVO to StepVO

									processVO.setDeviceName(ciVO.getDeviceName());
									processVO.setDeviceIp(ciVO.getDeviceIp());
								}

								break;

							} catch (Exception e) {
								log.error(e.toString(), e);
								throw new ServiceLayerException("取得設備資訊時失敗 [ 錯誤代碼: FIND_DEVICE_CONNECT_INFO ]");
							}

						case FIND_DEVICE_LOGIN_INFO:
							try {
								findDeviceLoginInfo(deviceListId);
								break;

							} catch (Exception e) {
								log.error(e.toString(), e);
								throw new ServiceLayerException("取得設備登入帳密設定時失敗 [ 錯誤代碼: FIND_DEVICE_LOGIN_INFO]");
							}

						case CONNECT_DEVICE:
							try {
								connectUtils = connect2Device(connectUtils, deviceMode, ciVO);
								break;

							} catch (Exception e) {
								log.error(e.toString(), e);
								throw new ServiceLayerException("設備連線失敗 [ 錯誤代碼: CONNECT_DEVICE ]");
							}

						case LOGIN_DEVICE:
							try {
								login2Device(connectUtils, ciVO);
								break;

							} catch (Exception e) {
								log.error(e.toString(), e);
								throw new ServiceLayerException("登入設備失敗 [ 錯誤代碼: LOGIN_DEVICE ]");
							}

						case SEND_COMMANDS:
							try {
								sendCmds(connectUtils, scripts, ciVO, processVO);
								break;

							} catch (Exception e) {
								log.error(e.toString(), e);
								throw new ServiceLayerException("派送設備命令失敗 [ 錯誤代碼: SEND_COMMANDS ]");
							}

						case CHECK_PROVISION_RESULT:
							try {
								break;

							} catch (Exception e) {
								log.error(e.toString(), e);
								throw new ServiceLayerException("檢核供裝派送結果時失敗 [ 錯誤代碼: CHECK_PROVISION_RESULT ]");
							}

						case CLOSE_DEVICE_CONNECTION:
							try {
								closeDeviceConnection(connectUtils);
								break;

							} catch (Exception e) {
								log.error(e.toString(), e);
								throw new ServiceLayerException("關閉與設備間連線時失敗 [ 錯誤代碼: CLOSE_DEVICE_CONNECTION ]");
							}

						default:
							break;
					}
				}

				processVO.setSuccess(true);
				processVO.setResult(Result.SUCCESS);
				break;

			} catch (ServiceLayerException sle) {
				/*
				 * Provision_Log_Retry
				 */
				psRetryVO = new ProvisionServiceVO();
				psRetryVO.setResult(Result.ERROR.toString());
				psRetryVO.setMessage(sle.toString());
				psRetryVO.setRetryOrder(round);
				psStepVO.getRetryVO().add(psRetryVO); // add RetryVO to StepVO

				processVO.setSuccess(false);
				processVO.setResult(Result.ERROR);
				processVO.setMessage(sle.toString());
				processVO.setCmdProcessLog(sle.getMessage());

				retryRound = true;
				round++;

				if (connectUtils != null) {
					try {
						connectUtils.disconnect();
					} catch (Exception e1) {
						log.error(e1.toString(), e1);
					}
				}
			} catch (Exception e) {
				log.error(e.toString(), e);

				/*
				 * Provision_Log_Retry
				 */
				psRetryVO = new ProvisionServiceVO();
				psRetryVO.setResult(Result.ERROR.toString());
				psRetryVO.setMessage(e.toString());
				psRetryVO.setRetryOrder(round);
				psStepVO.getRetryVO().add(psRetryVO); // add RetryVO to StepVO

				processVO.setSuccess(false);
				processVO.setResult(Result.ERROR);
				processVO.setMessage(e.toString());
				processVO.setCmdProcessLog(e.getMessage());

				retryRound = true;
				round++;

				if (connectUtils != null) {
					try {
						connectUtils.disconnect();
					} catch (Exception e1) {
						log.error(e1.toString(), e1);
					}
				}
			}
		}

		/*
		 * Provision_Log_Step
		 */
		psStepVO.setEndTime(new Date());
		psStepVO.setResult(processVO.getResult().toString());
		psStepVO.setMessage(processVO.getMessage());
		psStepVO.setRetryTimes(round-1);
		psStepVO.setProcessLog(processVO.getCmdProcessLog());

		/*
		 * Provision_Log_Detail
		 */
		psDetailVO.setEndTime(new Date());
		psDetailVO.setResult(processVO.getResult().toString());
		psDetailVO.setMessage(processVO.getMessage());
		psDetailVO.getStepVO().add(psStepVO); // add StepVO to DetailVO

		psMasterVO.getDetailVO().add(psDetailVO); // add DetailVO to MasterVO
		processVO.setPsVO(psMasterVO);

		processVO.setEndTime(new Date());
		processVO.setRetryTimes(round);

		return processVO;
	}
}
