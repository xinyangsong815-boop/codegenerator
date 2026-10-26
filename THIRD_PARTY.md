# 恢复与依赖来源

- 原始应用：用户提供的本机 2.1.0 Windows／Java17 JAR，SHA256 记录于 `baseline/provenance.json`。依赖版本与内置许可证保留于原 JAR 的 `BOOT-INF/lib/`。
- 反编译交叉检查：[CFR 0.152](https://www.benf.org/other/cfr/)。下载文件的 MD5 与作者公开值一致。
- 源码恢复：[Vineflower 1.11.2](https://github.com/Vineflower/vineflower/releases/tag/1.11.2)，通过 Maven Central 下载并核对其 SHA1。发布源码采用其恢复结果，另修复了两处泛型／局部变量还原错误。
- 这两个恢复工具只在恢复时使用，运行与从交付源码构建不需要它们。原始测试源码未包含在运行包内；本版验证代码为重新编写。

恢复编译后、功能修改前，对 6 组规格生成的全部文件进行了逐字节比较，与原始程序一致。该比较覆盖所列样例的生成行为，不等于证明原程序每个功能的行为都完全相同。
