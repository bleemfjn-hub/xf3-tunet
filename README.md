# XF3 Tuner

vivo X Fold3 (V2303A) 专属性能优化工具。

## 功能

- 一键优化 / 全部还原
- 单项独立开关

## 写入通道

使用 `content insert/update/delete` 操作 `content://settings/{secure,system,global}`，
绕过 OriginOS 对 `settings put` 命令行的拦截。

## 构建

通过 GitHub Actions 自动编译，产物在 Actions 页面的 Artifacts 中。
