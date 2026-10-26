# 离线开发助手 2.1.1-rebuild.8-java8

Java 8 / Spring Boot 2.7.18，模板rebuild-v19。两版同步升级，分别使用真实目标JDK编译和测试。详见 升级说明与整改对照.md 和 verification-summary.json。

运行包：准备完整的目标JDK/JRE后运行 bash start.sh（Java8麒麟），或 start-windows.cmd（Java17 Windows），访问 http://127.0.0.1:8790。JDK没有随包分发。独立数据目录可通过 --tool.data-dir=/实际路径 指定。

源码包：设置JAVA_HOME为完整JDK 8，执行 ./build.ps1 或 bash build.sh。59个应用Java源码从src编译，vendor/runtime-base.jar仅含框架依赖；构建生成器无需Maven或网络。构建输出dist/offline-dev-assistant-2.1.1-rebuild.8-java8-kylin-linux.jar。./verify.ps1 或 bash verify-source.sh 执行源码/契约/回归/页面测试，需要Node.js；-GeneratedProjects追加生成工程Maven离线测试，需要完整依赖缓存。

新增依赖清点工具需要Python3.8+标准库，见tools/DEPENDENCY-INVENTORY.md。运行包supply-chain保存实际JAR清单与CycloneDX SBOM。新增独立Assembler和映射测试、纯访问器注释整理、Outbox取消边界修复，以及文档、契约、测试报告、网站异步流程修复，详见升级说明。

升级前停止旧进程并备份data，用副本验证；不要两个进程同时写一个data。仅本机访问模式。真实MySQL、IAM、请求幂等、真实消息系统、漏洞和麒麟实机验收仍待完成，不能直接据本包宣布生产可用。原2.2.1源码尚未取回，本版沿用用户确认的恢复基线。
