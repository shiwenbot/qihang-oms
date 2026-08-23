package cn.qihangerp.api.task;

import cn.qihangerp.api.controller.intel.MarketIntelService;
import cn.qihangerp.common.task.IPollableService;
import cn.qihangerp.model.entity.SysTask;
import cn.qihangerp.service.SysTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketIntelAggTask implements IPollableService {
    private static final long TASK_ID = 802L;
    private final SysTaskService taskService;
    private final MarketIntelService marketIntelService;

    @Override public void poll() {
        log.info("开始聚合市场情报数据");
        marketIntelService.aggregatePending();
    }

    @Override public String getCronExpression() {
        SysTask task = taskService.getById(TASK_ID);
        return task != null && Integer.valueOf(1).equals(task.getStatus()) ? task.getCron() : "-";
    }
}
