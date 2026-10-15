# 生成、检查结构、指令编辑与应用

本说明对应公开命令 `generate / inspect / edit / check / version-plan / version-apply`。源码生成不等于构建或业务验证；这些命令不下载依赖、不启动数据库或Spring Boot。

## 1. 启动CLI

需要Java 21。当前仍没有独立fat JAR，下面使用已有的各模块编译输出启动，不要求写Java调用程序。首次准备编译输出按[测试资源约束](../AGENTS.md)安排，不在这里默认执行全量构建。

Linux示例（修改REPO；ANTLR runtime须已在本地Maven仓库）：

```bash
REPO=/absolute/path/to/Software-IR
M2_REPO="$HOME/.m2/repository"
CP="$M2_REPO/org/antlr/antlr4-runtime/4.13.2/antlr4-runtime-4.13.2.jar"
for module in sir-parser sir-semantic sir-lowering-api sir-lowering-spring-boot \
              sir-generator-spring-boot sir-project-graph sir-change \
              sir-toolchain-application kcg-cli; do
  CP="$CP:$REPO/$module/target/classes"
done
kcg() { java -Xmx384m -cp "$CP" io.kcg.cli.KcgCli "$@"; }
kcg --help
```

该函数只是启动器，不编译、不扫描项目源码、不实现变更规则。也可由已有的Java/Maven启动方式调用同一主类。

## 2. 一次完整流程

假设一个Git工作目录中，`sir/`保存课程SIR，`generated/`尚不存在；候选放在另一目录。源根、生成根在同一仓库内且互不包含，沿用ADR-027。

```bash
WORK=/absolute/path/to/course-workspace
SOURCE="$WORK/sir"
OUTPUT="$WORK/generated"
CANDIDATE=/absolute/path/to/new-course-candidate

kcg generate --source-root "$SOURCE" --entry project.sir --output-root "$OUTPUT"
kcg inspect --source-root "$SOURCE" --entry project.sir > inspection.json
```

`generate`只创建新源码工程，不改SIR、不调用Git。`inspect`只读，运行到正式语义分析，不生成Java；结果包含软件名、**整个源集合**的`baselineSha256`、schema和结构化draft。`draft`可作为命令值模板，不含身份、源位置或派生绑定。schema的`editable`说明机械草稿槽位，实际源编辑范围由`editingScope: DECLARATIONS_ONLY`限制；header、imports等不因此变为可编辑。

以下示例把课程代码长度上限从32改为40，分别改实体与创建输入，仍使用同一种update：

```bash
python3 - <<'PY' > commands.json
import json
with open('inspection.json', encoding='utf-8') as f:
    info = json.load(f)
value = {'type': 'child', 'kind': 'IntegerLiteral',
         'slots': {'value': {'type': 'text', 'value': '40'}}}
commands = []
for declaration in ('Course', 'CreateCourseInput'):
    commands.append({
        'op': 'update',
        'target': {'steps': [
            {'slot': 'declarations', 'name': declaration},
            {'slot': 'fields', 'name': 'code'},
            {'slot': 'constraints', 'name': 'length'},
            {'slot': 'arguments', 'index': 1}]},
        'value': value})
json.dump({'protocolVersion': info['commandProtocolVersion'],
           'protocol': info['protocol'], 'softwareName': info['softwareName'],
           'baselineSha256': info['baselineSha256'], 'commands': commands},
          __import__('sys').stdout, ensure_ascii=False)
PY

kcg edit --source-root "$SOURCE" --entry project.sir \
  --candidate-root "$CANDIDATE" < commands.json

kcg version-plan --repository-root "$WORK" --source-root "$SOURCE" \
  --entry project.sir --output-root "$OUTPUT" --candidate-root "$CANDIDATE" > plan.json

PLAN_ID=$(python3 -c 'import json; print(json.load(open("plan.json"))["planId"])')
kcg version-apply --repository-root "$WORK" --source-root "$SOURCE" \
  --entry project.sir --output-root "$OUTPUT" --candidate-root "$CANDIDATE" \
  --expected-plan-id "$PLAN_ID"
```

先检查各命令的退出码和结果，再运行下一条；`version-plan`返回`NO_CHANGES`时没有planId，也无需应用。示例声明/槽位来自现有多源课程fixture；其他软件以自己的inspect结构为准。字段约束参数在模型里是表达式节点，因此例中用`IntegerLiteral`，不是裸文本`"40"`。

