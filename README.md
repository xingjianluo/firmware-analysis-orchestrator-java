# 固件/二进制分析任务编排服务（本地样本版）

一个不依赖任何外部固件文件的本地分析编排服务：用手工构造的二进制样本模拟真实固件，
覆盖**任务编排并发语义、格式探测与解析容错、多规则匹配与结论汇总**，
并通过单元测试让任务状态与分析结论在并发提交、异常输入、规则变化下都可预期。

## 1. 任务状态流转

状态枚举：`PENDING`、`QUEUED`、`RUNNING`、`COMPLETED`、`FAILED`、
`PARTIAL_FAILED`、`CANCELLED`。

```
PENDING ──> QUEUED ──> RUNNING ──> COMPLETED        （规则全部命中且无冲突/无错误）
                               ├──> FAILED           （样本解析失败，带可区分失败码）
                               └──> PARTIAL_FAILED   （任意一条规则执行失败）
PENDING/QUEUED/RUNNING ──> CANCELLED                 （任意非终态可取消，一次性）
```

约束（由 `TaskRecord` 的内置锁与 `TaskStatus.canTransitionTo` 保证）：

- **状态只进不退**：终态（COMPLETED/FAILED/PARTIAL_FAILED/CANCELLED）拒绝任何写入；
- **重复提交不重复执行**：`TaskStore` 用 `putIfAbsent` 保证同一 `taskId` 只有一个任务实体，
  重复提交返回既有快照（`accepted=false, duplicate=true`）；
- **并发独立**：每个任务独立入队、独立执行、独立终结；
- **取消后不写回结果**：worker 在进入 RUNNING 前和解析后都会检查状态，
  `complete()` 对终态任务一律拒绝写入，已取消任务不会出现解析结果或结论。

## 2. 支持的样本格式与兼容范围

所有样本均为**本地按布局拼出的字节数组**（见测试工具 `SampleFixtures`），
探测以**真实魔数**为准，扩展名只用于标注"扩展名与真实格式不符"。

### FW1（固件描述格式）

布局：`"FW01"`(4 字节魔数) + `version`(2 字节, 大端)
+ `declaredLength`(4 字节, payload 声明长度) + ASCII payload。
payload 为 `key=value;` 序列。

### ARC1（归档容器格式）

布局：`"ARC1"`(4) + `entryCount`(2) + `declaredTotalLength`(4)
+ N 个 entry（`nameLength`(2)+name+`valueLength`(4)+value）。

### 解析失败原因码（互相可区分，且不透出底层异常）

| FailureCode | 含义 |
|---|---|
| `EMPTY_SAMPLE` | 样本为空 |
| `UNSUPPORTED_FORMAT` | 魔数不属于 FW1/ARC1（扩展名不符不算不支持） |
| `TRUNCATED_SAMPLE` | 内容被截断：头部不完整，或实际字节少于声明长度 |
| `HEADER_LENGTH_MISMATCH` | 头部声明长度与实际字节数不符（实际更多） |
| `CORRUPT_PAYLOAD` | 结构能读完但键值对/entry 内容损坏 |

任何解析器内部的非预期 `RuntimeException` 都会在 `SampleParsingService` 被收敛为
`CORRUPT_PAYLOAD` + 可读说明，调用方永远拿不到原始堆栈。

## 3. 规则与结论

规则集（`RuleRegistry`）是**带版本号的不可变快照**，初始为 v1 内置规则：

| 规则 | 分组 | 命中条件 |
|---|---|---|
| `R-FORMAT-KNOWN` | format | 真实格式属于 FW1/ARC1 |
| `R-VERSION-V2` | security | FW1 元数据 `version=2` |
| `R-PAYLOAD-LIMIT` | size | 样本长度 ≤ 64 字节 |
| `R-REQUIRED-DEVICE` | metadata | 元数据含非空 `device` |

结论优先级（固定，保证稳定）：

1. **PARTIAL**：任意规则执行抛异常 → 任务终态为 `PARTIAL_FAILED`，**绝不判为通过**，
   并记录 `errorSource`（异常类型）；
2. **INCONCLUSIVE**：同一冲突分组内同时存在命中与未命中的规则；
3. **FAIL**：至少一条规则未命中（且无冲突、无错误）；
4. **PASS**：全部命中（无规则也算通过）。

每条规则结果都带 `evidence`（判定依据），汇总输出 `reasons` 与 `conflicts`，
结果按 `ruleId` 排序，结论可解释、可复现。

**规则更新与历史复现**：更新规则只会**追加新版本**（v2、v3…），历史版本永不覆盖。
任务完成时固化所用版本与逐条结果；之后即使规则更新，
`POST /api/tasks/{id}/reproduce` 仍用固化版本重新求值并比对，历史结论保持一致。

## 4. HTTP 接口

| 方法与路径 | 说明 |
|---|---|
| `POST /api/tasks` | 提交任务；body：`{taskId, declaredExtension, contentBase64}`。新任务 202，重复提交 200 且 `duplicate=true` |
| `GET /api/tasks/{id}` | 查询任务快照与规则明细 |
| `POST /api/tasks/{id}/cancel` | 取消；终态任务返回但状态不变 |
| `POST /api/tasks/{id}/reproduce` | 用固化规则版本复现结论 |

非法 Base64 / 空 taskId 返回 400（`INVALID_REQUEST`），任务不存在返回 404（`TASK_NOT_FOUND`）。

## 5. 本地验证

前置：JDK 17+（编译目标 21）、Maven 3.6+。

```bash
# 运行全部单测与集成测试（只用内存样本，无需任何外部文件/服务）
mvn test

# 启动服务（随机端口）
mvn spring-boot:run

# 手工提交一个正常 FW1 样本
mvn -q exec:java -Dexec.mainClass=... # 或直接用测试里的 SampleFixtures 生成字节
```

用一段脚本生成可提交的 Base64 样本（与 `SampleFixtures.validFw1()` 相同布局）：

```bash
python3 - <<'PY'
import base64, struct
payload = b"device=router-x1;build=2026;"
body = b"FW01" + struct.pack(">H", 2) + struct.pack(">I", len(payload)) + payload
print(base64.b64encode(body).decode())
PY
```

测试覆盖的核心场景：

- 并发提交 30 条独立任务全部独立完成；20 线程并发重复提交同一 taskId，仅执行 1 次；
- 观察到的状态序列严格不回退；
- 排队阶段确定性取消（阻塞执行线程），已取消任务不写回任何结果；终态不可再取消；
- 截断 / 头部长度不符 / payload 损坏 / 不支持格式 / 空样本五类失败原因两两可区分；
- 规则全部命中 → PASS；规则缺失 → FAIL；同组冲突 → INCONCLUSIVE；
  规则抛异常 → PARTIAL/PARTIAL_FAILED；
- 规则从 v1 更新到 v2 后，历史任务结论不变且可按 v1 复现，新任务按 v2 得出新结论。

单测日志会打印**任务标识、判定依据（evidence/reasons）与错误来源（failureCode/errorSource）**，
可在 `mvn test` 输出中搜索 `[并发独立]`、`[取消不写回]`、`[畸形样本]`、
`[规则冲突]`、`[端到端历史复现]` 等标记核对。
