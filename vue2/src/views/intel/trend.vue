<template>
  <div class="app-container trend-page">
    <aside class="keyword-panel">
      <div class="panel-title">关键词</div>
      <button v-for="item in keywords" :key="item.id" type="button" :class="['keyword-item', { active: item.keyword === selected }]" @click="select(item.keyword)">
        <span>{{ item.keyword }}</span><i class="el-icon-arrow-right" />
      </button>
      <el-empty v-if="!keywords.length" :image-size="64" description="尚未配置关键词" />
    </aside>
    <main v-loading="loading" class="chart-panel">
      <div class="chart-heading"><div><h3>{{ selected || '关键词趋势' }}</h3><span>最近 14 天</span></div><strong>{{ latestHeat }}</strong></div>
      <line-chart :chart-data="chartData" height="420px" />
    </main>
  </div>
</template>

<script>
import LineChart from '@/views/dashboard/LineChart'
import { getKeywords, getTrend } from '@/api/intel'
export default {
  name: 'IntelTrend', components: { LineChart },
  data() { return { keywords: [], selected: '', rows: [], loading: false } },
  computed: {
    chartData() { return { date: this.rows.map(x => x.stat_date), heatScore: this.rows.map(x => Number(x.heat_score || 0)), seriesName: '热度' } },
    latestHeat() { return this.rows.length ? Number(this.rows[this.rows.length - 1].heat_score || 0).toFixed(2) : '--' }
  },
  created() { getKeywords().then(r => { this.keywords = (r.data || []).filter(x => x.enabled); if (this.keywords.length) this.select(this.keywords[0].keyword) }) },
  methods: { select(word) { this.selected = word; this.loading = true; getTrend({ keyword: word, days: 14 }).then(r => { this.rows = r.data || [] }).finally(() => { this.loading = false }) } }
}
</script>

<style scoped>
.trend-page { display:grid; grid-template-columns:240px minmax(0,1fr); gap:22px; }
.keyword-panel { border-right:1px solid #e5e7eb; padding-right:16px; min-height:500px; }
.panel-title { color:#606266; font-weight:600; margin:8px 10px 12px; }
.keyword-item { width:100%; height:42px; border:0; background:transparent; padding:0 10px; display:flex; align-items:center; justify-content:space-between; cursor:pointer; color:#606266; }
.keyword-item.active { background:#f3f4f6; color:#b93832; font-weight:600; }
.chart-heading { display:flex; align-items:flex-start; justify-content:space-between; padding:4px 8px 18px; }
.chart-heading h3 { margin:0 0 6px; font-size:18px; }.chart-heading span { color:#909399; font-size:12px; }.chart-heading strong { font-size:26px; color:#303133; }
@media (max-width: 768px) { .trend-page { grid-template-columns:1fr; }.keyword-panel { border-right:0; border-bottom:1px solid #e5e7eb; min-height:0; padding-bottom:12px; } }
</style>
