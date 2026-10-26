# 代码生成器

离线开发助手 **2.1.1-rebuild.8**，两个平台源码分别保存在独立分支：

| 分支 | 目标环境 |
|---|---|
| [generator-java17](https://github.com/xinyangsong815-boop/codegenerator/tree/generator-java17) | Java17 / Spring Boot 3.5.16 |
| [generator-java8](https://github.com/xinyangsong815-boop/codegenerator/tree/generator-java8) | Java8 / Spring Boot 2.7.18，麒麟 Linux 目标 |

各分支包含完整解包源码、离线构建依赖、构建脚本、测试证据和升级说明。先阅读分支根目录 README.md，再设置对应完整 JDK 构建。

每版16个生成工程实际通过819项测试，最终程序HTTP验收各76项通过。真实MySQL、麒麟实机、IAM及漏洞扫描等仍需在项目目标环境完成；详见升级说明。
