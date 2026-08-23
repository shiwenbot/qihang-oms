import request from '@/utils/request'

// AI生图相关API（生成走异步任务+轮询，提交接口本身不需要长超时）

// 提交生图任务：formData = { prompt, size, model, files[]? , refUrls? }
// 返回 { taskId }
export function generateImage(formData) {
  return request({
    url: '/api/erp-api/ai/image/generate',
    method: 'post',
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 30000
  })
}

// 查询任务状态：{ taskId, status, statusText, resultUrl, error, costSeconds }
export function getTaskStatus(taskId) {
  return request({
    url: `/api/erp-api/ai/image/task/${taskId}`,
    method: 'get'
  })
}

// 历史记录分页
export function getHistoryList(params) {
  return request({
    url: '/api/erp-api/ai/image/history',
    method: 'get',
    params: {
      pageNum: params.page || params.pageNum,
      pageSize: params.limit || params.pageSize
    }
  })
}

// 参数选项：{ configured, sizes[], models[], maxRefCount }
export function getImageOptions() {
  return request({
    url: '/api/erp-api/ai/image/options',
    method: 'get'
  })
}
