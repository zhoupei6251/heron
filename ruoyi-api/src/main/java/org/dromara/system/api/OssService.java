package org.dromara.system.api;

import org.dromara.system.api.domain.OssDTO;

import java.util.List;

/**
 * 通用 OSS服务
 *
 * @author Lion Li
 */
public interface OssService {

    /**
     * 通过ossId查询对应的url
     *
     * @param ossIds ossId串逗号分隔
     * @return url串逗号分隔
     */
    String selectUrlByIds(String ossIds);

    /**
     * 通过ossId查询列表
     *
     * @param ossIds ossId串逗号分隔
     * @return 列表
     */
    List<OssDTO> selectByIds(String ossIds);

    /**
     * 登记已经上传完成的对象；同一存储配置与对象键重复调用不新增记录。
     */
    void registerUploaded(OssDTO uploaded);

    /**
     * 按存储配置与对象键注销元数据，不删除桶对象。
     */
    void unregisterUploaded(String service, String fileName);
}
