# 发布包依赖清单

使用 Python 3.8 及以上运行，无须额外依赖，无网络请求，不执行或解压 JAR 到磁盘。

```text
python tools/dependency-inventory.py dist/实际发布包.jar --output-dir verification/supply-chain
```

生成 `dependency-inventory.json` 和 `sbom.cdx.json`（CycloneDX 1.6 JSON）。应在最终构建完成后运行，并将清单内的 `artifact.sha256` 与实际交付 JAR 再次核对。Java8 和 Java17 必须分别生成，不能共用清单。`status=COMPLETE` 仅表示该范围内打包清点和元数据读取完成，不表示坐标全部识别、许可证获批、没有漏洞或可投产。

退出码 0 表示清点完成，2 表示输入、读取、损坏元数据或扫描预算导致不完整；发布脚本必须检查退出码，不得沿用上次残留的结果。身份和许可不足单独保留：`coordinateStatus=UNIDENTIFIED/AMBIGUOUS`、`identityComplete=false`、`licenseReview=REVIEW_REQUIRED`。仅读取包内声明的坐标；不会把文件名猜测当作可信 Maven 坐标。存在多个 Maven 元数据时全部保留，并记录主坐标匹配方式。包内声明和哈希本身不能证明仓库来源或数字签名可信。

`BOOT-INF/lib/` 下每个文件都计数和计算 SHA256；嵌套 JAR 递归清点并保留物理父级。许可证文件记录位置、大小和哈希；POM/Manifest 中的许可声明作为观察证据保存，不自动批准或推断 SPDX 标识。输出不虚构 Maven 依赖关系图；未包含的 JDK、操作系统、仅构建/测试依赖及无元数据的 shaded 代码在范围说明中列出。递归、大小和解压预算超限会明确标为不完整。

`vulnerabilityScan` 固定为 `NOT_RUN`，本工具没有漏洞数据库，也未进行漏洞、来源、签名或组织许可政策验收。开发人员应使用本清单在组织批准的平台继续扫描和处置。SBOM 的结构参考 [CycloneDX 官方 1.6 JSON Schema](https://raw.githubusercontent.com/CycloneDX/specification/1.6/schema/bom-1.6.schema.json)。

验证扫描器及实际发布包：

```text
python verification/test_dependency_inventory.py --jar dist/实际发布包.jar --output verification/dependency-inventory-v6.json
```
