<template>
  <div class="app-container ai-image-container">
    <el-row :gutter="16">
      <!-- 左侧：参数面板 -->
      <el-col :xs="24" :sm="24" :md="10" :lg="8">
        <el-card shadow="never" class="param-card">
          <div slot="header" class="card-header">
            <span>AI 生图</span>
            <el-tag v-if="!options.configured" type="danger" size="mini">服务未配置</el-tag>
          </div>

          <el-form label-position="top" @submit.native.prevent>
            <el-form-item label="提示词">
              <el-input
                v-model="form.prompt"
                type="textarea"
                :rows="5"
                maxlength="2000"
                show-word-limit
                placeholder="描述你想要生成的图片，例如：白色背景的男士运动鞋产品图，专业摄影，细节清晰"
              />
            </el-form-item>

            <el-form-item label="参考图（可选，最多4张，PNG/JPG/WebP）">
              <el-upload
                ref="refUpload"
                action="#"
                list-type="picture-card"
                accept="image/png,image/jpeg,image/webp"
                :auto-upload="false"
                :file-list="fileList"
                :on-change="handleFileChange"
                :on-remove="handleFileRemove"
                :limit="4"
                :on-exceed="() => $message.warning('参考图最多4张')"
                multiple
              >
                <i class="el-icon-plus" />
              </el-upload>
              <div class="tip-text">上传参考图后按“图生图”处理；提示词作为修改要求，例如“让模特穿上图中衣服”</div>
            </el-form-item>

            <el-form-item label="尺寸">
              <el-select v-model="form.size" style="width: 100%" placeholder="选择尺寸">
                <el-option
                  v-for="s in options.sizes"
                  :key="s.value"
                  :label="s.label"
                  :value="s.value"
                />
              </el-select>
            </el-form-item>

            <el-form-item label="画质档位">
              <el-radio-group v-model="form.model">
                <el-radio-button
                  v-for="m in options.models"
                  :key="m.value"
                  :label="m.value"
                >{{ m.label }}</el-radio-button>
              </el-radio-group>
              <div v-if="form.model === '4k'" class="tip-text warn">4K 生成很慢且费用较高</div>
            </el-form-item>

            <el-button
              type="primary"
              style="width: 100%"
              :loading="generating"
              :disabled="!options.configured || generating"
              @click="handleGenerate"
            >{{ generating ? '生成中…' : '开始生成' }}</el-button>
          </el-form>
        </el-card>
      </el-col>

      <!-- 右侧：结果 + 历史 -->
      <el-col :xs="24" :sm="24" :md="14" :lg="16">
        <el-card shadow="never" class="result-card">
          <div slot="header" class="card-header">
            <span>生成结果</span>
            <span v-if="activeTask && activeTask.costSeconds" class="cost-text">耗时 {{ activeTask.costSeconds }}s</span>
          </div>

          <div v-if="!activeTask" class="result-empty">
            <i class="el-icon-picture-outline" />
            <p>输入提示词后点击“开始生成”</p>
          </div>

          <div v-else class="result-body">
            <div v-if="generating" class="result-loading">
              <i class="el-icon-loading" />
              <p>{{ activeTask.statusText || '排队中' }}，通常需要 30 秒到几分钟</p>
              <el-button size="mini" type="text" @click="cancelPolling">不再等待（任务会继续执行）</el-button>
            </div>
            <template v-else-if="activeTask.status === 2">
              <el-image
                :src="resolveUrl(activeTask.resultUrl)"
                fit="contain"
                :preview-src-list="[resolveUrl(activeTask.resultUrl)]"
                class="result-image"
              />
              <div class="result-actions">
                <el-button size="small" type="primary" plain icon="el-icon-download" @click="downloadImage(activeTask)">下载</el-button>
                <el-button size="small" plain icon="el-icon-document-copy" @click="copyText(resolveUrl(activeTask.resultUrl))">复制链接</el-button>
                <el-button size="small" plain icon="el-icon-picture-outline" @click="useAsRef(activeTask)">作为参考图</el-button>
              </div>
            </template>
            <div v-else-if="activeTask.status === 3" class="result-error">
              <i class="el-icon-warning-outline" />
              <p>{{ activeTask.error || '生成失败，请重试' }}</p>
              <el-button size="small" type="primary" plain @click="handleGenerate">重新生成</el-button>
            </div>
            <div class="result-prompt">提示词：{{ activeTask.prompt }}</div>
          </div>
        </el-card>

        <el-card shadow="never" class="history-card">
          <div slot="header" class="card-header">
            <span>历史记录</span>
            <el-button size="mini" type="text" icon="el-icon-refresh" @click="loadHistory">刷新</el-button>
          </div>
          <el-table v-loading="historyLoading" :data="historyList" size="small" @row-click="showHistory">
            <el-table-column label="图片" width="90">
              <template slot-scope="{ row }">
                <el-image
                  v-if="row.status === 2 && row.resultUrl"
                  :src="resolveUrl(row.resultUrl)"
                  fit="cover"
                  style="width: 64px; height: 64px; border-radius: 4px"
                />
                <span v-else>-</span>
              </template>
            </el-table-column>
            <el-table-column prop="prompt" label="提示词" show-overflow-tooltip />
            <el-table-column prop="size" label="尺寸" width="110" />
            <el-table-column label="状态" width="90">
              <template slot-scope="{ row }">
                <el-tag :type="statusTagType(row.status)" size="mini">{{ row.status | statusFilter }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="耗时" width="80">
              <template slot-scope="{ row }">{{ row.costSeconds ? row.costSeconds + 's' : '-' }}</template>
            </el-table-column>
            <el-table-column prop="createTime" label="时间" width="160" />
          </el-table>
          <pagination
            v-show="historyTotal > 0"
            :total="historyTotal"
            :page.sync="historyQuery.page"
            :limit.sync="historyQuery.limit"
            layout="total, prev, pager, next"
            @pagination="loadHistory"
          />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script>
import { generateImage, getTaskStatus, getHistoryList, getImageOptions } from '@/api/ai/image'

const POLL_INTERVAL = 3000
const POLL_MAX_MINUTES = 15

export default {
  name: 'AiImage',
  filters: {
    statusFilter(status) {
      return { 0: '排队中', 1: '生成中', 2: '已完成', 3: '失败' }[status] || '未知'
    }
  },
  data() {
    return {
      options: { configured: true, sizes: [], models: [], maxRefCount: 4 },
      form: {
        prompt: '',
        size: '1024x1024',
        model: 'auto'
      },
      fileList: [],
      generating: false,
      activeTaskId: null,
      activeTask: null,
      pollTimer: null,
      pollStart: 0,
      historyList: [],
      historyTotal: 0,
      historyLoading: false,
      historyQuery: { page: 1, limit: 10 }
    }
  },
  created() {
    this.loadOptions()
    this.loadHistory()
  },
  beforeDestroy() {
    this.stopPolling()
  },
  methods: {
    loadOptions() {
      getImageOptions().then(res => {
        if (res.code === 200 && res.data) {
          this.options = res.data
          if (!this.options.sizes.some(s => s.value === this.form.size) && this.options.sizes.length) {
            this.form.size = this.options.sizes[0].value
          }
        }
      })
    },
    // 后端相对路径（/ai-images/...）需加 API 前缀走代理；七牛等外链原样返回
    resolveUrl(url) {
      if (!url || /^https?:\/\//.test(url)) return url
      return (process.env.VUE_APP_BASE_API || '') + url
    },
    // 把当前结果图加入参考图区；提交时经 refUrls 通道（后端已支持 /ai-images/ 相对路径与七牛外链）
    useAsRef(task) {
      if (!task || task.status !== 2 || !task.resultUrl) {
        return
      }
      if (this.fileList.length >= 4) {
        this.$message.warning('参考图最多4张，请先移除现有参考图')
        return
      }
      if (this.fileList.some(f => f.refUrl === task.resultUrl)) {
        this.$message.info('该图已在参考图中')
        return
      }
      const name = task.resultUrl.substring(task.resultUrl.lastIndexOf('/') + 1) || 'ref.png'
      this.fileList.push({ name, url: this.resolveUrl(task.resultUrl), refUrl: task.resultUrl })
      this.$message.success('已加入参考图')
    },
    handleFileChange(file, fileList) {
      const allow = ['image/png', 'image/jpeg', 'image/webp']
      if (file.raw && !allow.includes(file.raw.type)) {
        this.$message.error('仅支持 PNG/JPG/WebP 格式')
        fileList.splice(fileList.indexOf(file), 1)
        return
      }
      if (file.raw && file.raw.size > 10 * 1024 * 1024) {
        this.$message.error('单张参考图不能超过 10MB')
        fileList.splice(fileList.indexOf(file), 1)
        return
      }
      this.fileList = fileList.slice(-4)
    },
    handleFileRemove(file, fileList) {
      this.fileList = fileList
    },
    handleGenerate() {
      if (!this.form.prompt || !this.form.prompt.trim()) {
        this.$message.warning('请输入提示词')
        return
      }
      const fd = new FormData()
      fd.append('prompt', this.form.prompt.trim())
      fd.append('size', this.form.size)
      fd.append('model', this.form.model)
      const refUrls = []
      this.fileList.forEach(f => {
        if (f.raw) {
          fd.append('files', f.raw)
        } else if (f.refUrl) {
          refUrls.push(f.refUrl)
        }
      })
      if (refUrls.length) {
        fd.append('refUrls', JSON.stringify(refUrls))
      }
      this.generating = true
      generateImage(fd).then(res => {
        if (res.code !== 200) {
          this.$message.error(res.msg || '提交失败')
          this.generating = false
          return
        }
        this.activeTaskId = res.data.taskId
        this.activeTask = { status: 0, statusText: '排队中', prompt: this.form.prompt.trim() }
        this.startPolling()
        this.loadHistory()
      }).catch(() => {
        this.generating = false
      })
    },
    startPolling() {
      this.pollStart = Date.now()
      this.pollTimer = setInterval(() => {
        if (Date.now() - this.pollStart > POLL_MAX_MINUTES * 60 * 1000) {
          this.stopPolling()
          this.generating = false
          this.$message.info('等待超时，可在历史记录中查看结果')
          return
        }
        getTaskStatus(this.activeTaskId).then(res => {
          if (res.code === 200 && res.data) {
            this.activeTask = { ...this.activeTask, ...res.data }
            if (res.data.status === 2 || res.data.status === 3) {
              this.stopPolling()
              this.generating = false
              if (res.data.status === 3) {
                this.$message.error(res.data.error || '生成失败')
              }
              this.loadHistory()
            }
          }
        }).catch(() => { /* 网络抖动继续轮询 */ })
      }, POLL_INTERVAL)
    },
    stopPolling() {
      if (this.pollTimer) {
        clearInterval(this.pollTimer)
        this.pollTimer = null
      }
    },
    cancelPolling() {
      this.stopPolling()
      this.generating = false
    },
    loadHistory() {
      this.historyLoading = true
      getHistoryList(this.historyQuery).then(res => {
        if (res.code === 200) {
          this.historyList = res.rows || []
          this.historyTotal = res.total || 0
        }
      }).finally(() => {
        this.historyLoading = false
      })
    },
    showHistory(row) {
      if (row.status === 0 || row.status === 1) {
        this.activeTaskId = row.id
        this.activeTask = { ...row }
        this.generating = true
        this.stopPolling()
        this.startPolling()
        return
      }
      this.activeTaskId = row.id
      this.activeTask = { ...row, resultUrl: row.resultUrl, error: row.errorMsg }
    },
    downloadImage(task) {
      const a = document.createElement('a')
      a.href = this.resolveUrl(task.resultUrl)
      a.download = `ai_${task.taskId || ''}.png`
      a.target = '_blank'
      a.click()
    },
    copyText(text) {
      navigator.clipboard ? navigator.clipboard.writeText(text).then(() => {
        this.$message.success('已复制')
      }) : this.$message.warning('浏览器不支持自动复制')
    },
    statusTagType(status) {
      return { 0: 'info', 1: 'warning', 2: 'success', 3: 'danger' }[status] || 'info'
    }
  }
}
</script>

<style scoped>
.ai-image-container {
  padding: 12px;
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}
.tip-text {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}
.tip-text.warn {
  color: #e6a23c;
}
.cost-text {
  color: #909399;
  font-size: 12px;
  font-weight: normal;
}
.result-empty,
.result-loading,
.result-error {
  padding: 48px 0;
  text-align: center;
  color: #909399;
}
.result-empty i,
.result-loading i,
.result-error i {
  font-size: 48px;
  margin-bottom: 12px;
}
.result-error i {
  color: #f56c6c;
}
.result-image {
  width: 100%;
  height: 420px;
  border-radius: 6px;
  background: #f5f7fa;
}
.result-actions {
  margin-top: 12px;
  text-align: center;
}
.result-prompt {
  margin-top: 12px;
  font-size: 12px;
  color: #909399;
  word-break: break-all;
}
.history-card {
  margin-top: 16px;
}
.el-table /deep/ .el-table__row {
  cursor: pointer;
}
</style>
