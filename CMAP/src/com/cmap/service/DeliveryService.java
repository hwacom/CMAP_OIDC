package com.cmap.service;

import java.util.List;

import com.cmap.exception.ServiceLayerException;
import com.cmap.service.vo.DeliveryServiceVO;

public interface DeliveryService {

	public long countDeviceList(DeliveryServiceVO dsVO) throws ServiceLayerException;

	public List<DeliveryServiceVO> findDeviceList(DeliveryServiceVO dsVO, Integer startRow, Integer pageLength) throws ServiceLayerException;

	public long countScriptList(DeliveryServiceVO dsVO) throws ServiceLayerException;

	public List<DeliveryServiceVO> findScriptList(DeliveryServiceVO dsVO, Integer startRow, Integer pageLength) throws ServiceLayerException;

	public DeliveryServiceVO getScriptInfoById(String scriptInfoId) throws ServiceLayerException;

	public DeliveryServiceVO getVariableSetting(List<String> groups, List<String> devices, List<String> variables) throws ServiceLayerException;

	public String logAccessRecord(DeliveryServiceVO dsVO) throws ServiceLayerException;

	public String doDelivery(DeliveryServiceVO dsVO, boolean jobTrigger) throws ServiceLayerException;

	/**
	 * 依使用者輸入的查詢條件查詢符合的供裝紀錄筆數
	 * @param dsVO
	 * @return
	 * @throws ServiceLayerException
	 */
	public long countProvisionLog(DeliveryServiceVO dsVO) throws ServiceLayerException;

	/**
	 * 依使用者輸入的查詢條件查詢符合的供裝紀錄資料
	 * @param dsVO
	 * @return
	 * @throws ServiceLayerException
	 */
	public List<DeliveryServiceVO> findProvisionLog(DeliveryServiceVO dsVO, Integer startRow, Integer pageLength) throws ServiceLayerException;

	/**
	 * 查找使用者點擊的供裝紀錄的provision log
	 * @param logStepId
	 * @return
	 * @throws ServiceLayerException
	 */
	public DeliveryServiceVO getProvisionLogById(String logStepId) throws ServiceLayerException;
}
