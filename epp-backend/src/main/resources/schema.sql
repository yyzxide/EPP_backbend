-- EPP 核心数据库 Schema (V1.0)
CREATE DATABASE IF NOT EXISTS epp_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE epp_db;

-- 1. 设备信息表: 存储设备静态属性与最新状态
CREATE TABLE IF NOT EXISTS `device_info` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    `device_id` VARCHAR(64) NOT NULL UNIQUE COMMENT '设备唯一HWID (C++端生成)',
    `os_type` VARCHAR(32) COMMENT '操作系统类型 (Windows/Linux/MacOS)',
    `ip_address` VARCHAR(45) COMMENT '最后上线IP (支持IPv6)',
    `strategy_version` BIGINT DEFAULT 0 COMMENT '当前生效的策略版本号(SyncTime)',
    `status` TINYINT DEFAULT 0 COMMENT '在线状态: 0-离线, 1-在线',
    `last_heartbeat_time` DATETIME COMMENT '最后心跳时间',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX `idx_device_id` (`device_id`),
    INDEX `idx_status` (`status`)
) ENGINE=InnoDB COMMENT='终端设备信息表';

-- 2. 策略模板表: 存储策略的具体 JSON 配置
CREATE TABLE IF NOT EXISTS `strategy_config` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `strategy_id` VARCHAR(64) NOT NULL UNIQUE COMMENT '策略唯一标识',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '策略版本号 (时间戳)',
    `config_json` JSON NOT NULL COMMENT '策略Payload (黑白名单、扫描项等)',
    `description` VARCHAR(255),
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_strategy_id` (`strategy_id`),
    INDEX `idx_version` (`version`)
) ENGINE=InnoDB COMMENT='策略配置版本管理';
