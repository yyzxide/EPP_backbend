package com.epp.backend.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.epp.backend.entity.SecCheckRecord;

import java.util.List;

public interface SecCheckService extends IService<SecCheckRecord> {

    List<SecCheckRecord> getRecentByDeviceId(String deviceId, int limit);
}
