package com.epp.backend.controller;

import com.epp.backend.common.CommonResult;
import com.epp.backend.config.JwtUtils;
import com.epp.backend.entity.DeviceInfo;
import com.epp.backend.service.DeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/device")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;
    private final JwtUtils jwtUtils;

    /**
     * 设备注册接口 — “办证中心”
     * 客户端第一次启动时调用，获取以后连接 Netty 所需的 JWT Token
     */
    @PostMapping("/register")
    public CommonResult<String> register(@RequestBody DeviceInfo deviceInfo) {
        if (deviceInfo.getDeviceId() == null || deviceInfo.getDeviceId().isEmpty()) {
            return CommonResult.failed("DeviceID 不能为空");
        }
        
        // 1. 在数据库中注册或更新设备信息
        deviceService.registerOrHeartbeat(deviceInfo);
        
        // 2. 为该设备签发“通行证” (Token)
        String token = jwtUtils.createToken(deviceInfo.getDeviceId());
        
        return CommonResult.success(token);
    }

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
        DeviceInfo deviceInfo = deviceService.getDeviceStatus(deviceId);
        return CommonResult.success(deviceInfo);
    }
}
