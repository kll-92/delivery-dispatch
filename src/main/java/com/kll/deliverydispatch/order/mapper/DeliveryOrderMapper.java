package com.kll.deliverydispatch.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kll.deliverydispatch.order.entity.DeliveryOrder;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DeliveryOrderMapper extends BaseMapper<DeliveryOrder> {

    /** Delete only the version that was read. MySQL <=> also matches a null rider_id. */
    @Delete("""
            DELETE FROM delivery_order
            WHERE id = #{orderId} AND status = #{expectedStatus}
              AND rider_id <=> #{expectedRiderId}
            """)
    int deleteIfUnchanged(@Param("orderId") Long orderId,
                          @Param("expectedStatus") int expectedStatus,
                          @Param("expectedRiderId") Long expectedRiderId);

    /** Old pending orders whose Outbox event reached RocketMQ may need a later retry. */
    @Select("""
            SELECT o.id
            FROM delivery_order o
            WHERE o.status = 0
              AND o.created_at < DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 30 SECOND)
              AND o.id > #{afterId}
              AND EXISTS (
                  SELECT 1 FROM dispatch_outbox x
                  WHERE x.order_id = o.id AND x.status = 1
              )
            ORDER BY o.id ASC
            LIMIT #{limit}
            """)
    java.util.List<Long> selectPendingPublishedIdsAfter(@Param("afterId") long afterId,
                                                         @Param("limit") int limit);

    /**
     * 只有待派单订单才允许变更为已派单，防止同一订单被重复分配。
     */
    @Update("""
            UPDATE delivery_order
            SET rider_id = #{riderId}, status = #{assignedStatus}
            WHERE id = #{orderId} AND status = #{pendingStatus}
            """)
    int assignIfPending(@Param("orderId") Long orderId,
                        @Param("riderId") Long riderId,
                        @Param("pendingStatus") int pendingStatus,
                        @Param("assignedStatus") int assignedStatus);

    /** Only an assigned order can start; the condition closes the read-then-write race. */
    @Update("""
            UPDATE delivery_order
            SET status = #{deliveringStatus}
            WHERE id = #{orderId} AND status = #{assignedStatus}
            """)
    int startIfAssigned(@Param("orderId") Long orderId,
                        @Param("assignedStatus") int assignedStatus,
                        @Param("deliveringStatus") int deliveringStatus);

    /** Only a pending order can be canceled; it must not overwrite a concurrent assignment. */
    @Update("""
            UPDATE delivery_order
            SET status = #{canceledStatus}
            WHERE id = #{orderId} AND status = #{pendingStatus}
            """)
    int cancelIfPending(@Param("orderId") Long orderId,
                        @Param("pendingStatus") int pendingStatus,
                        @Param("canceledStatus") int canceledStatus);

    /**
     * 订单只允许从配送中完成一次，防止重复回调重复释放骑手名额。
     */
    @Update("""
            UPDATE delivery_order
            SET status = #{completedStatus}
            WHERE id = #{orderId} AND status = #{deliveringStatus}
            """)
    int completeIfDelivering(@Param("orderId") Long orderId,
                             @Param("deliveringStatus") int deliveringStatus,
                             @Param("completedStatus") int completedStatus);
}
