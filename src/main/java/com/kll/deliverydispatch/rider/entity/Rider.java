package com.kll.deliverydispatch.rider.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("rider")
public class Rider {
    @TableId(value = "id",type = IdType.AUTO)
    private Long id;
    private String riderNo;
    private String name;
    private String phone;
    private Integer status;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private Integer currentOrderCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}