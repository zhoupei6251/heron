package org.dromara.heron.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 只读解析 sys_oss 主键，避免为了一个回填 ID 修改公共 OSS API。 */
@Mapper
public interface TrendLearnOssMapper {

    @Select("SELECT oss_id FROM sys_oss WHERE service = #{service} AND file_name = #{fileName} LIMIT 1")
    Long findId(@Param("service") String service, @Param("fileName") String fileName);
}
