# Colorwheel Iris Compat

这是一个面向 `Minecraft 1.21.1 NeoForge` 的客户端兼容补丁 mod，用于缓解 `Colorwheel + Iris + Flywheel` 在嵌入式与变换实例渲染路径上的兼容性问题。

## 目标

当前补丁主要针对以下几类问题：

- `flw_light(...)` 在特定 shader 变体中签名不兼容，导致编译失败。
- 某些 `varying` 在顶点、几何、片元阶段之间没有正确衔接，导致链接失败。
- `Create Aeronautics / Simulated` 触发的 `embedded`、`transformed`、`create_instance_rotating` 等渲染路径，在部分光影包下会放大上述问题。

## 实现思路

- 不修改 `Create`、`Simulated`、`Iris`、`Colorwheel` 本体源码。
- 通过 Mixin 在运行时拦截 `Colorwheel` / `Iris` 的 shader 构建流程。
- 对已知会失败的 shader 进行最小化回退与接口补齐，优先目标是避免崩溃并尽可能保留可视化效果。

## 当前包含的修复

- 在 `flw_light(...)` 相关顶点 shader 编译失败时，自动尝试兼容回退。
- 当几何或片元阶段引用了前一阶段未声明的输入时，自动补齐缺失的 `out/in varying` 与默认值。
- 对 `Colorwheel` 的 `embedded` / `transformed` 渲染路径做通用兜底，减少针对单个变量名逐个修补的需求。

## 构建

```powershell
.\gradlew.bat build
```

构建产物默认生成在 `build\libs\` 目录下。

## AI 生成说明

- 本项目包含 AI 生成与 AI 辅助修改的代码、构建脚本和文档。
- AI 生成内容由人工根据实际日志、源码行为与运行结果反复调整，不保证对所有光影包和整合包环境都完全适用。
