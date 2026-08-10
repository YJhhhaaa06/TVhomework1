- 本项目为个人后端学习项目
- 本项目禁止使用spring,spring boot,mybatis，因为学校不允许
- 在重构项目、或对项目架构进行更改后，需要更新CURRENT_ARCHETECTURE.md
- 更改业务流程后，需要更新BUSINESS_FLOW.md
- 写脚本时，必须使用Python语言，如果需要使用其它类型的脚本，必须向人类用户申请并说明理由
- 文档可能有滞后性，对架构和业务流程的总结供参考用，以实际代码为准
- 可用工具清单：  .docs目录下的AVAILABLE_TOOLS.md



下面的仅为建议

- 建议在使用测试脚本时，主会话派出subagent执行，理由是测试脚本的输出非常多，会引发上下文膨胀。subagent自己不要再派subagent执行测试了