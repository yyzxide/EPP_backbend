package com.epp.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("strategy_config")
public class StrategyConfig {
    @TableId(type =IdType.AUTO)
    private Long id;
    private String strategyId;
    private String version;
    private String configJson;
    private String description;
    private String createTime;
}
