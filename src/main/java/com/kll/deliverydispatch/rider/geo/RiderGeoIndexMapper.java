package com.kll.deliverydispatch.rider.geo;

import com.kll.deliverydispatch.rider.entity.Rider;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/** Database reads used only by GEO indexing and recovery. */
@Mapper
public interface RiderGeoIndexMapper {

    @Select("""
            SELECT id, status, longitude, latitude, current_order_count,
                   last_location_at AS lastLocationAt
            FROM rider
            WHERE id = #{riderId}
            """)
    Rider selectRiderById(@Param("riderId") Long riderId);

    @Select("SELECT UTC_TIMESTAMP(6)")
    LocalDateTime selectUtcNow();

    @Select("""
            SELECT id
            FROM rider
            WHERE id > #{afterId}
            ORDER BY id ASC
            LIMIT #{limit}
            """)
    List<Long> selectRiderIdsAfter(@Param("afterId") long afterId, @Param("limit") int limit);
}
