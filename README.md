# 固件/二进制分析任务编排服务（本地版）

一套完全基于**本地构造样本**运行的固件分析任务编排服务（Spring Boot 3 / Java 21）。
任务支持提交、排队、并发执行、取消与查询；对畸形样本做格式探测与解析容错；
多条分析规则独立匹配并汇总出稳定、可解释的结论。所有外部依赖仅限 JDK / Spring，
不访问网络、不依赖真实固件文件。

---

## 1. 任务状态流转

状态严格**单向流转**，由 `AnalysisTask` 状态机保证不允许回退、终态不可改写：

```
                 解析失败/内部错误
   QUEUED ──► RUNNING ───────────────► FAILED
     │           │  规则执行存在 ERROR ─► PARTIAL_FAILED
     │           │  规则全部跑完 ──────► COMPLETED
     │           │
     └─► CANCELLED ◄─────────────────── (运行中取消，检查点生效)
```

| 状态            | 含义 |
|-----------------|------|
| `QUEUED`        | 已受理入队，等待工作线程 |
| `RUNNING`       | 已抢占执行权（只有 `QUEUED` 能进入，保证不重复执行） |
| `COMPLETED`     | 规则全部跑完，产出最终结论 `PASS/WARN/FAIL/CONFLICT` |
| `PARTIAL_FAILED`| 至少一条规则执行失败，结论固定为 `PARTIAL`，**绝不算通过** |
| `FAILED`        | 样本解析失败或内部错误，带可区分的 `failureCode` |
| `CANCELLED`     | 排队中或运行中被取消；终态后结果写回会被状态机拒绝 |

关键并发不变量：

- **重复提交去重**：相同 `idempotencyKey`（缺省用样本内容 SHA-256）并发提交，
  只会创建并执行一条任务，后续提交仅累加 `submitCount`，不触发重复执行。
- **取消不回写**：`QUEUED` 任务取消后不会被执行；`RUNNING` 任务通过线程中断 +
  显式 `cancelRequested` 标志在检查点（解析后、每条规则前后）生效，已取消任务的
  结果写回被状态机拒绝，最终状态/结论一致可查。
- **规则快照**：任务提交时冻结当时的规则集版本 `ruleSetVersion`，执行与复跑都用该版本快照。

---

## 2. 支持的样本格式与容错范围

格式探测**只看魔数、不看扩展名**：

| 格式   | 魔数 | 解析方式 |
|--------|------|----------|
| FWIMG  | `FWIMG` (0x46 0x57 0x49 0x4D 0x47) | 本地自定义结构化固件，解析出 META/CONFIG/VERSION/BINARY 分段 |
| ELF    | `0x7F 'E' 'L' 'F'` | 抽取可打印字符串（长度 ≥ 4）作为 `STRINGS` 分段 |
| ZIP    | `PK\x03\x04` | 同上，抽取字符串作为 `STRINGS` 分段 |

### FWIMG 布局（小端）

```
偏移  长度  含义
0     5     魔数 "FWIMG"
5     1     版本
6     4     声明总长度 uint32
10    1     签名标记 0/1
11    1     分段数量 N
12    ...   N 个分段: [1 字节类型][4 字节内容长度][内容]
```

分段类型：`1=META 2=CONFIG 3=VERSION 4=BINARY`。

### 可区分的失败原因（`failureCode`）

| code | 触发情形 |
|------|----------|
| `EMPTY_SAMPLE` | 样本字节为空 |
| `UNSUPPORTED_FORMAT` | 魔数无法识别为 FWIMG/ELF/ZIP |
| `EXTENSION_MISMATCH` | 扩展名（.fwimg/.elf/.zip）与真实魔数格式不符 |
| `TRUNCATED_CONTENT` | 内容短于头部，或实际长度短于头部声明长度 |
| `DECLARED_LENGTH_MISMATCH` | 实际长度超出头部声明长度（尾部多余数据） |
| `CORRUPT_SAMPLE` | 分段越界、非法分段类型等结构损坏 |
| `TASK_NOT_FOUND` | 查询/取消/复跑不存在的任务 |
| `INTERNAL_ERROR` | 服务内部错误的兜底码 |

所有底层异常（`IndexOutOfBoundsException` 等）都在解析层被收敛为
`MalformedSampleException` + 上述稳定错误码，**不会把底层异常类名/堆栈透出给调用方**。

---

## 3. 分析规则与结论汇总

内置规则（不可变对象，版本号随内容递增）：

