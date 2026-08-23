package cn.qihangerp.service.impl;

import cn.qihangerp.common.PageQuery;
import cn.qihangerp.common.PageResult;
import cn.qihangerp.mapper.AiImageTaskMapper;
import cn.qihangerp.model.entity.AiImageTask;
import cn.qihangerp.service.AiImageTaskService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * @description 针对表【ai_image_task(AI生图任务表)】的数据库操作Service实现
 */
@Service
public class AiImageTaskServiceImpl extends ServiceImpl<AiImageTaskMapper, AiImageTask>
    implements AiImageTaskService {

    @Override
    public PageResult<AiImageTask> queryUserPage(String createBy, PageQuery pageQuery) {
        LambdaQueryWrapper<AiImageTask> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(StringUtils.hasText(createBy), AiImageTask::getCreateBy, createBy);
        queryWrapper.orderByDesc(AiImageTask::getId);
        Page<AiImageTask> pages = this.baseMapper.selectPage(pageQuery.build(), queryWrapper);
        return PageResult.build(pages);
    }
}
