package com.epp.backend.controller;

import com.epp.backend.common.CommonResult;
import com.epp.backend.entity.DeviceInfo;
import com.epp.backend.service.DeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/device")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    /**
     * 统一注册与心跳接口
     * 模拟 C++ 客户端 POST 请求
     */
    @PostMapping("/heartbeat")
    public CommonResult<DeviceInfo> heartbeat(@RequestBody DeviceInfo deviceInfo) {
        if (deviceInfo.getDeviceId() == null || deviceInfo.getDeviceId().isEmpty()) {
            return CommonResult.failed("DeviceID 不能为空");
        }
        DeviceInfo result = deviceService.registerOrHeartbeat(deviceInfo);
        return CommonResult.success(result);
    }

    @GetMapping("/{deviceId}")
    public CommonResult<DeviceInfo> getInfo(@PathVariable String deviceId) {
        // 实际开发中会从缓存或数据库获取
        return CommonResult.success(null); 
    }
}
