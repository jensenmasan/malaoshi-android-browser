# 马老师专属 · 安卓浏览器

基于 **GeckoView**(Mozilla Firefox 的安卓内核)自研的安卓浏览器,桌面名称、图标、主页均已定制为「马老师专属」。

## 定制内容

| 项目 | 说明 |
|---|---|
| 桌面名称 | 马老师专属 |
| 图标 | 红底「马」字,含自适应图标(Android 8+)与 5 档密度 |
| 主页 | 内置页面,顶部显示「马老师祝您新年快乐」,含搜索框与常用网站 |
| 关于页 | 仅显示版本与内核信息(无源码/赞助/外部链接入口) |

## 功能

- 多标签页(新建 / 切换 / 关闭)
- 地址栏:输入网址直达,输入关键词用必应搜索
- 前进 / 后退 / 刷新 / 回主页
- 页面加载进度条
- 可作为系统默认浏览器(支持 http/https 链接打开)

## 构建

推送到 `main` 会触发 GitHub Actions 自动构建,产物同时上传为 Artifact 并发布到 Release。

本地构建需要 JDK 17 + Android SDK(platform 36 / build-tools 36.0.0)+ Gradle 8.11.1:

```bash
gradle assembleRelease
```

## 签名

正式包使用仓库 Secrets 中的 keystore 签名;若未配置,则自动退化为 debug 签名包(同样可直接安装)。

## 许可

本项目为自研应用,使用 Mozilla 的 GeckoView 库(MPL-2.0)。
