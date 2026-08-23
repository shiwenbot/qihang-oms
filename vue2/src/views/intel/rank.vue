<template>
  <div class="app-container intel-page">
    <div class="page-toolbar">
      <el-select v-model="query.keyword" clearable filterable placeholder="全部关键词" @change="load">
        <el-option v-for="item in keywords" :key="item.id" :label="item.keyword" :value="item.keyword" />
      </el-select>
      <el-date-picker v-model="query.date" type="date" value-format="yyyy-MM-dd" placeholder="选择日期" @change="load" />
      <el-button icon="el-icon-refresh" @click="load">刷新</el-button>
    </div>
    <el-table v-loading="loading" :data="rows" size="small">
      <el-table-column prop="rank" label="#" width="54" align="center" />
      <el-table-column label="封面" width="82">
        <template slot-scope="{ row }"><el-image v-if="row.cover_url" :src="row.cover_url" fit="cover" class="cover" /></template>
      </el-table-column>
      <el-table-column prop="keyword" label="关键词" width="140" />
      <el-table-column prop="title" label="标题" min-width="240" show-overflow-tooltip />
      <el-table-column prop="nickname" label="作者" width="140" show-overflow-tooltip />
      <el-table-column prop="liked_count" label="赞" width="88" sortable />
      <el-table-column prop="collected_count" label="藏" width="88" />
      <el-table-column prop="comment_count" label="评" width="88" />
      <el-table-column label="链接" width="74" align="center">
        <template slot-scope="{ row }"><el-button type="text" icon="el-icon-top-right" title="打开笔记" @click="open(row.note_url)" /></template>
      </el-table-column>
      <template slot="empty">
        <div class="empty-state"><span>今日尚未采集</span><el-button type="text" :loading="running" @click="run">手动跑一次</el-button></div>
      </template>
    </el-table>
  </div>
</template>

<script>
import { getKeywords, getRank, runIntel } from '@/api/intel'
export default {
  name: 'IntelRank',
  data() { return { loading: false, running: false, rows: [], keywords: [], query: { keyword: this.$route.query.keyword || '', date: this.today() } } },
  created() { getKeywords().then(r => { this.keywords = r.data || [] }); this.load() },
  methods: {
    today() { const d = new Date(); return [d.getFullYear(), String(d.getMonth() + 1).padStart(2, '0'), String(d.getDate()).padStart(2, '0')].join('-') },
    load() { this.loading = true; getRank(this.query).then(r => { this.rows = r.data || [] }).finally(() => { this.loading = false }) },
    open(url) { if (url) window.open(url, '_blank', 'noopener') },
    run() { this.running = true; runIntel().then(r => this.$message.success(r.data.existing ? '已有采集任务正在运行' : '采集任务已提交')).finally(() => { this.running = false }) }
  }
}
</script>

<style scoped>
.page-toolbar { display:flex; gap:10px; flex-wrap:wrap; margin-bottom:14px; }
.cover { width:56px; height:72px; border-radius:4px; }
.empty-state { display:flex; gap:12px; justify-content:center; align-items:center; padding:34px; }
</style>
