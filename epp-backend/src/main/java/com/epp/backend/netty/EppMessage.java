package com.epp.backend.netty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class EppMessage {
    private byte type;       // 1=心跳, 2=安全检查上报, 3=服务端下发指令
    private String deviceId; // 设备唯一标识
    private String body;     // JSON 格式的业务数据
}
