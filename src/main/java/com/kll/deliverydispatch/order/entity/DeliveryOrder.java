package com.kll.deliverydispatch.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("delivery_order")
public class DeliveryOrder {
    @TableId(value = "id",type= IdType.AUTO)
    private Long id;
    private String orderNo;
    private String merchantName;
    private String merchantPhone;
    private String merchantAddress;
    private BigDecimal merchantLongitude;
    private BigDecimal merchantLatitude;
    private String receiverName;
    private String receiverPhone;
    private String receiverAddress;
    private BigDecimal receiverLongitude;
    private BigDecimal receiverLatitude;
    private Long riderId;
    private Integer status;
    private BigDecimal deliveryFee;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
