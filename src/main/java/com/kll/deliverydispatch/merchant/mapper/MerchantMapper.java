package com.kll.deliverydispatch.merchant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kll.deliverydispatch.merchant.entity.Merchant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 商家数据访问。 */
@Mapper
public interface MerchantMapper extends BaseMapper<Merchant> {

    /** 按登录账号查店铺，未开店返回 null。 */
    @Select("SELECT * FROM merchant WHERE account_id = #{accountId}")
    Merchant selectByAccountId(@Param("accountId") Long accountId);
}
