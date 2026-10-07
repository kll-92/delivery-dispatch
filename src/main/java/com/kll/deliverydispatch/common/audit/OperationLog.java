package com.kll.deliverydispatch.common.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("operation_log")
public class OperationLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String action;
    private String targetType;
    private Long targetId;
    private String actor;
    private String beforeJson;
    private String afterJson;
    private String reason;
    private LocalDateTime createdAt;
}
