<template>
  <div class="app-container alert-page">
    <div class="page-toolbar"><el-radio-group v-model="filter" size="small"><el-radio-button label="all">全部</el-radio-button><el-radio-button label="unread">未读</el-radio-button></el-radio-group><el-button icon="el-icon-refresh" @click="load">刷新</el-button></div>
    <el-table v-loading="loading" :data="visibleRows" size="small" @row-click="goRank">
      <el-table-column label="状态" width="74"><template slot-scope="{ row }"><span :class="['status-dot', row.status]" />{{ row.status === 'unread' ? '未读' : '已读' }}</template></el-table-column>
      <el-table-column prop="keyword" label="关键词" min-width="160" />
      <el-table-column label="涨幅" width="120"><template slot-scope="{ row }"><strong class="rise">+{{ Number(row.pct_change).toFixed(1) }}%</strong></template></el-table-column>
      <el-table-column label="今日热度" width="130"><template slot-scope="{ row }">{{ number(row.heat_today) }}</template></el-table-column>
      <el-table-column label="昨日热度" width="130"><template slot-scope="{ row }">{{ number(row.heat_yesterday) }}</template></el-table-column>
      <el-table-column prop="stat_date" label="日期" width="130" />
      <el-table-column label="操作" width="90" align="center"><template slot-scope="{ row }"><el-button v-if="row.status === 'unread'" type="text" @click.stop="markRead(row)">标记已读</el-button></template></el-table-column>
      <template slot="empty"><el-empty :image-size="80" description="暂无暴涨告警" /></template>
    </el-table>
  </div>
</template>

<script>
import { getAlerts, readAlert } from '@/api/intel'
export default {
  name: 'IntelAlert', data() { return { rows: [], loading: false, filter: 'all' } },
  computed: { visibleRows() { return this.filter === 'unread' ? this.rows.filter(x => x.status === 'unread') : this.rows } },
  created() { this.load() },
  methods: {
    load() { this.loading = true; getAlerts().then(r => { this.rows = r.data || [] }).finally(() => { this.loading = false }) },
    markRead(row) { readAlert(row.id).then(() => { row.status = 'read'; this.$message.success('已标记') }) },
    goRank(row) { this.$router.push({ path: '/intel/rank', query: { keyword: row.keyword, date: row.stat_date } }) },
    number(value) { return Number(value || 0).toFixed(2) }
  }
}
</script>

<style scoped>
.page-toolbar { display:flex; align-items:center; justify-content:space-between; margin-bottom:14px; }.el-table /deep/ .el-table__row { cursor:pointer; }.status-dot { display:inline-block;width:7px;height:7px;border-radius:50%;background:#c0c4cc;margin-right:7px; }.status-dot.unread { background:#d94841; }.rise { color:#c43630;letter-spacing:0; }
</style>