| 规则 | 主体 subject | 判定 |
|------|--------------|------|
| `R001` | `CREDENTIALS` | 任意分段命中 `password=<非空>` → FAIL，否则 PASS |
| `R002` | `VERSION` | 存在非空 VERSION 分段 → PASS，否则 FAIL |
| `R003` | `BACKDOOR` | 出现 `BACKDOOR` 标记 → FAIL，否则 PASS |
| `R004` | `INTEGRITY` | `signed=1` → PASS；缺失或 `signed=0` → FAIL |
| `R005` | `DEBUG` | `debug=true` → WARN，否则 PASS |

汇总结论的**稳定优先级**（`FindingAggregator`）：

1. 任一条规则 `ERROR` → **PARTIAL**（部分失败）；
2. 同一 `subject` 下同时出现 FAIL 与 PASS → **CONFLICT**（规则互相冲突，可解释、稳定）；
3. 否则按 **FAIL > WARN > PASS** 汇总。

每条 `RuleFinding` 都带 `ruleId / ruleVersion / subject / verdict / basis / errorSource`，
作为结论的判定依据，可在任务查询结果中逐条查看。

### 规则更新与历史复现

规则通过 `InMemoryRuleRegistry#replaceAll` 整体替换，替换后规则集版本单调递增，
旧版本快照保留。任务在**提交时冻结版本**，`POST /api/tasks/{id}/replay` 用该旧快照复跑，
返回 `ReplayReport`（含 `originalConclusion / replayConclusion / reproducible`）。
规则更新只影响新任务，历史结论保持可复现、不被改写。

---

## 4. REST 接口

| 方法 & 路径 | 说明 |
|-------------|------|
| `POST /api/tasks` | 提交任务，请求体 `{"fileName","contentBase64","idempotencyKey"}`，返回 202 + 任务视图 |
| `GET /api/tasks` | 列出全部任务 |
| `GET /api/tasks/{id}` | 查询单任务（状态/结论/失败码/每条规则依据） |
| `POST /api/tasks/{id}/cancel` | 取消排队中或运行中的任务 |
| `POST /api/tasks/{id}/replay` | 用提交时规则快照复跑，校验结论可复现 |

任务视图主要字段：`taskId, status, conclusion, failureCode, failureMessage,
ruleSetVersion, submitCount, deduplicated, findings[]`。

并发度可用 `analysis.worker.count`（默认 2）调整。

### curl 示例

```bash
# 启动（随机端口见日志；可在 application.properties 固定 server.port）
mvn spring-boot:run

# 用测试辅助生成样本较繁琐，可直接运行单测观察链路；下列为接口形态
curl -s -X POST localhost:8080/api/tasks -H 'Content-Type: application/json' \
  -d '{"fileName":"dev.fwimg","contentBase64":"<BASE64>","idempotencyKey":"k1"}'
curl -s localhost:8080/api/tasks/<taskId>
curl -s -X POST localhost:8080/api/tasks/<taskId>/cancel
curl -s -X POST localhost:8080/api/tasks/<taskId>/replay
```

---

## 5. 本地验证

```bash
mvn test            # 运行全部单测，仅使用本地构造样本，无需任何外部文件/网络
```

测试日志会打印**任务标识、判定依据、错误来源**（关键字段：`taskId`、`判定依据=`、`errorSource=`）。

| 测试类 | 覆盖场景 |
|--------|----------|
| `DefaultSampleParserTest` | 合法解析；空样本/未知格式/扩展名不符/截断/声明长度不符/分段损坏；ELF/ZIP 字符串抽取；底层异常不透出 |
| `RuleEngineTest` | 全 PASS、命中 FAIL、同主体 PASS+FAIL → CONFLICT、规则异常 → PARTIAL、后门/缺版本判定 |
| `TaskServiceTest` | 16 线程并发同样本只执行一次、6 条不同样本独立、排队取消不执行、运行中取消状态一致、终态不回退、规则异常部分失败、规则更新后历史结论复现、三类畸形失败原因可区分 |
| `TaskControllerIntegrationTest` | HTTP 提交/查询/去重/畸形错误码/任务不存在 的端到端冒烟 |

本地样本由测试工具 `Samples` 手工构造（`cleanFirmware / riskyFirmware /
backdoorFirmware / truncated / declaredLengthMismatch / corruptSegment / elfWithText / ...`）。

### 手工构造一个干净 FWIMG（用于试验）

魔数 `FWIMG` + 版本(1) + 总长度(u32) + 签名标记(1) + 分段数 +
分段(`类型 u8 | 长度 u32 | UTF-8 内容`)；详见 `Samples#fwimg`。
