// 菜单主题变量（JS 侧使用）
// 与 variables.scss 的 :export 保持同值；不直接 import scss 是因为
// 生产构建下 css 提取管线会让 scss 的 JS 导出变为 undefined，
// 导致侧边栏渲染抛错（TypeError: reading 'menuLightBackground'）。
export default {
  menuColor: 'rgba(0,0,0,.70)',
  menuLightColor: '#bfcbd9',
  menuColorActive: '#409EFF',
  menuBackground: '#ffffff',
  menuLightBackground: '#304156',
  subMenuBackground: '#fafafa',
  subMenuHover: '#f5f7fa',
  sideBarWidth: '200px',
  logoTitleColor: '#001529',
  logoLightTitleColor: '#ffffff'
}
