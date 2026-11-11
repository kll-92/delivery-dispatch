package com.kll.deliverydispatch.rider.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kll.deliverydispatch.rider.entity.Rider;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Mapper
public interface RiderMapper extends BaseMapper<Rider> {

    /** 按登录账号查骑手，未绑定时返回 null。 */
    @Select("SELECT * FROM rider WHERE account_id = #{accountId}")
    Rider selectByAccountId(@Param("accountId") Long accountId);

    @Select("SELECT * FROM rider WHERE id = #{riderId} FOR UPDATE")
    Rider selectByIdForUpdate(@Param("riderId") Long riderId);

    @Select("SELECT UTC_TIMESTAMP(6)")
    LocalDateTime selectUtcNow();

    @Update("""
            UPDATE rider
            SET longitude = #{longitude},
                latitude = #{latitude},
                last_location_at = UTC_TIMESTAMP(6)
            WHERE id = #{riderId}
            """)
    int updateLocation(@Param("riderId") Long riderId,
                       @Param("longitude") BigDecimal longitude,
                       @Param("latitude") BigDecimal latitude);

    /**
     * Profile writes refresh location age only when a supplied coordinate pair actually changes.
     * Keep the CASE assignment before coordinate assignments so MySQL compares the old columns.
     */
    @Update("""
            UPDATE rider
            SET last_location_at = CASE
                    WHEN #{longitude} IS NOT NULL
                     AND NOT (longitude <=> #{longitude} AND latitude <=> #{latitude})
                    THEN UTC_TIMESTAMP(6)
                    ELSE last_location_at
                END,
                name = #{name},
                phone = #{phone},
                longitude = CASE WHEN #{longitude} IS NOT NULL THEN #{longitude} ELSE longitude END,
                latitude = CASE WHEN #{latitude} IS NOT NULL THEN #{latitude} ELSE latitude END
            WHERE id = #{riderId}
            """)
    int updateProfile(@Param("riderId") Long riderId,
                      @Param("name") String name,
                      @Param("phone") String phone,
                      @Param("longitude") BigDecimal longitude,
                      @Param("latitude") BigDecimal latitude);

    /**
     * 数据库原子占用：只有可用、未满载且位置仍新鲜的骑手才能成功加一。
     */
    @Update("""
            UPDATE rider
            SET current_order_count = current_order_count + 1
            WHERE id = #{riderId}
              AND status = #{availableStatus}
              AND current_order_count >= 0
              AND current_order_count < #{maxOrderCount}
              AND longitude IS NOT NULL
              AND latitude IS NOT NULL
              AND last_location_at IS NOT NULL
              AND last_location_at > UTC_TIMESTAMP(6) - INTERVAL #{ttlSeconds} SECOND
              AND last_location_at <= UTC_TIMESTAMP(6)
            """)
    int tryClaimForOrder(@Param("riderId") Long riderId,
                         @Param("availableStatus") int availableStatus,
                         @Param("maxOrderCount") int maxOrderCount,
                         @Param("ttlSeconds") long ttlSeconds);

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

    /** Re-enable only an idle disabled rider; never overwrite a concurrent order count. */
    @Update("""
            UPDATE rider
            SET status = #{availableStatus}
            WHERE id = #{riderId}
              AND status = #{disabledStatus}
              AND current_order_count = 0
            """)
    int enableIfIdle(@Param("riderId") Long riderId,
                     @Param("disabledStatus") int disabledStatus,
                     @Param("availableStatus") int availableStatus);
}
