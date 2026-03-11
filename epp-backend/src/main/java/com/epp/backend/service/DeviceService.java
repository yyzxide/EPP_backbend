package com.epp.backend.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.epp.backend.entity.DeviceInfo;

public interface DeviceService extends IService<DeviceInfo> {
    /**
     * 设备注册/心跳上报
     * @param deviceInfo 包含设备ID, OS, IP等
     * @return 注册后的设备信息
     */
    DeviceInfo registerOrHeartbeat(DeviceInfo deviceInfo);

    DeviceInfo getDeviceStatus(String deviceId);
}
