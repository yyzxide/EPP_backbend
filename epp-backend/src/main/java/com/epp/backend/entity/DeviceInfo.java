package com.epp.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("device_info")
public class DeviceInfo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String deviceId;
    private String osType;
    private String ipAddress;
    private Long strategyVersion;
    private Integer status; // 0-离线, 1-在线
    private LocalDateTime lastHeartbeatTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
