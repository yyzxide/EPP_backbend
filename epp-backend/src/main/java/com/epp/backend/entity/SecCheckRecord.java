package com.epp.backend.entity;

import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sec_check_record")
public class SecCheckRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String deviceId;
    private String checkType;
    private String result;
    private String detail;
    private LocalDateTime checkTime;
}
