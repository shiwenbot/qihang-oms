<template>
  <div class="app-container config-page">
    <section class="config-section status-band">
      <div><h3>采集账号</h3><div class="scope-controls"><el-select v-model="provider" size="small" @change="changeScope"><el-option v-for="item in providers" :key="item.provider" :label="item.name" :value="item.provider" /></el-select><el-input v-model.trim="accountId" size="small" maxlength="50" @change="changeScope" /></div><p :class="credentialPresent ? 'ok' : 'error'">{{ accountStatus }}</p><small v-if="sidecar.last_ok_at">最近成功 {{ sidecar.last_ok_at }}</small></div>
      <div class="status-actions">
        <el-button v-if="canManageAuth" :type="credentialPresent ? 'default' : 'primary'" icon="el-icon-full-screen" @click="startLogin">{{ credentialPresent ? '重新登录' : '连接账号' }}</el-button>
        <el-button v-if="canManageAuth && credentialPresent" type="text" class="danger" @click="logout">退出账号</el-button>
        <el-button type="primary" icon="el-icon-video-play" :loading="running" :disabled="!credentialPresent" @click="run">立即采集</el-button>
      </div>
    </section>
    <section class="config-section">
      <div class="section-heading"><div><h3>种子词</h3><span>{{ keywords.length }} / 50</span></div><el-button size="small" type="primary" icon="el-icon-plus" @click="newKeyword">添加</el-button></div>
      <el-table v-loading="loading" :data="keywords" size="small">
        <el-table-column prop="keyword" label="关键词" min-width="200" />
        <el-table-column label="排序" width="130"><template>最多点赞</template></el-table-column>
        <el-table-column label="启用" width="90"><template slot-scope="{ row }"><el-switch v-model="row.enabled" :active-value="1" :inactive-value="0" @change="saveKeyword(row)" /></template></el-table-column>
        <el-table-column label="操作" width="130" align="right"><template slot-scope="{ row }"><el-button type="text" @click="editKeyword(row)">编辑</el-button><el-button type="text" class="danger" @click="removeKeyword(row)">删除</el-button></template></el-table-column>
      </el-table>
    </section>
    <section class="config-section">
      <div class="section-heading"><div><h3>竞品账号</h3><span>{{ competitors.length }} 个</span></div><el-button size="small" icon="el-icon-plus" @click="competitorDialog=true">添加</el-button></div>
      <el-table :data="competitors" size="small"><el-table-column prop="nickname" label="昵称"><template slot-scope="{ row }">{{ row.nickname || row.user_id }}</template></el-table-column><el-table-column prop="red_id" label="小红书号" /><el-table-column prop="fans" label="粉丝" width="110" /><el-table-column prop="last_crawled_at" label="上次采集" width="180" /><el-table-column label="操作" width="80" align="right"><template slot-scope="{ row }"><el-button type="text" class="danger" @click="removeCompetitor(row)">删除</el-button></template></el-table-column></el-table>
    </section>
    <section class="config-section"><div class="section-heading"><div><h3>最近任务</h3><span>保留最近 10 条</span></div><el-button size="small" icon="el-icon-refresh" @click="loadStatus">刷新</el-button></div><el-table :data="jobs" size="small"><el-table-column prop="id" label="任务" width="90" /><el-table-column label="状态" width="110"><template slot-scope="{ row }"><el-tag size="mini" :type="statusType(row.status)">{{ row.status }}</el-tag></template></el-table-column><el-table-column prop="item_count" label="笔记" width="90" /><el-table-column prop="error_count" label="错误" width="90" /><el-table-column prop="error_msg" label="信息" show-overflow-tooltip /><el-table-column prop="create_time" label="创建时间" width="180" /></el-table></section>
    <el-dialog :title="keywordForm.id ? '编辑关键词' : '添加关键词'" :visible.sync="keywordDialog" width="min(440px, 92vw)"><el-input v-model.trim="keywordForm.keyword" maxlength="100" placeholder="输入关键词" @keyup.enter.native="submitKeyword" /><div slot="footer"><el-button @click="keywordDialog=false">取消</el-button><el-button type="primary" @click="submitKeyword">保存</el-button></div></el-dialog>
    <el-dialog title="添加竞品账号" :visible.sync="competitorDialog" width="min(560px, 92vw)"><el-input v-model.trim="profileUrl" type="textarea" :rows="3" placeholder="粘贴带 xsec_token 的小红书主页链接" @input="competitorPreview=null" /><div v-if="competitorPreview" class="profile-preview"><el-avatar :size="42" :src="competitorPreview.avatar_url" /><div><strong>{{ competitorPreview.nickname }}</strong><span>粉丝 {{ competitorPreview.fans || 0 }}</span></div></div><div slot="footer"><el-button @click="competitorDialog=false">取消</el-button><el-button type="primary" @click="competitorPreview ? submitCompetitor() : loadCompetitorPreview()">{{ competitorPreview ? '确认添加' : '预览账号' }}</el-button></div></el-dialog>
    <el-dialog title="连接小红书账号" :visible.sync="qrDialog" width="min(420px, 92vw)" :close-on-click-modal="false" @closed="stopQrPolling">
      <div class="qr-login">
        <el-image v-if="qrState.qr_image" :src="qrState.qr_image" class="qr-image" fit="contain" />
        <i v-else-if="qrState.status === 'success'" class="el-icon-circle-check qr-success" />
        <i v-else-if="qrState.status === 'fail' || qrState.status === 'expired'" class="el-icon-warning-outline qr-error" />
        <i v-else class="el-icon-loading qr-loading" />
        <strong>{{ qrState.message || '正在准备登录环境' }}</strong>
        <span v-if="qrState.nickname">{{ qrState.nickname }}</span>
      </div>
      <div slot="footer"><el-button v-if="['fail','expired'].includes(qrState.status)" type="primary" @click="startLogin">重新生成</el-button><el-button @click="qrDialog=false">关闭</el-button></div>
    </el-dialog>
  </div>
