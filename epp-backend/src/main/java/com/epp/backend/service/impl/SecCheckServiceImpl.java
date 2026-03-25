package com.epp.backend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.epp.backend.entity.SecCheckRecord;
import com.epp.backend.mapper.SecCheckMapper;
import com.epp.backend.service.SecCheckService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SecCheckServiceImpl extends ServiceImpl<SecCheckMapper, SecCheckRecord> implements SecCheckService {

    @Override
    public List<SecCheckRecord> getRecentByDeviceId(String deviceId, int limit) {
        return this.list(new LambdaQueryWrapper<SecCheckRecord>()
                .eq(SecCheckRecord::getDeviceId, deviceId)
                .orderByDesc(SecCheckRecord::getCheckTime)
                .last("LIMIT " + limit));
    }
}
