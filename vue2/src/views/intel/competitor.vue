<template>
  <div class="app-container competitor-page">
    <div class="page-toolbar">
      <el-button type="primary" icon="el-icon-plus" @click="dialogVisible = true">添加竞品</el-button>
      <el-button icon="el-icon-refresh" @click="load">刷新</el-button>
    </div>
    <div v-loading="loading" class="account-grid">
      <article v-for="item in competitors" :key="item.id" :class="['account-card', { active: active && active.id === item.id }]" @click="choose(item)">
        <el-avatar :size="48" :src="item.avatar_url"><i class="el-icon-user-solid" /></el-avatar>
        <div class="account-main"><strong>{{ item.nickname || item.user_id }}</strong><span>粉丝 {{ formatCount(item.fans) }} · 关注 {{ formatCount(item.follows) }}</span><small>{{ item.last_crawled_at ? '采集于 ' + item.last_crawled_at : '等待首次采集' }}</small></div>
        <el-button type="text" icon="el-icon-delete" title="删除竞品" @click.stop="remove(item)" />
      </article>
    </div>
    <el-empty v-if="!loading && !competitors.length" description="尚未圈定竞品账号" />
    <section v-if="active" class="notes-section">
      <h3>{{ active.nickname || active.user_id }} · 最近笔记</h3>
      <el-table v-loading="notesLoading" :data="notes" size="small">
        <el-table-column label="封面" width="76"><template slot-scope="{ row }"><el-image v-if="row.cover_url" class="cover" :src="row.cover_url" fit="cover" /></template></el-table-column>
        <el-table-column prop="title" label="标题" min-width="240" show-overflow-tooltip />
        <el-table-column prop="liked_count" label="赞" width="90" /><el-table-column prop="collected_count" label="藏" width="90" /><el-table-column prop="comment_count" label="评" width="90" />
        <el-table-column prop="published_at" label="发布时间" width="170" />
        <el-table-column label="链接" width="72" align="center"><template slot-scope="{ row }"><el-button type="text" icon="el-icon-top-right" @click="open(row.note_url)" /></template></el-table-column>
      </el-table>
    </section>
    <el-dialog title="添加竞品账号" :visible.sync="dialogVisible" width="min(560px, 92vw)">
      <el-form label-position="top"><el-form-item label="小红书主页链接"><el-input v-model.trim="profileUrl" type="textarea" :rows="3" placeholder="https://www.xiaohongshu.com/user/profile/...?...xsec_token=..." @input="preview=null" /></el-form-item></el-form>
      <div v-if="preview" class="profile-preview"><el-avatar :size="44" :src="preview.avatar_url" /><div><strong>{{ preview.nickname }}</strong><span>小红书号 {{ preview.red_id || '-' }} · 粉丝 {{ formatCount(preview.fans) }}</span></div></div>
      <div slot="footer"><el-button @click="dialogVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="preview ? add() : loadPreview()">{{ preview ? '确认添加' : '预览账号' }}</el-button></div>
    </el-dialog>
  </div>
</template>

<script>
import { addCompetitor, deleteCompetitor, getCompetitorNotes, getCompetitors, previewCompetitor } from '@/api/intel'
export default {
  name: 'IntelCompetitor',
  data() { return { competitors: [], active: null, notes: [], loading: false, notesLoading: false, dialogVisible: false, profileUrl: '', preview: null, saving: false } },
  created() { this.load() },
  methods: {
    load() { this.loading = true; getCompetitors().then(r => { this.competitors = r.data || []; if (!this.active && this.competitors.length) this.choose(this.competitors[0]) }).finally(() => { this.loading = false }) },
    choose(item) { this.active = item; this.notesLoading = true; getCompetitorNotes(item.id).then(r => { this.notes = r.data || [] }).finally(() => { this.notesLoading = false }) },
    loadPreview() { if (!this.profileUrl) return this.$message.warning('请粘贴主页链接'); this.saving = true; previewCompetitor({ profile_url: this.profileUrl }).then(r => { this.preview = r.data }).finally(() => { this.saving = false }) },
    add() { this.saving = true; addCompetitor({ profile_url: this.profileUrl, ...this.preview }).then(() => { this.$message.success('竞品已添加'); this.profileUrl = ''; this.preview = null; this.dialogVisible = false; this.load() }).finally(() => { this.saving = false }) },
    remove(item) { this.$confirm(`删除竞品“${item.nickname || item.user_id}”？`, '确认').then(() => deleteCompetitor(item.id)).then(() => { if (this.active && this.active.id === item.id) { this.active = null; this.notes = [] } this.load() }).catch(() => {}) },
    open(url) { if (url) window.open(url, '_blank', 'noopener') },
    formatCount(value) { const n = Number(value || 0); return n >= 10000 ? (n / 10000).toFixed(1) + '万' : String(n) }
  }
}
</script>

<style scoped>
.page-toolbar { display:flex; gap:10px; margin-bottom:16px; }.account-grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(290px,1fr)); gap:12px; }
.account-card { min-height:88px; border:1px solid #e4e7ed; border-radius:6px; padding:14px; display:flex; align-items:center; gap:12px; cursor:pointer; }.account-card.active { border-color:#d94841; box-shadow:0 0 0 1px #d94841 inset; }
.account-main { min-width:0; flex:1; display:flex; flex-direction:column; gap:5px; }.account-main strong,.account-main span,.account-main small { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }.account-main span { color:#606266;font-size:13px; }.account-main small { color:#909399; }
.notes-section { margin-top:26px; }.notes-section h3 { font-size:16px; margin:0 0 12px; }.cover { width:48px;height:62px;border-radius:3px; }
.profile-preview { display:flex;align-items:center;gap:12px;padding:12px;background:#f5f7fa;border-radius:5px; }.profile-preview div { display:flex;flex-direction:column;gap:5px; }.profile-preview span { color:#606266;font-size:12px; }
</style>
