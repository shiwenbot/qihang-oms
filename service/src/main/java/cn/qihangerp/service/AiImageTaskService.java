package cn.qihangerp.service;

import cn.qihangerp.common.PageQuery;
import cn.qihangerp.common.PageResult;
import cn.qihangerp.model.entity.AiImageTask;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * AI生图任务 Service
 */
public interface AiImageTaskService extends IService<AiImageTask> {

    /**
     * 分页查询指定用户的生图历史
     */
    PageResult<AiImageTask> queryUserPage(String createBy, PageQuery pageQuery);
}