`edit`只保存候选SIR，原SIR和Java工程不动。成功候选可独立重新打开；不满意可不用它。`version-apply`才改变已有工程并通过Git保存前后版本；不会推送。数据库表初始化和演进不在这个流程内。

## 3. 指令JSON合同

外层：`KCG-CLI-COMMANDS-V1`；内部批协议：`sir-command/1`。edit从stdin读**一个**UTF-8 JSON对象，不是check的JSONL。

```json
{
  "protocolVersion": "KCG-CLI-COMMANDS-V1",
  "protocol": "sir-command/1",
  "softwareName": "来自inspect",
  "baselineSha256": "来自inspect的64位小写十六进制摘要",
  "commands": []
}
```

上面是信封形状，实际commands必须非空。协议、softwareName、摘要一起绑定所见的软件和源版本；任何源文件改变后，旧批次均陈旧。三种操作如下：

| op | 必填 | 可选 |
| --- | --- | --- |
| `add` | `container`、`value` | `position` |
| `delete` | `target` | `expectedOld` |
| `update` | `target`、`value` | `expectedOld` |

选择器为`{"steps":[...]}`，每步必填`slot`，可选`name`或`index`，二者互斥。不解析点分字符串，不按相似度猜对象。有名字优先用名字；索引针对前序命令执行后的草稿。批按顺序执行，最终由正式语义分析决定是否合法：可以先增加输入，再增加引用它的能力；删除后仍被引用则整批拒绝。

值只有四种：

```json
{"type":"text","value":"canonical标量或引用名称"}
{"type":"none"}
{"type":"seq","elements":[]}
{"type":"child","kind":"schema中的节点种类","slots":{}}
```

上面各行分别是值形状，并非一个JSONL请求。child必须提供该种类的所有FACT/REFERENCE槽位；OPTIONAL用none，MANY用seq。节点角色由schema分配，不能传id、span、binding或派生type；不能加载任意Java类。新节点请复制inspect中的同类模板或查看schema的slots、valueType、unions、enumValues，再修改 authored 值，不另造业务专用操作。

`expectedOld`若提供，先核验旧值；JSON null不是省略或none。未知/重复字段、缺失必需字段、未知节点/槽位均拒绝。机械形状与最终业务合法性不同：例如现有错误状态码只允许400/404/409，由原语义层检查，不在JSON codec复制该规则。

## 4. 结果、预算与副作用

新三命令结果都是一个固定字段顺序的JSON对象，以换行结束，协议`KCG-CLI-WORKFLOW-V1`。原check/version结果协议不变。

- generate成功：`GENERATED`，源摘要、生成文件路径/大小/SHA-256。
- inspect成功：`INSPECTED`，批协议、基线摘要、schema、draft。
- edit成功：`CANDIDATE_SAVED`，候选根/完整源摘要、semanticChanges及各源声明文本变化。
- edit拒绝：`REFUSED`，原SourceEditor的reason/message/details；失败候选不落盘。读取/发布失败为`FAILURE`并保留Application诊断；读取/inspect阶段的语法和语义诊断保留code及源位置；SourceEditor对编辑结果的诊断沿用原details文本（含原code/文件/行），不解析字符串伪造structured span。

预算：JSON 8 MiB、字符串1 MiB UTF-8、64层（根值算第1层）、100000个值、1–1000条命令、选择器64步；index/position是非负32位整数。源集合沿用128文件、1 MiB/文件、8 MiB合计。候选根必须尚不存在且不与源根重叠。发布采用同卷暂存目录及原子移动；发布失败清理本次暂存和创建的空父目录，不覆盖原目录。

退出码：0成功、2参数/外部JSON错误、3Application失败或编辑拒绝、70意外内部错误。version-apply仍额外使用4表示需要重跑；generate/edit不使用4。程序性失败（70）不同于普通不合法输入。

编辑范围沿用现有SourceEditor：只改声明，新声明加入入口文件；跨文件引用需要已有import。BOM、会丢失注释的重写、header/import编辑明确拒绝。本阶段没有自动移动声明、数据库迁移、构建/启动命令、fat JAR或自然语言解析。
