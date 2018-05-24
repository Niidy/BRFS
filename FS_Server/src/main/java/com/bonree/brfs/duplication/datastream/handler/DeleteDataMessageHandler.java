package com.bonree.brfs.duplication.datastream.handler;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bonree.brfs.common.ZookeeperPaths;
import com.bonree.brfs.common.http.HandleResult;
import com.bonree.brfs.common.http.HandleResultCallback;
import com.bonree.brfs.common.http.HttpMessage;
import com.bonree.brfs.common.http.MessageHandler;
import com.bonree.brfs.common.service.Service;
import com.bonree.brfs.common.service.ServiceManager;
import com.bonree.brfs.common.task.TaskType;
import com.bonree.brfs.common.utils.CloseUtils;
import com.bonree.brfs.configuration.ServerConfig;
import com.bonree.brfs.disknode.client.DiskNodeClient;
import com.bonree.brfs.disknode.client.HttpDiskNodeClient;
import com.bonree.brfs.disknode.server.handler.data.FileInfo;
import com.bonree.brfs.duplication.storagename.StorageNameManager;
import com.bonree.brfs.duplication.storagename.StorageNameNode;
import com.bonree.brfs.duplication.storagename.exception.StorageNameNonexistentException;
import com.bonree.brfs.schedulers.task.TasksUtils;
import com.bonree.brfs.schedulers.task.manager.MetaTaskManagerInterface;
import com.bonree.brfs.schedulers.task.manager.impl.DefaultReleaseTask;
import com.bonree.brfs.schedulers.task.model.TaskModel;
import com.bonree.brfs.schedulers.task.model.TaskServerNodeModel;
import com.google.common.base.Splitter;

public class DeleteDataMessageHandler implements MessageHandler {
	private static final Logger LOG = LoggerFactory.getLogger(DeleteDataMessageHandler.class);
	
	private static final int TIME_INTERVAL_LEVEL = 2;
	
	private ServiceManager serviceManager;
	private StorageNameManager storageNameManager;
	private ServerConfig serverConfig;
	private ZookeeperPaths zkPaths;
	
	public DeleteDataMessageHandler(ServerConfig serverConfig,ZookeeperPaths zkPaths,ServiceManager serviceManager, StorageNameManager storageNameManager) {
		this.serverConfig = serverConfig;
		this.zkPaths = zkPaths;
	    this.serviceManager = serviceManager;
		this.storageNameManager = storageNameManager;
	}

	@Override
	public void handle(HttpMessage msg, HandleResultCallback callback) {
		HandleResult result = new HandleResult();
		
		List<String> deleteInfo = Splitter.on("/").omitEmptyStrings().trimResults().splitToList(msg.getPath());
		if(deleteInfo.size() != 2) {
			result.setSuccess(false);
			result.setCause(new IllegalArgumentException(msg.getPath()));
			callback.completed(result);
			return;
		}
		
		int storageId = Integer.parseInt(deleteInfo.get(0));
		
		LOG.info("DELETE data for storage[{}]", storageId);
		
//		String path = getPathByStorageNameId(storageId);
		StorageNameNode sn = storageNameManager.findStorageName(storageId);
		if(sn == null) {
			result.setSuccess(false);
			result.setCause(new StorageNameNonexistentException(storageId));
			callback.completed(result);
			return;
		}
		
		List<String> times = Splitter.on("_").omitEmptyStrings().trimResults().splitToList(deleteInfo.get(1));
		long startTime = DateTime.parse(times.get(0)).getMillis();
		long endTime = DateTime.parse(times.get(1)).getMillis();
		if(startTime > endTime) {
			result.setSuccess(false);
			result.setCause(new IllegalArgumentException("start time must before to end time!"));
			callback.completed(result);
			return;
		}
		
		LOG.info("DELETE DATA [{}-->{}]", times.get(0), times.get(1));
		
		List<Service> serviceList = serviceManager.getServiceListByGroup(ServerConfig.DEFAULT_DISK_NODE_SERVICE_GROUP);
//		boolean deleteCompleted = true;
//		for(Service service : serviceList) {
//			DiskNodeClient client = null;
//			try {
//				client = new HttpDiskNodeClient(service.getHost(), service.getPort());
//				List<FileInfo> fileList = client.listFiles(path, TIME_INTERVAL_LEVEL);
//				LOG.info("get file list size={}", fileList.size());
//				
//				List<String> deleteList = filterByTime(fileList, startTime, endTime);
//				if(deleteList.isEmpty()) {
//					continue;
//				}
//				
//				for(String deletePath : deleteList) {
//					LOG.info("Deleting----[{}]", deletePath);
//					deleteCompleted &= client.deleteDir(deletePath, true, true);
//				}
//			} catch(Exception e) {
//				e.printStackTrace();
//			} finally {
//				CloseUtils.closeQuietly(client);
//			}
//		}
         TaskModel task = TasksUtils.createUserDelete(sn, TaskType.USER_DELETE, "", startTime, endTime);
         MetaTaskManagerInterface release = DefaultReleaseTask.getInstance();
         release.setPropreties(serverConfig.getZkHosts(), zkPaths.getBaseTaskPath(), zkPaths.getBaseLocksPath());
         // 创建任务节点
         String taskName = release.updateTaskContentNode(task, TaskType.USER_DELETE.name(), null);
         TaskServerNodeModel serverModel = TasksUtils.createServerTaskNode();
         // 创建服务节点
         for (Service service : serviceList) {
             release.updateServerTaskContentNode(service.getServiceId(), taskName, TaskType.USER_DELETE.name(), serverModel);
         }
		
		result.setSuccess(true);
		callback.completed(result);
	}

	private String getPathByStorageNameId(int storageId) {
		StorageNameNode node = storageNameManager.findStorageName(storageId);
		if(node != null) {
			return "/" + node.getName();
		}
		
		return null;
	}
	
	private List<String> filterByTime(List<FileInfo> fileList, long startTime, long endTime) {
		ArrayList<String> fileNames = new ArrayList<String>();
		for(FileInfo info : fileList) {
			if(info.getLevel() != TIME_INTERVAL_LEVEL) {
				continue;
			}
			
			List<String> times = Splitter.on("_").splitToList(new File(info.getPath()).getName());
			if(startTime <= DateTime.parse(times.get(0)).getMillis() && DateTime.parse(times.get(1)).getMillis() <= endTime) {
				fileNames.add(info.getPath());
			}
		}
		
		return fileNames;
	}

	@Override
	public boolean isValidRequest(HttpMessage message) {
		return message.getPath().matches("/.*/.*_.*");
	}
}
