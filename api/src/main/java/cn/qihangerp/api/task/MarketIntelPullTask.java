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
public class MarketIntelPullTask implements IPollableService {
    private static final long TASK_ID = 801L;
    private final SysTaskService taskService;
    private final MarketIntelService marketIntelService;

    @Override public void poll() {
        log.info("开始异步派发市场情报采集任务");
        marketIntelService.createJobsForAllMerchants();
    }

    @Override public String getCronExpression() {
        SysTask task = taskService.getById(TASK_ID);
        return task != null && Integer.valueOf(1).equals(task.getStatus()) ? task.getCron() : "-";
    }
}