</template>

<script>
import { addCompetitor, addKeyword, deleteCompetitor, deleteKeyword, getCompetitors, getIntelQrStatus, getIntelScope, getIntelStatus, getKeywords, logoutIntel, previewCompetitor, runIntel, setIntelScope, startIntelQrLogin, updateKeyword } from '@/api/intel'
export default {
  name: 'IntelConfig',
  data() { const scope = getIntelScope(); return { loading: false, running: false, provider: scope.provider, accountId: scope.accountId, providers: [{ provider: 'xiaohongshu', name: '小红书' }], keywords: [], competitors: [], sidecar: {}, canManageAuth: false, jobs: [], keywordDialog: false, competitorDialog: false, keywordForm: {}, profileUrl: '', competitorPreview: null, qrDialog: false, qrState: {}, qrTimer: null } },
  computed: {
    cookieOk() { return Boolean(this.sidecar && this.sidecar.cookie_ok) },
    credentialPresent() { return Boolean(this.sidecar && this.sidecar.credential_present) },
    accountStatus() { if (!this.credentialPresent) return '尚未连接'; return `${this.sidecar.nickname || '已连接'}${this.cookieOk ? '' : ' · 待验证'}` }
  },
  created() { this.load(); this.loadStatus() },
  beforeDestroy() { this.stopQrPolling() },
  methods: {
    scope() { return { provider: this.provider, accountId: this.accountId || 'default' } },
    changeScope() { this.stopQrPolling(); this.accountId = this.accountId || 'default'; setIntelScope(this.scope()); this.load(); this.loadStatus() },
    load() { this.loading = true; Promise.all([getKeywords(this.scope()), getCompetitors(this.scope())]).then(([k, c]) => { this.keywords = k.data || []; this.competitors = c.data || [] }).finally(() => { this.loading = false }) },
    loadStatus() { getIntelStatus(this.scope()).then(r => { const data = r.data || {}; this.sidecar = data.sidecar || {}; this.providers = this.sidecar.providers || this.providers; this.canManageAuth = Boolean(data.can_manage_auth); this.jobs = data.jobs || [] }) },
    startLogin() { this.stopQrPolling(); this.qrDialog = true; this.qrState = { status: 'preparing', message: '正在准备登录环境' }; startIntelQrLogin(this.scope()).then(r => { this.qrState = r.data || this.qrState; this.pollQrStatus() }).catch(() => { this.qrState = { status: 'fail', message: '无法启动登录' } }) },
    pollQrStatus() { if (!this.qrDialog || !this.qrState.session_id) return; getIntelQrStatus(this.qrState.session_id, this.scope()).then(r => { this.qrState = r.data || this.qrState; if (this.qrState.status === 'success') { this.$message.success('采集账号连接成功'); this.loadStatus(); return } if (['fail', 'expired'].includes(this.qrState.status)) return; this.qrTimer = setTimeout(this.pollQrStatus, 2000) }).catch(() => { this.qrTimer = setTimeout(this.pollQrStatus, 3000) }) },
    stopQrPolling() { if (this.qrTimer) { clearTimeout(this.qrTimer); this.qrTimer = null } },
    logout() { this.$confirm('退出当前采集账号？', '确认').then(() => logoutIntel(this.scope())).then(() => { this.$message.success('已退出采集账号'); this.loadStatus() }).catch(() => {}) },
    newKeyword() { this.keywordForm = { keyword: '', enabled: true, sort_type: 2 }; this.keywordDialog = true },
    editKeyword(row) { this.keywordForm = { ...row, enabled: Boolean(row.enabled) }; this.keywordDialog = true },
    submitKeyword() { if (!this.keywordForm.keyword) return this.$message.warning('请输入关键词'); const params = this.scope(); const action = this.keywordForm.id ? updateKeyword(this.keywordForm.id, this.keywordForm, params) : addKeyword(this.keywordForm, params); action.then(() => { this.keywordDialog = false; this.load() }) },
    saveKeyword(row) { updateKeyword(row.id, { ...row, enabled: Boolean(row.enabled) }, this.scope()) },
    removeKeyword(row) { this.$confirm(`删除关键词“${row.keyword}”？`, '确认').then(() => deleteKeyword(row.id, this.scope())).then(this.load).catch(() => {}) },
    loadCompetitorPreview() { if (!this.profileUrl) return this.$message.warning('请粘贴主页链接'); previewCompetitor({ profile_url: this.profileUrl, provider: this.provider, account_id: this.accountId }).then(r => { this.competitorPreview = r.data }) },
    submitCompetitor() { addCompetitor({ profile_url: this.profileUrl, ...this.competitorPreview, provider: this.provider, account_id: this.accountId }).then(() => { this.profileUrl = ''; this.competitorPreview = null; this.competitorDialog = false; this.load() }) },
    removeCompetitor(row) { this.$confirm('删除该竞品账号？', '确认').then(() => deleteCompetitor(row.id, this.scope())).then(this.load).catch(() => {}) },
    run() { this.running = true; runIntel({ provider: this.provider, account_id: this.accountId }).then(r => { this.$message.success(r.data.existing ? '已有任务正在运行' : '采集任务已提交'); this.loadStatus() }).finally(() => { this.running = false }) },
    statusType(status) { return { success: 'success', fail: 'danger', timeout: 'danger', running: 'warning', pending: 'info' }[status] || 'info' }
  }
}
</script>

