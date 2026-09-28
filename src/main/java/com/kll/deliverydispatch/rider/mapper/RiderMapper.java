package com.kll.deliverydispatch.rider.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kll.deliverydispatch.rider.entity.Rider;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RiderMapper extends BaseMapper<Rider> {

    /**
     * 数据库原子占用：只有可用且未达到并发接单上限的骑手才能成功加一。
     */
    @Update("""
            UPDATE rider
            SET current_order_count = current_order_count + 1
            WHERE id = #{riderId}
              AND status = #{availableStatus}
              AND current_order_count < #{maxOrderCount}
            """)
    int tryClaimForOrder(@Param("riderId") Long riderId,
                         @Param("availableStatus") int availableStatus,
                         @Param("maxOrderCount") int maxOrderCount);

    /**
     * 订单完成时原子释放一个接单名额，避免并发完成时把计数减成负数或丢失更新。
     */
    @Update("""
            UPDATE rider
            SET current_order_count = current_order_count - 1
            WHERE id = #{riderId}
              AND current_order_count > 0
            """)
    int releaseOrderSlot(@Param("riderId") Long riderId);

    /** Disable only an available rider with no active orders; avoids racing with a new assignment. */
    @Update("""
            UPDATE rider
            SET status = #{disabledStatus}
            WHERE id = #{riderId}
              AND status = #{availableStatus}
              AND current_order_count = 0
            """)
    int disableIfIdle(@Param("riderId") Long riderId,
                      @Param("availableStatus") int availableStatus,
                      @Param("disabledStatus") int disabledStatus);
}
