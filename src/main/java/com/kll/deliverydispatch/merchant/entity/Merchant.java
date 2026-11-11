package com.kll.deliverydispatch.merchant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 商家（店铺）。账号管登录，商家管店铺；一个账号一个店。 */
@Data
@TableName("merchant")
public class Merchant {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 关联 app_account.id */
    private Long accountId;

    private String name;

    private String phone;

    private String address;

    private BigDecimal longitude;

    private BigDecimal latitude;

    private Boolean enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