<style scoped>
.config-page { max-width:1100px; }.config-section { padding:20px 0; border-bottom:1px solid #e4e7ed; }.config-section:first-child { padding-top:4px; }.status-band,.section-heading { display:flex; align-items:center; justify-content:space-between; gap:16px; }.status-actions { display:flex;align-items:center;gap:8px;flex-wrap:wrap;justify-content:flex-end; }.config-section h3 { font-size:16px;margin:0 0 5px; }.config-section p { margin:0 0 4px;font-weight:600; }.config-section small,.section-heading span { color:#909399;font-size:12px; }.section-heading { margin-bottom:12px; }.section-heading > div { display:flex;align-items:baseline;gap:10px; }.ok { color:#27844b; }.error,.danger { color:#c43630; }
.scope-controls { display:flex;gap:8px;margin:8px 0; }.scope-controls .el-select { width:130px; }.scope-controls .el-input { width:160px; }
.profile-preview { display:flex;align-items:center;gap:12px;margin-top:12px;padding:12px;background:#f5f7fa;border-radius:5px; }.profile-preview > div { display:flex;flex-direction:column;gap:5px; }.profile-preview span { color:#606266;font-size:12px; }
.qr-login { min-height:300px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:14px;text-align:center; }.qr-image { width:260px;height:260px; }.qr-loading,.qr-success,.qr-error { font-size:64px; }.qr-loading { color:#909399; }.qr-success { color:#27844b; }.qr-error { color:#c43630; }.qr-login span { color:#606266; }
@media (max-width: 700px) { .status-band { align-items:flex-start;flex-direction:column; }.status-actions { justify-content:flex-start; }.qr-image { width:230px;height:230px; } }
</style>
