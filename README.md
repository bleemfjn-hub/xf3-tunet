# XF3 Tuner

vivo X Fold3 (V2303A) 专属性能优化工具。

## 功能

- 一键优化 / 全部还原
- 单项独立开关

## 下载

APK 构建后自动提交到仓库根目录 `xf3-tuner.apk`。

## 写入通道

使用 `content insert/update/delete` 操作 `content://settings/{secure,system,global}`，
绕过 OriginOS 对 `settings put` 命令行的拦截。
