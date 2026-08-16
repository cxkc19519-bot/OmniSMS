# OmniSMS 项目文档

## 文档目的

本目录保存 OmniSMS 从需求到运维的项目基线。代码实现、测试和部署不得与这些标准相冲突。

## 推荐阅读顺序

1. `01-product-requirements.md`：先理解要解决的问题和验收结果。
2. `02-technical-architecture.md`：了解系统组成、数据流和待验证事项。
3. `03-security-and-privacy.md`：了解短信及凭据的保护要求。
4. `04-ui-ux-specification.md`：了解安卓 App 的页面与交互。
5. `05-development-standards.md`：开始编码前阅读。
6. `06-implementation-plan.md`：按阶段推进工作。
7. `07-testing-and-acceptance.md`：测试与发布前阅读。
8. `08-deployment-and-operations.md`：部署 VPS 和日常维护时阅读。
9. `09-feasibility-validation.md`：查看阶段 0 的验证进度、操作和结论。
10. `10-development-status.md`：查看当前实现、验证结果、缺口和下一步。

## 文档状态

| 文档 | 状态 |
|---|---|
| 产品需求 | 已由用户确认 |
| 技术架构 | 已确认，实施中 |
| 安全与隐私 | 已确认并生效 |
| 界面设计 | 初版规范，开发前可细化原型 |
| 开发规范 | 生效 |
| 实施计划 | 已确认，执行中 |
| 测试与验收 | 初版基线 |
| 部署与运维 | 初版基线 |
| 可行性验证 | 进行中 |
| 开发状态 | 持续更新 |

## 维护规则

- 需求变化必须修改产品需求及受影响文档。
- 文件路径或命令变化时，同步修改 `Codex.md` 和本页。
- 关键决定应包含日期、结论、原因和影响。
- 文档使用 UTF-8 编码和简体中文。
- 示例只能使用虚构号码、邮箱、服务器地址和验证码。
