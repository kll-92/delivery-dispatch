package com.kll.deliverydispatch.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kll.deliverydispatch.order.entity.DispatchOutboxMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface DispatchOutboxMapper extends BaseMapper<DispatchOutboxMessage> {

    @Delete("DELETE FROM dispatch_outbox WHERE order_id = #{orderId} AND status = 0")
    int deletePendingByOrderId(@Param("orderId") Long orderId);

    /**
     * 在事务中锁定待投递行；多个发布器并行运行时跳过彼此已锁定的行。
     */
    @Select("""
            SELECT id, order_id, event_type, status, created_at, published_at
            FROM dispatch_outbox
            WHERE status = 0
            ORDER BY id ASC
            LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    List<DispatchOutboxMessage> selectPendingForUpdate(@Param("limit") int limit);

    @Update("""
            UPDATE dispatch_outbox
            SET status = 1, published_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND status = 0
            """)
    int markAsPublished(@Param("id") Long id);
}
