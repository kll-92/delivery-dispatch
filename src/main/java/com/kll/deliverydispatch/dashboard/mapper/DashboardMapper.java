package com.kll.deliverydispatch.dashboard.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

@Mapper
public interface DashboardMapper {

    @Select("""
            SELECT COUNT(*) AS totalOrders,
                   COALESCE(SUM(CASE WHEN status = #{pendingStatus} THEN 1 ELSE 0 END), 0) AS pendingOrders,
                   COALESCE(SUM(CASE WHEN status = #{assignedStatus} THEN 1 ELSE 0 END), 0) AS assignedOrders,
                   COALESCE(SUM(CASE WHEN status = #{deliveringStatus} THEN 1 ELSE 0 END), 0) AS deliveringOrders,
                   COALESCE(SUM(CASE WHEN status = #{completedStatus} THEN 1 ELSE 0 END), 0) AS completedOrders,
                   COALESCE(SUM(CASE WHEN status = #{canceledStatus} THEN 1 ELSE 0 END), 0) AS canceledOrders
            FROM delivery_order
            """)
    Map<String, Object> countOrders(@Param("pendingStatus") int pendingStatus,
                                   @Param("assignedStatus") int assignedStatus,
                                   @Param("deliveringStatus") int deliveringStatus,
                                   @Param("completedStatus") int completedStatus,
                                   @Param("canceledStatus") int canceledStatus);

    @Select("""
            SELECT COUNT(*) AS totalRiders,
                   COALESCE(SUM(CASE WHEN status = #{availableStatus}
                                          AND current_order_count < #{maxCapacity}
                                     THEN 1 ELSE 0 END), 0) AS availableRiders
            FROM rider
            """)
    Map<String, Object> countRiders(@Param("availableStatus") int availableStatus,
                                   @Param("maxCapacity") int maxCapacity);
}
