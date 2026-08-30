- 本项目为个人后端学习项目
- 本项目禁止使用spring,spring boot,mybatis，因为学校不允许
- 在重构项目、或对项目架构进行更改后，需要更新 .docs/常青/CURRENT_ARCHITECTURE.md
- 更改业务流程后，需要更新 .docs/常青/BUSINESS_FLOW.md
- 写脚本时，必须使用Python语言，如果需要使用其它类型的脚本，必须向人类用户申请并说明理由
- 临时使用的一次性脚本放在 temp_script/ 下，需要长期复用/自动化的脚本才放 tools/，不得乱放
- 改动业务代码时，需同步维护对应的测试脚本与单元测试
- 文档可能有滞后性，对架构和业务流程的总结供参考用，以实际代码为准
- 项目文档唯一入口：.docs/INDEX.md。开始任务前先读 INDEX，按其中"何时读"指引读取文档；.docs/archive/ 为历史存档、.docs/temp/ 为临时文档，除非用户明确要求否则不读
- 可用工具清单：  .docs/说明书/AVAILABLE_TOOLS.md


下面的仅为建议

- 测试统一由主会话执行 `python tools\run_tests_report.py all`（完整输出落盘、stdout 只回显摘要，详见 .docs/说明书/TEST_AUTOMATION.md 第九节）；不派 subagent 跑测试，除非用户明确要求
