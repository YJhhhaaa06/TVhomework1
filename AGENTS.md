- 本项目为个人后端学习项目
- 本项目禁止使用spring,spring boot,mybatis，因为学校不允许
- 在重构项目、或对项目架构进行更改后，需要更新CURRENT_ARCHETECTURE.md
- 更改业务流程后，需要更新BUSINESS_FLOW.md
- 写脚本时，必须使用Python语言，如果需要使用其它类型的脚本，必须向人类用户申请并说明理由
- 文档可能有滞后性，对架构和业务流程的总结供参考用，以实际代码为准
- 可用工具清单：  .docs目录下的AVAILABLE_TOOLS.md



下面的仅为建议

- 测试统一由主会话执行 `python tools\run_tests_report.py all`（完整输出落盘、stdout 只回显摘要，详见 .docs/TEST_AUTOMATION.md 第九节）；不派 subagent 跑测试，除非用户明确要求
