package com.kll.deliverydispatch.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kll.deliverydispatch.account.entity.Account;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AccountMapper extends BaseMapper<Account> {
    @Select("SELECT * FROM app_account WHERE username = #{username} LIMIT 1")
    Account selectByUsername(@Param("username") String username);

    @Select("SELECT * FROM app_account WHERE role = 'ADMIN' ORDER BY id FOR UPDATE")
    List<Account> selectAdminsForUpdate();
}
