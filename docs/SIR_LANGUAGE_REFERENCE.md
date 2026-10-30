# SIR 语言参考（`sir 0.3`）

这份文档写给**用 SIR 写软件的人**（包括让模型写 SIR 的人）。它回答“能写什么、怎么写、写错了会怎样”，不讲编译器内部。想知道为什么这样设计，看[设计 02](design/02-sir-language-and-modules.md) 和[设计 10](design/10-semantic-boundary-and-mechanisms.md)；想知道命令行怎么用，看 [CLI 说明](CLI_WORKFLOW.md)。

**文中的示例都是被检查过的。** 代码块有三种：

- ` ```sir `：一份完整的文档，必须能通过 `kcg check`（解析、语义、降级、生成全部通过）；
- ` ```sir-declarations `：只有 `declarations { ... }` 里面的内容，测试把它放进一个标准外壳里，同样必须通过；
- ` ```sir-error SIR-XXX-NNN `：故意写错的声明，必须被拒绝，并且诊断里必须有这个码。

检查它们的测试是 `LanguageReferenceExamplesTest`；这份文档和语言不一致时，测试会失败，所以以示例为准。

## 1. 一个最小的软件

一个 SIR 文档描述一个**软件**：它有什么数据（实体）、对外露出什么（视图、输入）、能做什么（能力）。下面是一个只能按编号查一本书的软件：

```sir
sir 0.3

software Library {
  metadata {
    displayName "Library";
    namespace "example.library";
  }
  target {
    language java 21;
    framework spring_boot;
    persistence mybatis_plus;
    database mysql;
    build maven;
    interface rest;
  }
  sources { }
  imports { }
  declarations {
    entity Book persistent {
      identity id: Id generated auto;
      field title: Name where length(1, 200);
    }
    view BookView from Book {
      field id: Id;
      field title: Text;
    }
    input GetBookInput {
      field id: Id;
    }
    error BookNotFound http 404;
    capability GetBook {
      input GetBookInput;
      output BookView;
      fails BookNotFound;
      requires readonly;
      expose query;
      workflow {
        load Book by input.id as book else BookNotFound;
        return book;
      }
    }
  }
}
```

**文档的骨架是固定的：** 版本行 `sir 0.3`、`software 名字 { ... }`，里面依次是 `metadata`、`target`、`sources`、`imports`、`declarations`。

- `metadata`：`displayName`（给人看的名字）和 `namespace`（生成的 Java 包名，必须是合法的 Java 包名）。
- `target`：目前只接受这一组值（Java 21、Spring Boot、MyBatis-Plus、MySQL、Maven、REST），写别的会被拒绝（`SIR-LOWER-TARGET-001`）。
- `sources` 和 `imports`：多文件工程才用，见第 8 节；单文件写空的 `{ }`。
- 注释：`// 到行尾` 和 `/* 块注释 */`。`kcg edit` 遇到会丢失注释的改写时会拒绝这次编辑（见 [CLI 说明](CLI_WORKFLOW.md)），所以要被工具改写的声明里最好不要放注释。
- 字符串用双引号，不能跨行。

**保留字。** 下面这些词在语言里有固定含义，不能当实体、字段、变量、错误或能力的名字，写了是语法错误（`SIR-PARSE-001`）：

`sir software metadata displayName namespace target language framework persistence database build interface declarations enum entity persistent identity generated auto uuid field versioned present where input patch of view from order ascending descending Page containsLiteral error http capability actor output fails requires authenticated atomic readonly expose command query workflow validate else load by as find item any create update persist return Optional List Ref true false and or not now unit`

最容易撞上的是 `item`、`order`、`error`、`input`、`target`、`query`、`command`、`build`、`from`、`field`、`present`。另一类词只在特定位置才有含义，别的地方仍然可以当名字：`sources source imports import unique secret credential subject expires when matches is none`，以及时间单位 `second(s) minute(s) hour(s) day(s)`。

```sir-error SIR-PARSE-001
entity Booking persistent {
  identity id: Id generated auto;
  field order: Count;
}
```

## 2. 类型

| 类型 | 含义 | 自带的规则 | 底层 |
| --- | --- | --- | --- |
| `Id` | 记录的编号 | | 64 位整数 |
| `Serial` | 不做主键的长编号、大数 | | 64 位整数 |
| `Version` | 并发版本号 | 等于 `versioned` | 64 位整数 |
| `Count` | 个数、次数 | | 32 位整数 |
| `Quantity` | 至少为 1 的数量 | `min(1)` | 32 位整数 |
| `PageNo`、`PageSize` | 页码、页大小 | | 32 位整数 |
| `Money` | 金额、十进制小数 | `precision(19, 2)` | 十进制 |
| `Text` | 普通文本 | | 文本 |
| `Name` | 名称 | `notBlank`、`length(1, 100)` | 文本 |
| `Code` | 短编码 | `notBlank`、`length(1, 32)` | 文本 |
| `Remark` | 备注、说明 | `length(0, 500)` | 文本 |
| `Email` | 邮箱 | `email` | 文本 |
| `Secret` | 口令（只存摘要） | 等于 `secret` | 文本 |
| `Token` | 不可猜测的标识、令牌 | | UUID |
| `Moment` | 时刻 | | `Instant`（UTC） |
| `Day` | 日期 | | `LocalDate` |
| `Flag` | 是／否 | | 布尔 |
| `Unit` | 没有值 | 能力没有返回内容时 |
| `Optional<T>` | 可以没有 | 不能嵌套 `Optional<Optional<T>>`（`SIR-TYPE-003`） |
| `List<T>` | 列表 | 能力输出写 `List<Ref<实体>>`（不分页的 `find` 的结果） |
| `Page<T>` | 分页结果 | 只能做 `expose query` 的输出 |
| `Ref<实体>` | 指向另一个实体的一行 | 存成那一行的主键 |

可选文本（如 `Optional<Name>`、`Optional<Code>`）在请求里不传或传 `null` 都合法；一旦有值，仍要满足类型自带的规则（非空白、长度等），空串和纯空白照样被拒绝。非可选的 `Name`、`Code` 不接受 `null`。局部更新（PATCH）区分“没传”和“显式传 `null`”：没传保留原值，传 `null` 是清空，只有可空的字段能清空；合并后的结果仍按目标字段的规则检查。

写字段时只写语义类型，不写机器类型；底层用什么（Java 类型、数据库列）由目标决定。类型自带的规则不用再写，字段上写同名规则时以字段上的为准：`Name where length(1, 64)` 是最长 64 个字符的名称，仍然非空白。同一种底层的类型之间可以互相比较和运算（`Count` 和 `Quantity` 都是 32 位整数），视图字段可以用另一个同底层的类型投影实体字段。

自己声明的 `enum`、`entity`、`input`、`view` 也是类型，直接写名字（不能与上面的类型重名）。各种类型都用一遍：

```sir-declarations
entity Event persistent {
  identity id: Id generated auto;
  field title: Text where notBlank;
  field headcount: Count where min(0);
  field budget: Money;
  field open: Flag;
  field day: Day;
  field startsAt: Moment;
  field note: Optional<Text>;
}
view EventView from Event {
  field id: Id;
  field title: Text;
  field budget: Money;
  field day: Day;
  field startsAt: Moment;
}
capability OpenEvents {
  output List<Ref<Event>>;
  requires readonly;
  expose query;
  workflow {
    find Event where item.open == true as events;
    return events;
  }
}
capability Ping {
  output Unit;
  requires readonly;
  expose query;
  workflow {
    return unit;
  }
}
```

**整数字面量的宽度。** 字面量默认是 32 位整数；和 64 位整数（`Id`、`Serial`、`Version`）比较或作同型加减、乘法时，字面量按 64 位理解（`item.qty > 0` 里 `qty` 是 `Serial` 也能写）。但 32 位的字段不会自动变成 64 位。字面量必须放得进它的类型，`5000000000` 单独用在 32 位整数上会被拒绝：

```sir-error SIR-TYPE-001
entity Gauge persistent {
  identity id: Id generated auto;
  field level: Count;
}
input LevelInput {
  field level: Count;
}
error TooHigh;
capability CheckLevel {
  input LevelInput;
  output Unit;
  fails TooHigh;
  requires readonly;
  expose query;
  workflow {
    validate input.level < 5000000000 else TooHigh;
    return unit;
  }
}
```

**金额精度。** `Money` 默认是 `precision(19, 2)`：最多 17 位整数、2 位小数，不隐含非负，也不带币种。`precision(p, s)` 的 `p` 是总位数、`s` 是小数位数，必须写整数常量，`p >= 1`、`0 <= s <= p`，只能用于十进制值。和别的规则一样，可以在类型或字段上改写：

```sir-declarations
type UnitPrice = Money where precision(19, 4), min(0);
entity PriceTag persistent {
  identity id: Id generated auto;
  field amount: Money default 0.0;
  field unitPrice: UnitPrice;
  field fraction: Money where precision(2, 2);
}
```

判断依据是“能否精确表示”，不做舍入：默认的 `Money` 接受 `1.23` 和 `1.2300`（HTTP 输入也接受等值的指数写法），拒绝 `1.234`；`precision(2, 2)` 接受 `0` 和 `0.99`，不接受 `1`。请求校验和写入前的检查用同一条规则；算出来的值写进字段时，也按目标字段的精度检查，不交给数据库去舍入。旧版本的 `Money` 没有精度上限，现在收紧了；以前按“分”存成整数的数据不会自动转换。

MySQL 上交付为 `DECIMAL(p, s)`，并要求 `p <= 65`、`s <= 30`。已上线字段的精度改变不会自动迁移。局部更新的精度以目标实体的字段为准，不用输入类型的默认精度。

### 2.1 自己的语义类型（`type`）

一个业务里反复出现的“某种值”可以起个名字，以已有的语义类型为底：

```sir-declarations
type Percent = Count where min(0), max(100);
type ShortName = Name where length(1, 20);
type Pin = Secret;
entity Account persistent {
  identity id: Id generated auto;
  field rate: Percent;
  field label: ShortName;
  field pin: Pin;
  field tight: Percent where max(10);
}
```

新类型继承底类型的底层值、规则和标记（`Pin` 和 `Secret` 一样只存摘要）；自己写的规则替换底类型的同名规则（`ShortName` 仍然非空白，最长 20）。字段上还可以再写规则，以字段上的为准。`type` 在整个工程里都可见，不用 import；底类型可以是另一个 `type`，但不能成环，名字不能和语言自带的类型重名（`SIR-TYPE-005`）：

```sir-error SIR-TYPE-005
type Money = Count;
```

## 3. 声明

### 3.1 枚举

```sir-declarations
enum Status { OPEN, CLOSED }
entity Ticket persistent {
  identity id: Id generated auto;
  field status: Status;
}
```

用的时候写 `Status.OPEN`。

### 3.2 实体（`entity`）

实体是会被存进数据库的东西。**每个实体必须有且只有一个 `identity`**，写在最前面：

```
identity 名字: 类型 generated auto;    // 类型是 Id，数据库自增
identity 名字: 类型 generated uuid;    // 类型是 Token，由应用生成
```

然后是若干 `field`。字段后面可以接修饰，**顺序固定**：约束（`where ...`）、`versioned`、`unique`、`secret`、`default ...`、`@id("...")`。最后可以有 `credential ...;`（见下）和 `scope ...;`（见下）。实体成员的整体顺序固定为 `identity`、`field`、`unique (...)`、`credential`、`scope`，写错位置时报错会说明这个顺序和该挪到哪里。

```sir-declarations
entity Account persistent {
  identity id: Id generated auto;
  field name: Name where length(1, 64) unique;
  field email: Optional<Email>;
  field age: Count where min(0), max(150);
  field passwordHash: Secret;
  field version: Version;
}
```

**约束**（`where` 后面，逗号分隔）：`notBlank`（非空白，文本）、`email`（邮箱格式，文本）、`timeZone`（时区名，文本，无参数）、`length(最小, 最大)`（文本）、`min(值)`、`max(值)`（数字）、`precision(总位数, 小数位)`（十进制）。同一个约束不能重复，`min` 不能大于 `max`。

`timeZone` 接受 `UTC` 和运行时 JDK 认识的地区时区名（带斜线，如 `Asia/Shanghai`）；不接受 `CST` 这类缩写、`+08:00`／`UTC+08:00` 这类偏移、拼错的名字和前后空格。合法名字以运行时的 JDK 为准，不在生成时固定。它不管 `null`：要求必填就用非可选类型，或再加 `notBlank`。

**`versioned`**：这个整数字段是并发版本号，每个实体最多一个。带版本号的实体，更新时要用 `persist ... else 错误`（见 5.5）。

**`unique`**：声明这个字段的值在所有行里不重复，交付包建表时给它建唯一索引。有了它，才能 `load 实体 by 字段: 值`（见 5.2）。只能用在文本、整数（32 位或 64 位）和 `Token` 上，不能和 `versioned` 同用。

**联合唯一：`unique (字段, 字段, ...);`**写在实体的字段之后、`credential` 和 `scope` 之前，说明这几个字段合起来在所有行里不重复（一人只能选一门课、一人只能点一次赞、一间房一个时段）。可以写多条。字段必须是这个实体自己的，至少两个，写的顺序就是索引的顺序；不能是可选的（数据库里空值之间不算重复，约束会悄悄失效）、`secret`、`versioned`、主键，不限长的文本要先用 `length` 限定，文本字段的最大长度加起来不超过 768。违反这些是 `SIR-VALID-014`。新建的行撞上它时，用 `persist 变量 else 错误名;` 报声明的错误（见 5.5）：

```sir-declarations
entity Student persistent {
  identity id: Id generated auto;
  field name: Name;
}
entity Course persistent {
  identity id: Id generated auto;
  field code: Code;
}
entity Enrollment persistent {
  identity id: Id generated auto;
  field student: Ref<Student>;
  field course: Ref<Course>;
  unique (student, course);
}
view EnrollmentView from Enrollment {
  field id: Id;
}
input EnrollInput {
  field studentId: Id;
  field courseId: Id;
}
error AlreadyEnrolled http 409;
error NoCourse http 404;
error NoStudent http 404;
capability Enroll {
  input EnrollInput;
  output EnrollmentView;
  fails AlreadyEnrolled;
  fails NoCourse;
  fails NoStudent;
  requires atomic;
  expose command;
  workflow {
    load Student by input.studentId as student else NoStudent;
    load Course by input.courseId as course else NoCourse;
    create Enrollment as made {
      student: student;
      course: course;
    }
    persist made else AlreadyEnrolled;
    return made;
  }
}
```

**`secret`**：这个文本字段（通常直接写 `Secret` 类型）存的是**摘要**，不是原文。它只能被写入，读取只有一种方式——`stored matches candidate`（见第 6 节）。不能同时 `unique`、`versioned`、带约束；不能出现在 `view` 里。例子见 7.1“登录”。

**`default 值`**：`create` 没有写这个字段时取的值，可以是常量（`default 0`、`default "open"`、`default false`）、枚举成员（`default Status.OPEN`）或创建时刻（`default now()`）。写了 `default` 的字段在 `create` 里可以不写，写了就以写的为准。常量和枚举成员也会成为数据库列的默认值，所以给已上线的表加一个带默认值的必填字段时，老数据取这个默认值（`now()` 和不限长的 `Text` 没有数据库默认值）；已上线的字段改默认值时，只改列的默认值，已有的行保持原值。`unique`、`secret`、`versioned` 字段不能有默认值（`SIR-VALID-010`），默认值的类型要和字段一致（`SIR-TYPE-001`）。

```sir-declarations
enum TicketState { OPEN, CLOSED }
entity Ticket persistent {
  identity id: Id generated auto;
  field title: Text;
  field state: TicketState default TicketState.OPEN;
  field urgent: Flag default false;
  field openedAt: Moment default now();
}
input TicketDraft {
  field title: Text;
}
capability OpenTicket {
  input TicketDraft;
  output Unit;
  requires atomic;
  expose command;
  workflow {
    create Ticket as made { title: input.title; }
    persist made;
    return unit;
  }
}
```

**`@id("...")`**：给字段一个稳定的名字，改字段名时身份不变（已上线的表里，这一列跟着改名并保留数据）。普通写作不需要。

#### 默认查询范围（`scope`）

`scope 条件;` 写在实体最后，说明哪些行对软件来说“存在”。这个实体的每个 `load`、`find`、`exists`、`count`、`sum` 和 `any(...)` 都只看满足条件的行：`find` 自动加上这个条件，`load` 到范围外的行时按 `load` 自己的错误失败。条件里直接写本实体的字段名（和 `any` 的条件一样）。典型用法是软删除：

```sir-declarations
entity Note persistent {
  identity id: Id generated auto;
  field text: Text;
  field removed: Flag default false;
  scope removed == false;
}
input NoteRef {
  field id: Id;
}
error NoteMissing http 404;
capability RemoveNote {
  input NoteRef;
  output Unit;
  fails NoteMissing;
  requires atomic;
  expose command;
  workflow {
    load Note by input.id as row else NoteMissing;
    update row { removed: true; }
    persist row;
    return unit;
  }
}
```

条件必须是布尔值，只能说这一行自己的事：不能用 `any(...)` 或 `.present`（`SIR-VALID-011`）。

#### 凭据实体（`credential`）

把一个实体声明成“它的行就是持有者令牌”，写在实体的最后：

```sir-declarations
entity User persistent {
  identity id: Id generated auto;
  field name: Text unique;
}
entity AccessToken persistent {
  identity id: Token generated uuid;
  field owner: Ref<User>;
  field expiresAt: Moment;
  credential subject owner expires expiresAt;
}
```

含义：请求头 `Authorization: Bearer <令牌>` 里的令牌就是 `AccessToken` 的主键；找到这一行并且 `expiresAt` 晚于现在，请求就以 `owner` 指向的 `User` 的身份运行（见 4.2 的 `actor`）。规则：主键必须是 `Token`（否则 `SIR-TYPE-001`）；`subject` 必须是 `Ref<实体>` 字段；`expires` 必须是 `Moment` 字段；同一个主体只能有一种凭据实体（`SIR-VALID-009`）；一个软件里，“要求已认证”的能力要么全由凭据认证，要么全不是。作废令牌不需要新语法：把 `expiresAt` 更新成 `now()`。

### 3.3 输入（`input`）

输入描述一个能力接收的请求内容。输入字段有名字、类型和可选约束；视图可以直接投影或显式计算（见第 3.4 节），不能自行添加约束。`unique`、`secret`、`@id` 是实体字段才有的修饰，写在输入或视图里是语法错误。

```sir-declarations
input RegisterInput {
  field name: Name where length(1, 64);
  field note: Optional<Text>;
}
```

**普通输入的必填规则。**不是 `Optional` 的字段都必填：请求没传或传 `null`，在执行工作流之前返回 `400 INVALID_REQUEST`，`fields` 给出请求里的字段名（引用字段是 `名字Id`）和错误码 `NotNull`。这条规则按声明判断，不看工作流怎样使用这个字段：只做比较、直接存库、甚至没有读到的字段也一样必填；不想让客户端必须传，就声明成 `Optional<T>`，或拆成另一个输入。带 `notBlank` 的文本（包括 `Name`、`Code`）缺值时报 `NotBlank`。唯一的例外是分页：一个能力里只被分页步骤用作页码、页大小的字段，没传时取默认值（见第 5.3 节）；同一个输入被另一个不分页的能力使用时，这两个字段在那个能力里照样必填。局部更新输入按下面的规则处理。

**局部更新输入**（`patch of 实体`）：客户端只传想改的字段，没传的不动。它必须声明实体的主键字段（恰好一次）和至少一个想让客户端改的字段；字段只写名字和类型，**约束以实体上的为准**，不能再写；不能包含 `versioned` 字段（版本号由框架处理）；这个实体必须有 `versioned` 字段；用它的能力必须是 `expose command` 并且对这个实体有写步骤。违反这些是 `SIR-VALID-003`：

```sir-declarations
entity Course persistent {
  identity id: Id generated auto;
  field name: Text where notBlank;
  field capacity: Quantity;
  field version: Version;
}
input UpdateCourseInput patch of Course {
  field id: Id;
  field name: Text;
  field capacity: Count;
}
view CourseView from Course {
  field id: Id;
  field name: Text;
  field capacity: Count;
  field version: Serial;
}
error CourseNotFound http 404;
error EmptyChange;
error StaleVersion http 409;
capability UpdateCourse {
  input UpdateCourseInput;
  output CourseView;
  fails CourseNotFound;
  fails EmptyChange;
  fails StaleVersion;
  requires atomic;
  expose command;
  workflow {
    validate input.name.present or input.capacity.present else EmptyChange;
    load Course by input.id as course else CourseNotFound;
    update course {
      name: input.name;
      capacity: input.capacity;
    }
    persist course else StaleVersion;
    return course;
  }
}
```

`input.字段.present` 问“客户端有没有传这个字段”。局部更新的每个字段都必须在 `update` 里绑定。

### 3.4 视图（`view`）

视图描述返回给客户端的内容，是某个实体的**投影**。普通字段 `field 同名字段: 类型;` 读取实体的同名字段，两者的底层类型要一致。写成 `field 输出名: 类型 = 表达式;` 就是**计算字段**，值由表达式算出，实体上不必有这个字段。两种字段都**不能带约束**，也不能读 `secret` 字段。

计算字段里的 `item` 是当前这一行。能用的有：这一行的字段和主键、枚举值、加减乘、比较和 `and`／`or`／`not`、空值判断、按固定时长平移时间。字段类型必须和表达式的类型完全一致，包括是否 `Optional`，不会自动补零或拆掉 `Optional`。不能读 `input`、`actor` 或别的视图字段，不能调用 `now()`，不能查询，也不能顺着引用读另一行（读引用自己的 `.id` 可以，不会多查一次）。计算字段之间不能互相引用。

```sir-declarations
entity Course persistent {
  identity id: Id generated auto;
  field capacity: Quantity;
  field enrolled: Count default 0;
  field note: Optional<Text>;
}
view CourseView from Course {
  field id: Id;
  field remaining: Count = item.capacity - item.enrolled;
  field full: Flag = item.enrolled >= item.capacity;
  field note: Optional<Text> = item.note;
  field missingNote: Flag = item.note is none;
}
```

计算在组装响应时进行，直接返回、分页和嵌套视图都一样，不增加数据库列、迁移或 SQL。用到的字段不必出现在响应里。整数溢出会报错，不会回绕；金额计算保持精确值，不按 `Money` 的存储精度舍入。`where` 和 `order by` 只能用实体字段，不能用计算字段的名字。聚合、金额合计和时间差仍然写不出来。

**跨行计数。**视图字段可以写成 `field 数量: Serial = count(子实体, 关联条件);`，统计子行中符合条件的有多少行。被数的实体和条件里读的字段都要能找到（多文件时同样需要 import）；条件里必须用子实体的一个 `Ref` 字段与 `item` 比较，这就是关联，写别的形式会被拒绝。结果是 64 位整数，0 行就是 0，不会变成空值。计数只能出现在视图字段的完整取值里（以及这样的视图作为分页结果）：实体 `scope`、输入字段、工作流步骤、`where`／`order by` 里都不行。

```sir-declarations
enum CopyStatus { AVAILABLE, BORROWED, LOST }
entity Book persistent {
  identity id: Id generated auto;
  field title: Name;
}
entity Copy persistent {
  identity id: Id generated auto;
  field book: Ref<Book>;
  field status: CopyStatus default CopyStatus.AVAILABLE;
}
view CatalogueBook from Book {
  field id: Id;
  field title: Name;
  field availableCopies: Serial =
    count(Copy, book == item and status == CopyStatus.AVAILABLE);
}
use Crud {
  entity: Book;
  path: books;
  readView: CatalogueBook;
}
input SearchBooks { field keyword: Text; field page: PageNo; field size: PageSize; }
error BadPage;
capability SearchByTitle {
  input SearchBooks;
  output Page<CatalogueBook>;
  fails BadPage;
  requires readonly;
  expose query at "/catalogue";
  workflow {
    find Book where item.title containsLiteral input.keyword
      order by title ascending, id ascending
      Page input.page, input.size else BadPage as rows;
    return rows;
  }
}
```

计数的读法是**先读根行，再对当前页的子行做一次分组统计**：一个分页请求不会每行再查一次，空页一行子查询都不发。同一个请求内根行和计数看同一个只读快照（`REPEATABLE_READ`），不同请求之间会随写入变化。因此这个字段是“现在书架上几本”，**不是借得到的保证**：真正能否借出仍由借书路径的名额、副本状态和预约顺序决定。子实体自身的 `scope` 照常生效（软删除的副本不会被数进去）。

一个视图字段还可以是关联实体的视图（嵌套）：

```sir-declarations
entity Author persistent {
  identity id: Id generated auto;
  field name: Text;
}
entity Book persistent {
  identity id: Id generated auto;
  field title: Text;
  field author: Ref<Author>;
}
view AuthorSummary from Author {
  field id: Id;
  field name: Text;
}
view BookView from Book {
  field id: Id;
  field title: Text;
  field author: AuthorSummary;
}
```

嵌套的视图有两种：指向一个（上面的 `author`，本实体持有 `Ref`），和列出一批（`field reviews: List<ReviewView>`，子实体持有指向本实体的 `Ref`，必须只有一个）。它们可以出现在两个地方：分页 `find` 的结果里，以及 `load` 或 `create` 之后 `return` 的那一行里。两处都是每个嵌套多一条批量查询，不会逐行查询；列出一批时读出全部子行，不分页，按子行主键升序，所以子行可能很多的关系应当另写一个分页的 `find`。不分页的 `find` 结果带嵌套视图会被拒绝（`SIR-LOWER-FEATURE-001`）。

```sir-declarations
entity Author persistent {
  identity id: Id generated auto;
  field name: Text;
}
entity Book persistent {
  identity id: Id generated auto;
  field title: Text;
  field author: Ref<Author>;
}
entity Review persistent {
  identity id: Id generated auto;
  field book: Ref<Book>;
  field text: Text;
}
view AuthorSummary from Author {
  field id: Id;
  field name: Text;
}
view ReviewView from Review {
  field id: Id;
  field text: Text;
}
view BookDetail from Book {
  field id: Id;
  field title: Text;
  field author: AuthorSummary;
  field reviews: List<ReviewView>;
}
input BookRef {
  field id: Id;
}
error BookNotFound http 404;
capability GetBook {
  input BookRef;
  output BookDetail;
  fails BookNotFound;
  requires readonly;
  expose query;
  workflow {
    load Book by input.id as book else BookNotFound;
    return book;
  }
}
```

### 3.5 错误（`error`）

```
error 名字;               // 状态码默认 400
error 名字 http 404;      // 指定状态码
```

状态码只允许 **400、401、403、404、409**。401 是“没登录或凭据无效”，客户端会去重新登录；已登录但无权做这件事用 403（见 4.2）。错误的名字就是客户端看到的错误码（响应体 `{"code":"名字","message":"名字","fields":[]}`）。

### 3.6 能力（`capability`）

能力是软件对外提供的一件事，对应一个 HTTP 接口。第 4 节详述。

### 3.7 给实体加字段（`extend`）

`extend 实体 { 字段... }` 给一个在别处声明的实体加字段，写法和实体里的字段一样。加进去的字段和实体自己的字段完全相同：可以被视图投影、被 `create` 绑定、建进同一张表。它主要给功能用（功能借它带上自己需要的字段），作者也可以用它把一个实体的字段分开写：

```sir-declarations
entity Account persistent {
  identity id: Id generated auto;
  field name: Name;
}
extend Account {
  field visits: Count default 0;
  field lastSeen: Optional<Moment>;
}
view AccountView from Account {
  field id: Id;
  field visits: Count;
}
```

目标必须是实体（`SIR-EXTEND-001`），在同一个文件里或已经 import；加的字段不能和已有字段重名（`SIR-SYMBOL-001`）。一个实体可以被 extend 多次。

### 3.8 功能（`use` 与 `feature`）

一个功能是一组声明的名字：`use 功能 { 参数: 值; }` 在编译时展开成它代表的声明，展开出来的东西和手写的一样被检查、生成、部署，但不写进源码，编辑时也只改 `use` 这一处。语言自带 `DualTokenLogin`（双 Token 登录，见 7.2）、`Crud`（增删改查，见 7.3）和 `AccountRegistration`（注册，见 7.1 末尾）。

自己的功能用 `feature` 定义。参数有四种：`name`（一个名字，默认值可以是空的 `""`）、`duration`（一段时长，如 `7 days`）、`text`（一段文字，如 `"my/posts"`，也可以只写一个名字）、`names`（名字列表，如 `[status, createdAt]`，空列表写 `[]`），都可以有默认值。`names` 只用来挑选字段（见下面的 `only`／`except`），不能直接拼进模板。模板是三个反引号括起来的 SIR 声明，用 `${参数}` 引用其它参数：

```sir-declarations
entity Note persistent {
  identity id: Id generated auto;
  field text: Text;
}
feature Tagging {
  parameter owner: name;
  parameter prefix: name = "";
  template ```
    entity ${prefix}Tag persistent {
      identity id: Id generated auto;
      field owner: Ref<${owner}>;
      field label: Name;
    }
    extend ${owner} {
      field tagCount: Count default 0;
    }
  ```;
}
use Tagging {
  owner: Note;
}
```

参数和模板在定义时就检查（参数重复、默认值不合种类、模板里用了没声明的参数都是 `SIR-FEATURE-006`）；`use` 时检查实参（不认识的功能 `SIR-FEATURE-001`，缺参数或多参数 `SIR-FEATURE-002`，值不合种类或范围 `SIR-FEATURE-003`）；展开后的声明按普通规则检查，错误报在 `use` 这一行。

一个功能可以 `use` 多次，只要没有默认值的参数取值不同：`use Crud { entity: Note; }` 和 `use Crud { entity: Tag; }` 是两次不同的使用；对同一组取值用两次是 `SIR-FEATURE-005`。

字段列表参数的用法例如 `parameter hidden: names = [];`，再在 `${for f in entity.fields except secret, hidden}` 里使用。列表里的名字必须是该实体的字段（包括 `extend` 加上的），重复或不存在的名字会指出具体是哪一项。改名时，作为参数的实体、字段和枚举成员会跟着改；路径文字、模式值和别的实体的同名字段不受影响。原来省略的默认参数，改名后会在需要时写成显式参数。

#### 按实体的字段展开

`name` 参数指向一个实体时，模板可以逐个走它的字段，也可以按它是否带版本号选择不同的文本，这样一个功能就能用于任何实体：

```sir-declarations
entity Note persistent {
  identity id: Id generated auto;
  field title: Name;
  field body: Optional<Remark>;
  field pin: Secret;
}
feature Summary {
  parameter entity: name;
  template ```
    view ${entity}Summary from ${entity} {
      field ${entity.identity.name}: ${entity.identity.type};
  ${for f in entity.fields except secret}
      field ${f.name}: ${f.type};
  ${end}
    }
  ```;
}
use Summary {
  entity: Note;
}
```

写法：

- `${for f in 参数.fields}` … `${end}`：对实体的每个字段重复中间的文本，字段顺序是实体里写的顺序，`extend` 加的字段（包括别的功能加的）排在后面。后面可以跟 `except secret, versioned, defaulted`，跳过口令字段、版本号字段、有默认值的字段。
- 循环里：`${f.name}` 是字段名，`${f.type}` 是实体里写的类型（如 `Optional<Remark>`、`Ref<Owner>`），`${f.rules}` 是字段上写的规则（如 ` where length(1, 8)`，没有就是空），`${f.base}` 是去掉语义类型规则后的类型（`Name` 写成 `Text`，用在不允许带规则的地方，比如 `patch` 输入）。
- `${参数.identity.name}`、`${参数.identity.type}`：实体主键的名字和类型。
- `${if 参数.versioned}` … `${else}` … `${end}`：实体有版本号字段时取前一段，否则取后一段（`${else}` 可省略；`not 参数.versioned` 反过来）。
- `${if 参数}`：这次 `use` 给了参数值（不是空的 `""`）时才取中间的文本。
- 参数可以指向实体的一个字段：循环写 `only 参数` 只走这个字段，`except` 后面写参数名则跳过这个字段（如 `except secret, owner`）。参数为空时 `only` 一个字段也不走，`except` 不跳过任何字段；参数不是实体的字段时，`use` 处报 `SIR-FEATURE-003`。
- 只有一个 `${for}`／`${if}`／`${else}`／`${end}` 的行，整行不进入展开结果。

循环或条件写错（走的不是 `name` 参数、字段没有这个属性、少了 `${end}`）在定义处报 `SIR-FEATURE-006`；`use` 时参数指向的不是实体报 `SIR-FEATURE-003`。这类功能在其它功能展开之后再展开（所以能看到别的功能用 `extend` 加的字段），也因此不能声明 `type`（`SIR-FEATURE-004`）。

## 4. 能力

```sir-declarations
entity Note persistent {
  identity id: Id generated auto;
  field text: Text;
}
view NoteView from Note {
  field id: Id;
  field text: Text;
}
input NoteInput {
  field text: Text where notBlank;
}
capability PostNote {
  input NoteInput;
  output NoteView;
  requires atomic;
  expose command;
  workflow {
    create Note as note {
      text: input.text;
    }
    persist note;
    return note;
  }
}
```

能力里的子句**顺序固定**：`actor`（可选）→ `input`（可选）→ `output` → 若干 `fails` → 若干 `requires` → `expose` → `workflow`。

### 4.1 `input`、`output`、`fails`

- `input 输入名;` 声明请求内容；没有就不写。
- `output 类型;` 声明返回内容：视图、`Ref<实体>`、`List<Ref<实体>>`、`Page<视图>`、`Unit`。
- `fails 错误名;` 列出这个能力**可能**报的错误。工作流里用到的每个错误都必须在这里声明，**并且 `fails` 要按错误名字升序排**（`SIR-VALID-001`）：

```sir-error SIR-VALID-001
entity Item persistent {
  identity id: Id generated auto;
  field name: Text;
}
view ItemView from Item {
  field id: Id;
  field name: Text;
}
input ItemRef {
  field id: Id;
}
error ItemGone http 404;
error AccessDenied http 401;
capability GetItem {
  input ItemRef;
  output ItemView;
  fails ItemGone;
  fails AccessDenied;
  requires readonly;
  expose query;
  workflow {
    load Item by input.id as found else ItemGone;
    return found;
  }
}
```

（`AccessDenied` 排在 `ItemGone` 前面才对。）

### 4.2 `actor` 与 `requires`

`requires` 有三种，**必须按这个顺序写**：`authenticated`、`atomic`、`readonly`。

- `requires atomic;`：这个能力的所有写入在一个事务里。**有两个及以上写步骤（create／update／persist）就必须写它**。
- `requires readonly;`：只读。`expose query` 必须配 `readonly`；`atomic` 和 `readonly` 不能同时写；只读能力里不能有 `create`、`update`、`persist`（`SIR-FLOW-004`）。
- `requires authenticated;`：要求请求已认证。它要配一个 `actor Ref<实体>;`，工作流里用 `actor` 表示发起请求的那一行。

```sir-declarations
entity User persistent {
  identity id: Id generated auto;
  field name: Text;
}
entity Goods persistent {
  identity id: Id generated auto;
  field title: Text;
  field seller: Ref<User>;
}
view GoodsView from Goods {
  field id: Id;
  field title: Text;
}
input PublishInput {
  field title: Text where notBlank;
}
capability PublishGoods {
  actor Ref<User>;
  input PublishInput;
  output GoodsView;
  requires authenticated;
  requires atomic;
  expose command;
  workflow {
    create Goods as goods {
      title: input.title;
      seller: actor;
    }
    persist goods;
    return goods;
  }
}
```

认证怎么来：如果软件里有 `credential` 实体（3.2），由它认证；否则运行时要有部署方提供的认证器，或本地调试的固定身份。没有身份的请求得到 `401`，不会走到工作流。

#### 授权：谁可以做这件事

授权就是工作流里的普通检查，比较的一边是 `actor`。只允许本人修改自己的商品，别人改得到 `403`：

```sir-declarations
entity User persistent {
  identity id: Id generated auto;
  field name: Text;
}
entity Goods persistent {
  identity id: Id generated auto;
  field title: Text;
  field seller: Ref<User>;
}
view GoodsView from Goods {
  field id: Id;
  field title: Text;
}
input RenameInput {
  field id: Id;
  field title: Text where notBlank;
}
error GoodsNotFound http 404;
error NotSeller http 403;
capability RenameGoods {
  actor Ref<User>;
  input RenameInput;
  output GoodsView;
  fails GoodsNotFound;
  fails NotSeller;
  requires authenticated;
  requires atomic;
  expose command;
  workflow {
    load Goods by input.id as goods else GoodsNotFound;
    validate goods.seller == actor else NotSeller;
    update goods {
      title: input.title;
    }
    persist goods;
    return goods;
  }
}
```

只看自己的数据，把条件写进 `find`：`find Goods where item.seller == actor ...`。

按角色限制，直接读当前用户的字段：

```sir-declarations
enum Role {
  MEMBER,
  ADMIN
}
entity User persistent {
  identity id: Id generated auto;
  field name: Text;
  field role: Role default Role.MEMBER;
}
entity Goods persistent {
  identity id: Id generated auto;
  field title: Text;
}
input GoodsRef {
  field id: Id;
}
error Forbidden http 403;
error GoodsNotFound http 404;
capability RemoveGoods {
  actor Ref<User>;
  input GoodsRef;
  output Unit;
  fails Forbidden;
  fails GoodsNotFound;
  requires authenticated;
  requires atomic;
  expose command;
  workflow {
    validate actor.role == Role.ADMIN else Forbidden;
    load Goods by input.id as goods else GoodsNotFound;
    delete goods;
    return unit;
  }
}
```

读 `actor` 主键以外的字段时，生成的代码先按主键读出当前用户这一行（每次请求一次）；这一行不存在时请求按未认证处理（401），不用为它声明错误。只把 `actor` 当引用用（`seller: actor`、`goods.seller == actor`）不会多读这一行。

### 4.3 `expose` 与 HTTP

- `expose query;` → `GET /api/<能力名的短横线形式>`，输入字段作为查询参数。
- `expose command;` → `POST`（输入是局部更新时是 `PATCH`），输入是 JSON 请求体。
- 路由是能力名转短横线：`PublishGoods` → `/api/publish-goods`。
- 成功的状态码：创建了新行是 `201`，其它是 `200`。

也可以自己定方法和路径，路径前自动加 `/api`：

```
expose query at "/notes/{id}";              // GET    /api/notes/{id}
expose command at "/notes";                 // POST   /api/notes
expose command put at "/notes/{id}";        // PUT    /api/notes/{id}
expose command delete at "/notes/{id}";     // DELETE /api/notes/{id}
```

路径里的 `{名字}` 是输入的一个必填字段，取自 URL；其余字段 GET、DELETE 从查询参数取，POST、PUT、PATCH 从 JSON 请求体取（请求体里同名的值被路径上的值覆盖，字段规则在合并后检查）。规则（都是 `SIR-VALID-013`）：

- 路径以 `/` 开头，每段是小写字母、数字和短横线，或 `{输入字段}`；
- 路径字段必须是必填的数字、文本或 uuid 字段，同一个字段只能出现一次；
- `put`、`delete` 只能用于 `command`；输入是局部更新（`patch`）时方法固定是 `PATCH`，路径里只能放它的主键；
- 两个能力的方法和路径不能相同，`/notes/{id}` 和 `/notes/{noteId}` 也算相同（不写路径的能力按它的名字算）。

```sir-declarations
entity Note persistent {
  identity id: Id generated auto;
  field body: Text;
}
view NoteView from Note {
  field id: Id;
  field body: Text;
}
input NoteRef {
  field id: Id;
}
input EditNoteInput {
  field id: Id;
  field body: Text where notBlank;
}
error NoteNotFound http 404;
capability ShowNote {
  input NoteRef;
  output NoteView;
  fails NoteNotFound;
  requires readonly;
  expose query at "/notes/{id}";
  workflow {
    load Note by input.id as note else NoteNotFound;
    return note;
  }
}
capability EditNote {
  input EditNoteInput;
  output NoteView;
  fails NoteNotFound;
  requires atomic;
  expose command put at "/notes/{id}";
  workflow {
    load Note by input.id as note else NoteNotFound;
    update note {
      body: input.body;
    }
    persist note;
    return note;
  }
}
capability RemoveNote {
  input NoteRef;
  output Unit;
  fails NoteNotFound;
  requires atomic;
  expose command delete at "/notes/{id}";
  workflow {
    load Note by input.id as note else NoteNotFound;
    delete note;
    return unit;
  }
}
```

## 5. 工作流（`workflow`）

工作流是一串**步骤**，按顺序执行，**以且只以一个 `return` 结束**（`SIR-FLOW-002`）。变量由 `load`、`find`、`exists`、`count`、`sum`、`create` 的 `as 名字` 引入，之后才能用（`SIR-FLOW-001`）。

### 5.1 `validate`

```
validate 条件 else 错误名;
```

条件为假就报这个错误并停止。条件是布尔表达式（第 6 节）。

### 5.2 `load`

按主键取一行，取不到就报错：

```
load 实体 by 主键值 as 变量 else 错误名;
```

后面要做跨行检查、并且不能让并发请求同时通过时，可以先锁住一行共同的父行：

```text
load Room by input.roomId as room for update else RoomNotFound;
```

`for update` 只能用在 `requires atomic` 的 command 里，而且只能按主键加载；不能用于只读能力、query、非原子能力，也不能和 `by 字段:` 一起用。行不存在时仍报声明的错误，实体的 `scope` 也照样生效。锁一直持有到整个事务结束，之后不要求修改或 `persist` 这一行。

例子见[图书借阅样例](../sir-toolchain-application/src/test/resources/topics/library/project.sir)：借书和还书都先锁住读者，再用带守卫的计数 `activeLoans < 5` 占用名额；第一次归还减一，重复归还在减之前就被拒绝。加锁顺序固定为读者 → 借阅记录 → 副本；副本冲突时抛错，整个事务回滚。注意 `activeLoans` 是作者自己维护的计数，不是 `count` 自动算的：**所有改变借阅状态的写法都必须照这个约定更新它**。给已上线的系统加这一列时，要先按实际未归还的借阅记录回填，不能直接从默认值 0 开始。

在 Spring Boot／MySQL 目标上，`for update` 生成按主键的 `SELECT … FOR UPDATE`。含 `for update` 的能力使用 `READ_COMMITTED` 隔离级别，这样锁住父行之后的查询能看到别的事务刚提交的行；生成工程的 MyBatis 本地缓存设为 `STATEMENT`，事务里重复的相同查询每次都真的去查。没写 `for update` 的能力不受影响，带版本号实体原有的加锁方式也不变。

行锁不会自动识别业务上的冲突：**所有可能产生冲突记录的写法，都必须先锁同一行父行，再检查、再写入**。比如房间预约用半开区间 `[startsAt, endsAt)`，重叠条件是 `item.startsAt < input.endsAt and item.endsAt > input.startsAt`，首尾相接不算冲突。只写 `exists` 再插入，或者只声明 `requires atomic`，都防不住并发下的重叠。编译器对这个约定只做提示：工作流里先 `exists`／`count` 检查、再 `create` 同一个实体，检查条件里用到了某个加载的行，却没有在检查前 `for update` 锁住任何行，被插入的实体也没有声明 `unique`，就会给出警告 `SIR-WARN-001`（不阻止编译）；约定里“所有写法都要先锁父行”和“计数要由所有写法一起维护”没法在编译期证明，仍要作者自己保证。要锁多行父行时，作者自己保持统一的加锁顺序。死锁或等锁超时按执行失败处理，不会自动重试，也不会转成 4xx。

也可以按**声明了 `unique` 的字段**取：

```
load 实体 by 字段名: 值 as 变量 else 错误名;
```

```sir-declarations
entity Account persistent {
  identity id: Id generated auto;
  field name: Text unique;
}
view AccountView from Account {
  field id: Id;
  field name: Text;
}
input ByName {
  field name: Text;
}
error AccountNotFound http 404;
capability FindAccount {
  input ByName;
  output AccountView;
  fails AccountNotFound;
  requires readonly;
  expose query;
  workflow {
    load Account by name: input.name as account else AccountNotFound;
    return account;
  }
}
```

命中就返回这一行，未命中报声明的错误；如果数据库里违反了 `unique`、同一个值有两行，**请求以 500 失败，不会悄悄取其中一行**。对主键本身不要写字段名（`SIR-VALID-006`）。带 `versioned` 的实体按字段加载后再更新会被拒绝（`SIR-VALID-006`），因为更新要锁行，而锁是按主键取的：

```sir-error SIR-VALID-006
entity Course persistent {
  identity id: Id generated auto;
  field code: Text unique;
  field name: Text;
  field version: Version;
}
view CourseView from Course {
  field id: Id;
}
input RenameCourse patch of Course {
  field id: Id;
  field name: Text;
}
error NoCourse http 404;
error Stale http 409;
capability Rename {
  input RenameCourse;
  output CourseView;
  fails NoCourse;
  fails Stale;
  requires atomic;
  expose command;
  workflow {
    load Course by code: input.name as course else NoCourse;
    update course {
      name: input.name;
    }
    persist course else Stale;
    return course;
  }
}
```


**顺着引用加载。**引用字段里存的就是被引用那一行的主键，所以键的位置可以直接写引用，也可以写 `.id`，两者等价：

```sir-declarations
entity Item persistent {
  identity id: Id generated auto;
  field title: Text;
}
entity Line persistent {
  identity id: Id generated auto;
  field product: Ref<Item>;
}
view ItemView from Item {
  field id: Id;
  field title: Text;
}
input LineRef {
  field id: Id;
}
error Missing http 404;
capability ItemOfLine {
  input LineRef;
  output ItemView;
  fails Missing;
  requires readonly;
  expose query;
  workflow {
    load Line by input.id as line else Missing;
    load Item by line.product as found else Missing;
    return found;
  }
}
```

引用必须指向被加载的这个实体，否则是类型不一致（`SIR-TYPE-001`）。

### 5.3 `find`

按条件取多行：

```
find 实体 where 谓词 [order by 字段 [ascending|descending], ...] [Page 页码, 页大小 else 错误] as 变量;
```

谓词里用 `item` 表示正在被检查的那一行。**比较的左边必须是 `item.字段`，右边必须是不读取 `item` 的值**（`SIR-VALID-001`）；对列做加减乘或时间平移也不行。

```sir-declarations
entity Ticket persistent {
  identity id: Id generated auto;
  field title: Text;
  field priority: Count;
  field expiresAt: Moment;
}
view TicketView from Ticket {
  field id: Id;
  field title: Text;
}
input TicketQuery {
  field minPriority: Count;
  field keyword: Text;
  field page: PageNo;
  field size: PageSize;
}
error BadPage;
capability LiveTickets {
  input TicketQuery;
  output Page<TicketView>;
  fails BadPage;
  requires readonly;
  expose query;
  workflow {
    find Ticket
      where item.priority >= input.minPriority
        and item.title containsLiteral input.keyword
        and item.expiresAt > now() + 5 minutes
      order by priority descending, id ascending
      Page input.page, input.size else BadPage
    as live;
    return live;
  }
}
```

不带 `Page` 的 `find` 得到 `List<Ref<实体>>`，带 `Page` 的得到 `Page<视图>`。`containsLiteral` 是**字面包含**（不是模式匹配，`%` 之类按字面处理）。输出是 `Page<视图>` 时必须带 `Page ...` 子句，且能力必须是 `expose query`。`where true` 取所有行（实体有 `scope` 时仍只取范围内的行）。排序字段要能比较大小；`Token` 主键也可以排序，它没有有意义的大小，但顺序是稳定的，分页需要这一点。

对错的写法（比较方向反了）：

```sir-error SIR-VALID-001
entity Ticket persistent {
  identity id: Id generated auto;
  field priority: Count;
}
view TicketView from Ticket {
  field id: Id;
}
input Limit {
  field floor: Count;
  field page: PageNo;
  field size: PageSize;
}
error BadPage;
capability Wrong {
  input Limit;
  output Page<TicketView>;
  fails BadPage;
  requires readonly;
  expose query;
  workflow {
    find Ticket where input.floor < item.priority Page input.page, input.size else BadPage as found;
    return found;
  }
}
```

对列做加减乘也不行，要把运算挪到右边：

```sir-error SIR-VALID-001
entity Ticket persistent {
  identity id: Id generated auto;
  field priority: Count;
}
view TicketView from Ticket {
  field id: Id;
}
input Limit {
  field floor: Count;
  field page: PageNo;
  field size: PageSize;
}
error BadPage;
capability Wrong {
  input Limit;
  output Page<TicketView>;
  fails BadPage;
  requires readonly;
  expose query;
  workflow {
    find Ticket where item.priority + 1 > input.floor Page input.page, input.size else BadPage as found;
    return found;
  }
}
```

**可选值与可选筛选。** `Optional<T>` 可以和同底层类型的值比较；任意一侧为空，`== != < <= > >=` 都是 false，两个空值也不相等。不会自动省略筛选条件。请求没传就不限制，明确写 `input.kind is none or item.kind == input.kind`：

```sir-declarations
entity Ticket persistent {
  identity id: Id generated auto;
  field kind: Optional<Code>;
}
view TicketView from Ticket {
  field id: Id;
}
input Filter {
  field kind: Optional<Code>;
  field page: PageNo;
  field size: PageSize;
}
error BadPage;
capability FilterTickets {
  input Filter;
  output Page<TicketView>;
  fails BadPage;
  requires readonly;
  expose query;
  workflow {
    find Ticket where input.kind is none or item.kind == input.kind
      Page input.page, input.size else BadPage as found;
    return found;
  }
}
```

**关联存在性：`any(实体, 条件)`。** 在查询步骤的谓词里问“有没有另一个实体的行指向我，并满足条件”：

```sir-declarations
entity Course persistent {
  identity id: Id generated auto;
  field code: Text;
}
entity Enrollment persistent {
  identity id: Id generated auto;
  field course: Ref<Course>;
  field active: Flag;
}
view CourseView from Course {
  field id: Id;
  field code: Text;
}
input PageInput {
  field onlyActive: Flag;
  field page: PageNo;
  field size: PageSize;
}
error InvalidPage;
capability ActiveCourses {
  input PageInput;
  output Page<CourseView>;
  fails InvalidPage;
  requires readonly;
  expose query;
  workflow {
    find Course
      where any(Enrollment, course == item and active == input.onlyActive)
      order by code ascending
      Page input.page, input.size else InvalidPage
    as courses;
    return courses;
  }
}
```

`any` 只能出现在查询步骤的条件里，不能嵌套。条件最外层的 `and` 里必须有一个把两张表连起来的比较，两个方向都可以：`被查实体的引用字段 == item`（另一张表的行指向当前行），或 `被查实体的主键 == item.引用字段`（当前行指向另一张表的行），等号两边可以互换。例如查 `Entry` 时，`any(Category, id == item.category and owner == actor)` 表示“这条账目的分类属于当前用户”。没有这个连接条件，或者随意比较两张表的列，都会被拒绝。

**裸名字先指关联实体的字段或主键，而不是外层同名变量。**因此 `tag == tag` 的两侧都是关联行的 tag；即使外层加载了变量 tag，也没有按它筛选。同一个关联字段与自己做 `==` 或 `!=` 比较会报 `SIR-VALID-001`，括号、`not` 和逻辑组合不能绕开；应把外层变量改名为 wanted，写 `tag == wanted`。只存在同名变量并不报错，`reader == actor` 仍合法；关联行与外层行读取同一字段声明，也不等于同一行的字段自比较。判空用 `is none/is present`，不要用字段自比较代替。

```sir-error SIR-VALID-001
entity Course persistent { identity id: Id generated auto; }
entity Enrollment persistent {
  identity id: Id generated auto;
  field course: Ref<Course>;
  field active: Flag;
}
view CourseView from Course { field id: Id; }
error InvalidPage;
capability ActiveCourses {
  output Page<CourseView>;
  fails InvalidPage;
  requires readonly;
  expose query;
  workflow {
    find Course where any(Enrollment, course == item and active == active)
      Page 1, 10 else InvalidPage as courses;
    return courses;
  }
}
```

### 5.4 `create`

```
create 实体 as 变量 { 字段: 值; ... }
```

必须给出所有**必填**字段：除了主键（系统生成）、`versioned` 字段（框架初始化）、`Optional<...>` 字段和带 `default` 的字段之外的每一个，漏了报 `SIR-FLOW-001`。不能绑定主键或 `versioned` 字段，同一个字段也不能绑两次。创建后要 `persist` 才会写入。

新建的行在 `persist` 之前**还没有主键**：自增主键由数据库在插入时给出，`generated uuid` 的主键也是在插入时才赋值。所以 `persist` 之前不能读它的主键，否则读到的是空值，要等请求真正写库时才失败。编译器检查这一点（`SIR-FLOW-007`），三种读法都会报错：读 `变量.主键`（例如把 `access.id` 抄进另一行）；把这一行当作引用的值（例如 `basket: basket`），因为引用存的就是它的主键；`return` 这一行时，输出的视图里含有主键。改法都是把 `persist 变量;` 挪到读主键之前。其他字段的值在 `create` 时就定了，`persist` 之前可以照常读（例如 `access.expiresAt`）；视图里不含主键时，也可以直接返回没存下的行。

```
create AccessToken as access { owner: holder; expiresAt: now() + 15 minutes; }
persist access;
create TokenPair as pair { accessToken: access.id; accessExpiresAt: access.expiresAt; }
persist pair;
```

### 5.5 `update` 与 `persist`

```
update 变量 { 字段: 值; ... }
persist 变量;
```

`update` 只改内存里的这一行，`persist` 才写入。`update` 不能改主键，也不能改 `versioned` 字段（框架负责递增）。

对**带 `versioned` 的实体**：更新它的能力必须用这个实体的局部更新输入（`patch of`，见 3.3），更新后的 `persist` 必须写 `else 错误名`，在别人先改过时报这个错误；对没有 `versioned` 的实体，`else` 只有下面说的一种用法。违反这几条都是 `SIR-VALID-004`。

**新建的行撞上已有的行。**实体里有 `unique` 字段或联合唯一约束时，新建的行可能和已经存在的行重复（比如重复注册同一个名字）。`persist 变量 else 错误名;` 说明这时报哪个已声明的错误；不写 `else`，数据库的异常会原样变成 500。这个 `else` 只对 `create` 出来的行、且实体有唯一约束时才能写（`SIR-VALID-004`）。

**如果在写入前已对同一实体执行过 `exists`，新行的 `persist` 必须写 `else`**（`SIR-VALID-004`），可沿用前面 `validate ... else` 拒绝重复的错误；它仍须列入 `fails`。先查重不能代替并发撞重处理，`atomic` 或父行锁不豁免这条显式错误处理要求。检查按步骤顺序进行，覆盖每个新行写入和循环体；循环内的查重不视为循环后必定执行过。仅有 `count`、写入之后才 `exists`、或没有此前 `exists` 的唯一新建，不在这条强制规则内；普通已加载行的更新也不受它影响。

```sir-declarations
entity Account persistent {
  identity id: Id generated auto;
  field name: Name unique;
}
view AccountView from Account {
  field id: Id;
  field name: Name;
}
input NewAccount {
  field name: Name;
}
error NameTaken http 409;
capability Register {
  input NewAccount;
  output AccountView;
  fails NameTaken;
  requires atomic;
  expose command;
  workflow {
    exists Account where item.name == input.name as used;
    validate not used else NameTaken;
    create Account as made {
      name: input.name;
    }
    persist made else NameTaken;
    return made;
  }
}
```

只保留前面的查重、不处理唯一撞重会被拒绝：

```sir-error SIR-VALID-004
entity Account persistent {
  identity id: Id generated auto;
  field name: Name unique;
}
view AccountView from Account { field id: Id; }
input NewAccount { field name: Name; }
error NameTaken http 409;
capability Register {
  input NewAccount;
  output AccountView;
  fails NameTaken;
  requires atomic;
  expose command;
  workflow {
    exists Account where item.name == input.name as used;
    validate not used else NameTaken;
    create Account as made { name: input.name; }
    persist made;
    return made;
  }
}
```

### 5.6 守卫更新：`update 变量 when 条件 else 错误 { ... }`

“只在这一行**写入时**仍然满足条件才写，否则报错”，用来在并发下只让一个请求成功，例如刷新令牌只能用一次：

```sir-declarations
entity User persistent {
  identity id: Id generated auto;
  field name: Text;
}
entity RefreshToken persistent {
  identity id: Token generated uuid;
  field owner: Ref<User>;
  field used: Flag;
  field expiresAt: Moment;
}
view RefreshView from RefreshToken {
  field id: Token;
  field expiresAt: Moment;
}
input RefreshInput {
  field token: Token;
}
error Expired;
error Invalid http 409;
capability Rotate {
  input RefreshInput;
  output RefreshView;
  fails Expired;
  fails Invalid;
  requires atomic;
  expose command;
  workflow {
    load RefreshToken by input.token as old else Invalid;
    validate old.expiresAt > now() else Expired;
    update old when old.used == false and old.expiresAt > now() else Invalid { used: true; }
    create RefreshToken as fresh {
      owner: old.owner;
      used: false;
      expiresAt: now() + 7 days;
    }
    persist old;
    persist fresh;
    return fresh;
  }
}
```

规则：守卫是布尔表达式，由“目标字段与值的比较”和空值判断（目标的可选字段，或不读取目标的可选值）用 `and`／`or`／`not` 组合，比较的右边可以是值，也可以恰好是目标的另一个字段（`course.enrolled < course.capacity`，由数据库拿两列比）；目标必须是 `load` 出来的行；实体不能带 `versioned`；错误要在 `fails` 里声明；绑定的值**不能读目标自己的字段**（守卫只钉住它判断的那些字段，其它字段在内存里是旧的，读它们再写回会丢失更新），可以读 `input`、常量、`now()` 和别的变量，例外有两个：下一节的“本字段加减一个值”，以及目标是用 `load … for update` 锁住的行（锁一直持有到事务结束，读到的就是存着的值，所以可以读它自己的字段）（`SIR-VALID-007`）；带守卫的 `update` 后面要有对同一行的普通 `persist`，这一行只能有这一个 `update`。

守卫在**一条 SQL 里**检查并写入，所以并发下只有一个请求成功。它依赖数据库驱动把“匹配的行数”当影响行数（MySQL Connector/J 的默认行为）；如果部署时在连接串上加了 `useAffectedRows=true`，“设成和原来一样的值”会被当成没有匹配而报守卫的错误。

#### 计数：`字段: 行.字段 + 值`

库存、座位、点赞数这类“在原来的数上加减”的字段，直接写成**本字段加或减一个值**：

```sir-declarations
entity Course persistent {
  identity id: Id generated auto;
  field name: Name;
  field capacity: Quantity;
  field enrolled: Count default 0;
}
view CourseView from Course {
  field id: Id;
  field name: Name;
  field capacity: Quantity;
  field enrolled: Count;
}
input CourseRef {
  field id: Id;
}
error CourseNotFound http 404;
error CourseFull http 409;
capability Take {
  input CourseRef;
  output CourseView;
  fails CourseFull;
  fails CourseNotFound;
  requires atomic;
  expose command at "/courses/{id}/take";
  workflow {
    load Course by input.id as course else CourseNotFound;
    update course when course.enrolled < course.capacity else CourseFull {
      enrolled: course.enrolled + 1;
    }
    persist course;
    return course;
  }
}
```

这样的绑定写成 `SET enrolled = enrolled + ?`，加减由数据库在存着的值上做，所以并发的请求都会被数过，不会各自用读到的旧值覆盖对方；上面的守卫把“还有名额”也放进同一条语句，名额只剩一个时只有一个请求成功。

规则（`SIR-VALID-007`）：形式只有 `字段: 变量.同一个字段 + 值` 和 `字段: 变量.同一个字段 - 值`，值不能读这一行；字段必须是整数（`Count`、`Quantity`、`Serial` 等）或带精度的 `Money`；行必须是 `load` 出来的，或者是本次新建、并且在这一步之前已经 `persist` 过的（还没存下的行没有值可加）；实体不能带 `versioned`；这一行只能有这一个 `update`（它整个写成一条语句，只写绑定的那些列）。不带守卫也可以，行不在了会以服务器错误结束。写入后会把这一列重新读回来，所以返回的是存着的值，不是读到的旧值加一。

一个常见的搭配是“新建一行并给被引用的行计数”（选课：新建选课记录，课程的已选人数加一）。**先 `persist` 被计数的行，再 `persist` 新建的行**：在 MySQL（InnoDB）里，插入引用了某一行的记录会先给被引用的行加读锁，两个请求若各自拿着读锁再去改同一行，会互相等待而死锁；先改被计数的行，后到的请求在这里排队，就不会。**编译器检查这一点**（`SIR-VALID-015`）：一个新行引用了某一行，这一行又被 `update`，并且它不是用 `load … for update` 加载的，那么新行的 `persist` 必须排在这一行的 `persist` 之后，否则报错；用 `for update` 在加载处就锁住它，两种顺序都安全。

下面的写法会被拒绝：选课记录先 `persist`，课程的人数后 `persist`，而课程没有加锁。

```sir-error SIR-VALID-015
entity Course persistent {
  identity id: Id generated auto;
  field capacity: Count;
  field enrolled: Count default 0;
}
entity Enrollment persistent {
  identity id: Id generated auto;
  field course: Ref<Course>;
  field student: Id;
  unique (course, student);
}
view EnrollmentView from Enrollment {
  field id: Id;
}
input TakeInput {
  field courseId: Id;
  field student: Id;
}
error CourseFull http 409;
error CourseMissing http 404;
error Taken http 409;
capability Take {
  input TakeInput;
  output EnrollmentView;
  fails CourseFull;
  fails CourseMissing;
  fails Taken;
  requires atomic;
  expose command;
  workflow {
    load Course by input.courseId as course else CourseMissing;
    update course when course.enrolled < course.capacity else CourseFull {
      enrolled: course.enrolled + 1;
    }
    create Enrollment as made {
      course: course;
      student: input.student;
    }
    persist made else Taken;
    persist course;
    return made;
  }
}
```


没有守卫、又不是上面这种形式的“读自己的字段再写回”（例如 `a: row.b + 1`）仍然合法，但那是先读、再改、再写，并发下会丢更新；要并发安全就按这一节写。原子写只支持本字段加减一个值，不支持乘；加减的值可以用乘法算出来，但不能读这一行。金额的加减值必须能精确表示到字段的小数位。执行原子写的数据库连接必须开着严格模式（`STRICT_TRANS_TABLES` 或 `STRICT_ALL_TABLES`），否则拒绝写入，不会去改服务器的全局设置。金额加到超出范围属于数据库执行失败，整个事务回滚，不会变成作者声明的守卫错误；“余额不能为负”这类规则仍要自己写守卫。

### 5.7 `delete`

```
delete 变量;
```

删除 `load` 读出来的这一行。只能删 `load` 出来的变量或 `each` 正在处理的行（不能删刚 `create` 的），删除后不能再 `update`、`persist` 或再次 `delete` 它（`SIR-VALID-012`）；只读能力里不能有（`SIR-FLOW-004`）。

```sir-declarations
entity Memo persistent {
  identity id: Id generated auto;
  field text: Text;
}
input MemoRef {
  field id: Id;
}
error MemoMissing http 404;
capability RemoveMemo {
  input MemoRef;
  output Unit;
  fails MemoMissing;
  requires atomic;
  expose command;
  workflow {
    load Memo by input.id as row else MemoMissing;
    delete row;
    return unit;
  }
}
```

### 5.8 `return`

```
return 变量;
```

返回的类型必须和 `output` 一致（`SIR-TYPE-001`）。没有 `return` 或有多个都不行。

### 5.9 `exists` 与 `count`：只读一个布尔值或数量

```text
exists 实体 where 条件 as 是否存在;
count 实体 where 条件 as 数量;
```

两者都是工作流里的独立步骤，执行到这一步才查数据库，结果变量后面可以反复用。`item` 只在这一步的条件里有效。`exists` 的结果是 Boolean，没有匹配的行时为 false；`count` 的结果是 64 位整数，没有匹配时为 0。不分页，也不会把行全读回来再数。实体的 `scope` 和可选值的空值规则照样生效。不能加 `Page`、排序或视图。`exists`、`count` 只在这个位置是关键字，别处仍可以当名字。

```sir-declarations
entity Ticket persistent {
  identity id: Id generated auto;
  field priority: Count;
}
input CheckTickets {
  field minimum: Count;
  field expected: Serial;
  field hasMatches: Flag;
}
error Mismatch;
capability CheckMatchingTickets {
  input CheckTickets;
  output Unit;
  fails Mismatch;
  requires readonly;
  expose query at "/tickets/check";
  workflow {
    exists Ticket where item.priority >= input.minimum as anyMatch;
    count Ticket where item.priority >= input.minimum as total;
    validate anyMatch == input.hasMatches and total == input.expected else Mismatch;
    return unit;
  }
}
```

每个步骤是一条 SQL：`exists` 只取主键并带 `LIMIT 1`，`count` 用 `COUNT(*)`。条件里用到 `actor` 的其它字段时，`actor` 那一行还是会单独读一次（见 4.2）。

数量不是布尔值：

```sir-error SIR-TYPE-001
entity Ticket persistent { identity id: Id generated auto; }
error Mismatch;
capability WrongCount {
  output Unit;
  fails Mismatch;
  requires readonly;
  expose query;
  workflow {
    count Ticket where true as total;
    validate total else Mismatch;
    return unit;
  }
}
```

**不能防并发。**先 `count < 5` 或先确认“不存在”再插入，两个请求仍可能同时通过；`requires atomic` 也不会锁住查询的范围。硬性的数量上限用行内原子计数加守卫（5.6），防止时段重叠按 5.2 的父行锁写法。`any`、`count`、`sum` 都不能写进任意表达式或更新守卫。

### 5.10 `sum`：精确的金额合计

完整例子：

```sir-declarations
entity User persistent {
  identity id: Id generated auto;
}
entity Entry persistent {
  identity id: Id generated auto;
  field owner: Ref<User>;
  field amount: Optional<Money>;
}
type TotalAmount = Money;
capability TotalOfMine {
  actor Ref<User>;
  output TotalAmount;
  requires authenticated;
  requires readonly;
  expose query;
  workflow {
    sum Entry.amount where item.owner == actor as total;
    return total;
  }
}
```

`sum 实体.字段 where 条件 as 结果;` 是一个独立步骤，`sum` 只在这个位置是关键字，只能用在 `requires readonly` 的 query 里。求和的字段必须直接属于这个实体，底层是十进制（`Money`、它的别名，或 `Optional` 包着的这些）。不支持整数字段、`secret` 字段、顺着引用取的字段、表达式、去重、关联和分页；平均值见本节末尾，按引用分组见 5.11。`item` 只在这一步的条件里有效，结果变量在这一步之后才能用。

没有匹配的行，或者匹配的行金额全为空时，结果是 0；空值不参与求和。负数和末尾的零都保持精确，不经过浮点数，不舍入，也不套用字段自己的精度和 `min`／`max`，所以总额可以超过一笔 `Money` 的精度。例子里用 `type TotalAmount = Money;` 给输出起名，这不表示总额要符合一笔金额的存储规则。0 在 JSON 里显示几位小数不做保证。

条件的写法和普通查询一样（`any`、可选值比较、字面匹配、实体 `scope` 都可以用）。**权限要自己写**：`Crud` 的 `owner` 只管它生成的那几个接口，合计时仍要写 `item.owner == actor`；按分类合计时，还要检查这个分类属于当前用户。

在 Spring Boot／MySQL 目标上，每个 `sum` 是一条 `COALESCE(SUM(列), 0)`，不把行读回来再加。MySQL 在求和越界时可能只给一个警告，然后返回被截断的值，所以生成的代码会检查这条语句的警告：有警告或者查不了警告，都当作失败，不会返回一个错的总额。严格 SQL 模式管不到 SELECT，代替不了这项检查。含 `sum` 的工程会把 MyBatis 本地缓存设为 `STATEMENT`，保证每次都真的执行并检查；部署时改掉这个缓存设置或拦截器，这项保证就不成立。

`sum` 只读，不能用来控制写入的上限。计算字段不能引用 `sum` 的结果。设计依据见[设计第 24 节](design/12-feature-semantics-and-semantic-types.md#g8-decimal-sum-proposal)。

**平均值。**把关键字 `sum` 换成 `avg` 就是平均值，其它写法和限制都与 `sum` 相同（单值或 `by` 分组都可以）：

```text
avg Entry.amount where item.owner == actor and item.occurredAt >= input.since and item.occurredAt < input.until as mean;
```

平均值只算金额不为空的行；结果按字段自己声明的小数位**四舍五入（0.5 进位）**，例如 1.785 在两位小数的 `Money` 上是 1.79；没有匹配的行或者金额全为空时结果是 0，需要把“没有数据”和“平均为零”分开时，先用 `exists` 或 `count` 判断。在 MySQL 上是 `COALESCE(ROUND(AVG(列), 小数位), 0)`，同样检查语句警告。分组的平均值每组一个数，规则和分组合计一致。

### 5.11 `sum … by …`：按引用分组的精确金额分页

```sir-declarations
entity User persistent { identity id: Id generated auto; }
entity Category persistent {
  identity id: Id generated auto;
  field owner: Ref<User>;
}
entity Entry persistent {
  identity id: Id generated auto;
  field owner: Ref<User>;
  field category: Ref<Category>;
  field amount: Optional<Money>;
  field occurredAt: Moment;
}
input GroupRange {
  field since: Optional<Moment>;
  field until: Optional<Moment>;
  field page: PageNo;
  field size: PageSize;
}
error BadPage;
error BadRange;
capability TotalsByCategory {
  actor Ref<User>;
  input GroupRange;
  output Page<SumGroup<Ref<Category>>>;
  fails BadPage;
  fails BadRange;
  requires authenticated;
  requires readonly;
  expose query at "/entries/totals-by-category";
  workflow {
    validate input.until > input.since else BadRange;
    sum Entry.amount by category
      where item.owner == actor and any(Category, id == item.category and owner == actor)
        and item.occurredAt >= input.since and item.occurredAt < input.until
      Page input.page, input.size else BadPage
    as totals;
    return totals;
  }
}
```

分组的键只能是这个实体上直接的、非可选、非 `secret` 的引用字段；求和的字段仍是十进制或可选十进制。`SumGroup<Ref<Category>>` 只能用作 `Page` 的元素类型，不能用在实体、输入或别的地方。每一项固定是 `{key, total}`：`key` 是分类的主键值（不是分类本身，也不带名称），`total` 是精确的十进制总额，不套用一笔 `Money` 的精度。

外层仍是 `{total, page, size, records}`，但这里的 `total` 是**组数**，不是账目的行数。各组按键的主键值升序排列。没有匹配账目的分类不出现（不补零组）；有账目但金额全空的组，总额是 0。没有任何匹配时 `total` 为 0、`records` 为空；页码越界时 `total` 仍是正确的组数。`page`、`size` 沿用分页的默认值和上限，必须是输入里两个不同的直接字段，分页错误要写进 `fails`。例子里的 `validate input.until > input.since` 在任一时间缺失或顺序颠倒时都为 false，所以会在查询之前报 `BadRange`；时间区间是 `[since, until)`。

每个分组步骤读两次数据库：一次 `COUNT(DISTINCT 键)` 算组数，一次 `GROUP BY` 读当前页，不读明细，也不逐个分类去查。两次读用同一个只读快照（`REPEATABLE READ`），条件里的值只算一次，所以组数和当前页是一致的；两条语句都检查警告。任何一步失败都不返回半页。翻页是不同的请求，不保证看到同一个快照，所以也不能拿它控制写入的上限。

和单值合计一样，归属要自己写进条件：例子同时检查了账目属于当前用户、分类也属于当前用户。实体 `scope` 自动生效。不支持平均值、舍入、多个键、可选键、文本或枚举作键、`HAVING`、按总额排序和补零组。

### 5.12 `month`：一个自然月的起止时刻

```sir-declarations
type ZoneName = Text where timeZone, length(1, 64);
input MonthInput { field month: Optional<Text>; field zone: Optional<ZoneName>; }
error BadPeriod http 400;
capability MonthStart {
  input MonthInput; output Moment; fails BadPeriod; requires readonly; expose query;
  workflow {
    month input.month in input.zone as since, until else BadPeriod;
    return since;
  }
}
```

这一步只能用在只读的 query 里。两个表达式是文本或可选文本（不能是 `secret`）：第一个是年月，第二个是时区名。它不会自己去读用户的时区设置：上面的例子让请求自己带时区；记账样例则先 `load` 当前用户，再用保存的 `account.timeZone`，请求改不了它。`as` 后面的两个名字必须是新的，类型都是 `Moment`，后面的步骤才能用；错误要写进 `fails`。这一步本身不查数据库。

年月必须严格写成 `YYYY-MM`，不去空格、不补零、不猜时区。结果是当地这个月第一天和下个月第一天的最早时刻，换算成 UTC：`since` 是本月开始，`until` 是下月开始。当地零点因夏令时被跳过时，取跳过后的第一个时刻；零点出现两次时，取第一次。月的长度按日历算，不按固定秒数，也不是先取 UTC 的月再平移。查询时写 `item.occurredAt >= since and item.occurredAt < until`。这一步和前面读取时区设置的步骤在同一个只读事务（`REPEATABLE READ`）里。

缺值、显式 `null`、格式或月份不对、不认识的时区，或者算出的任一端点超出数据库 `DATETIME(6)` 的范围（`1000-01-01T00:00:00Z` 到 `9999-12-31T23:59:59.999999Z`），都报声明的错误（例子里是 400 `BadPeriod`），不会悄悄改用 UTC，也不会把 Java 的日期异常漏给客户端。所以 `9999-12`，以及时区偏移后越过下限的 `1000-01`，都可能被拒绝。含 `month` 的工程会改用按 UTC 读写 `Instant` 的数据库日期绑定，避开旧的 `Timestamp` 在早期历史日期上的历法偏差和 JVM 默认时区的影响；不含 `month` 的工程不变。

[记账样例](../sir-toolchain-application/src/test/resources/topics/ledger/project.sir)用它做了每月分类合计和预算超支提示。用户先在 `/api/me/time-zone` 保存自己的时区，没保存时月度接口报 `BadPeriod`。超支提示把这个分类当月的精确总额和预算上限比较，严格大于才算超支，等于不算。预算、账目和时区设置都可以修改，改了以后重查过去的月份，结果也会跟着变；样例没有为每个月保存预算快照，也不能拿它控制并发消费。给已有用户加时区这一列时，只加可空列，不替他们决定时区。

### 5.13 `each`：逐行处理查到的行（有上限）

`each` 先把要处理的行一次读完（最多读 `limit + 1` 行）。超过上限就直接报声明的错误，**循环体一次都不执行**；没有行时也不执行。完整例子：

```sir-declarations
entity Inventory persistent {
  identity id: Id generated auto;
  field stock: Count;
}
entity Parcel persistent {
  identity id: Id generated auto;
  field inventory: Ref<Inventory>;
  field quantity: Quantity;
}
input RestoreInput { field inventory: Ref<Inventory>; }
error InvalidQuantity http 409;
error MissingInventory http 404;
error StockLimit http 409;
error TooManyParcels http 409;
capability RestoreInventory {
  input RestoreInput;
  output Unit;
  fails InvalidQuantity;
  fails MissingInventory;
  fails StockLimit;
  fails TooManyParcels;
  requires atomic;
  expose command;
  workflow {
    each Parcel where item.inventory == input.inventory
      order by inventory ascending, id ascending
      limit 100 else TooManyParcels as parcel {
      validate parcel.quantity > 0 else InvalidQuantity;
      load Inventory by parcel.inventory as inventory for update else MissingInventory;
      validate inventory.stock >= 0 and inventory.stock <= 2147483647 - parcel.quantity else StockLimit;
      update inventory when inventory.stock >= 0 and inventory.stock <= 2147483647 - parcel.quantity else StockLimit {
        stock: inventory.stock + parcel.quantity;
      }
      persist inventory;
    }
    return unit;
  }
}
```

只能用在 `requires atomic` 的 command 里；它处理的是数据库里的行，不是请求里的列表。`each`、`limit` 只在这个位置是关键字。来源必须是持久化实体，条件和 `scope` 的规则与普通查询相同，`item` 只在条件里表示来源行。必须写 `order by`，并且以主键收尾，保证顺序稳定；引用字段按它存的主键排序。上限必须是 1 到 100 的整数字面量，不能是表达式；超过上限不会截断了事，而是报错。

循环体里能写 `validate`、`load`、`exists`、`count`、`create`、`update`、`persist`、`delete`（`SIR-FLOW-004`）。可以读外面的变量；能写、能删的是本次处理的行、体内 `load` 的行和体内 `create` 的行。循环外的行只能**累加**：一个不带守卫的 `update`，每个绑定都是“本字段 + 值”或“本字段 - 值”（见 5.6 的计数），后面跟 `persist`。这样每一次循环都是一条 `SET total = total + ?`，由数据库在存着的值上加，前一次的结果不会被后一次覆盖；要累加到本次新建的行上，这一行要在循环前先 `persist`。行变量和体内的变量出了循环就不能用。体内不能再嵌套 `each` 或 `repeat`，也不能有 `return`、`find`、求和或 `month`；没有 `break`、`continue`。体内任何一步失败，整笔事务回滚，之前已经执行过的循环也一起撤销。

下面是购物车结账：先存一张总额为 0 的订单，再逐个购物车条目锁商品、扣库存、写明细、把这一项的金额加到订单上，最后删掉条目。

```sir-declarations
entity Buyer persistent {
  identity id: Id generated auto;
}
entity Goods persistent {
  identity id: Id generated auto;
  field price: Money where min(0);
  field stock: Count where min(0);
}
entity BasketItem persistent {
  identity id: Id generated auto;
  field owner: Ref<Buyer>;
  field goods: Ref<Goods>;
  field quantity: Quantity;
  unique (owner, goods);
}
entity Sale persistent {
  identity id: Id generated auto;
  field buyer: Ref<Buyer>;
  field total: Money default 0.00;
}
entity SaleLine persistent {
  identity id: Id generated auto;
  field sale: Ref<Sale>;
  field goods: Ref<Goods>;
  field quantity: Quantity;
  field unitPrice: Money;
}
view SaleView from Sale {
  field id: Id;
  field total: Money;
}
error BasketEmpty http 409;
error BasketTooLarge http 409;
error GoodsMissing http 404;
error SoldOut http 409;
capability CheckOut {
  actor Ref<Buyer>;
  output SaleView;
  fails BasketEmpty;
  fails BasketTooLarge;
  fails GoodsMissing;
  fails SoldOut;
  requires authenticated;
  requires atomic;
  expose command at "/basket/checkout";
  workflow {
    exists BasketItem where item.owner == actor as hasItems;
    validate hasItems else BasketEmpty;
    create Sale as sale {
      buyer: actor;
    }
    persist sale;
    each BasketItem where item.owner == actor
      order by goods ascending, id ascending
      limit 50 else BasketTooLarge as entry {
      load Goods by entry.goods as goods for update else GoodsMissing;
      update goods when goods.stock >= entry.quantity else SoldOut {
        stock: goods.stock - entry.quantity;
      }
      persist goods;
      create SaleLine as line {
        sale: sale;
        goods: goods;
        quantity: entry.quantity;
        unitPrice: goods.price;
      }
      persist line;
      update sale {
        total: sale.total + goods.price * entry.quantity;
      }
      persist sale;
      delete entry;
    }
    return sale;
  }
}
```

商品按 id 升序加锁，几个人同时结账也按同一顺序等锁，不会互相死锁；任何一个商品库存不够，之前扣掉的库存、写下的明细和订单都一起撤销，购物车保持原样。

`each` 不会自动加锁。要防止重复操作或子行被并发修改，仍要先锁父行、检查状态，并按固定顺序锁子行。例子里先检查库存加上数量不会超过整数上限，再写带同样条件的守卫，不依赖数据库的严格模式防溢出。[商城样例](../sir-toolchain-application/src/test/resources/topics/shop/project.sir)的取消订单就是这样组合的：锁订单、守卫状态为 `CREATED`、检查明细存在、上限 100 行、按商品和 id 升序退回库存，任何一步失败整笔回滚；同一个样例里的结账就是上面购物车的写法。

### 5.14 `repeat`：按次数重复（有上限）

```
repeat 次数 times limit N else 错误 as 序号 { ... }
```

`each` 处理数据库里查到的行；`repeat` 处理"请求里说要做几次"，例如每周重复预约。次数先算一次，大于 `limit` 就直接报声明的错误，**循环体一次都不执行**；然后体内的 `序号` 依次是 0、1、……、次数 - 1，类型是 `Int32`，出了循环就不能用。完整例子：

```sir-declarations
entity Hall persistent {
  identity id: Id generated auto;
}
entity Slot persistent {
  identity id: Id generated auto;
  field hall: Ref<Hall>;
  field startsAt: Moment;
  field endsAt: Moment;
}
input WeeklySlot {
  field hallId: Id;
  field startsAt: Moment;
  field endsAt: Moment;
  field weeks: Quantity;
}
error HallMissing http 404;
error InvalidSpan http 400;
error SlotTaken http 409;
error TooManyWeeks http 409;
capability BookWeekly {
  input WeeklySlot;
  output Unit;
  fails HallMissing;
  fails InvalidSpan;
  fails SlotTaken;
  fails TooManyWeeks;
  requires atomic;
  expose command at "/slots/weekly";
  workflow {
    validate input.startsAt < input.endsAt else InvalidSpan;
    load Hall by input.hallId as hall for update else HallMissing;
    repeat input.weeks times limit 12 else TooManyWeeks as week {
      exists Slot where item.hall == hall and item.startsAt < addDays(input.endsAt, week * 7) and item.endsAt > addDays(input.startsAt, week * 7) as taken;
      validate not taken else SlotTaken;
      create Slot as made {
        hall: hall;
        startsAt: addDays(input.startsAt, week * 7);
        endsAt: addDays(input.endsAt, week * 7);
      }
      persist made;
    }
    return unit;
  }
}
```

次数必须是 `Int32`（`Count`、`Quantity` 或整数字面量，不能是可选值）；上限是 1 到 100 的整数字面量；只能用在 `requires atomic` 的 command 里；错误必须在 `fails` 里声明。循环体的规则和 `each` 完全相同（5.13）。先锁父行，再逐周检查、逐周写入：任何一周被占用，之前几周的预约一起回滚。


## 6. 表达式

| 写法 | 含义 |
| --- | --- |
| `true`、`false`、`42`、`0.01`、`"文本"`、`unit` | 字面量 |
| `名字`、`input`、`actor`、`item` | 变量；`input` 是请求，`actor` 是发起人，`item` 只在 `find`／`exists`／`count`／`sum` 的查询谓词里 |
| `a.b.c` | 取成员 |
| `x.字段.present` | 局部更新里，客户端传了没有（包括显式传 null） |
| `x is none`、`x is present` | 可选值为空／非空；不是 PATCH 的传参标记 |
| `枚举.成员` | 枚举值，如 `Status.OPEN` |
| `now()` | 现在（`Moment`） |
| `- x`、`not x` | 负号、非 |
| `a + b`、`a - b` | 同底层的整数（32 位或 64 位）或 `Money` 加减；整数溢出会抛错而不是回绕 |
| `a * b` | 同型整数精确乘法，或 Decimal 底层值乘 Decimal／整数；金额乘整数无损转换，不经浮点数 |
| `daysBetween(a, b)` | 从 `a` 到 `b` 经过的整天数，64 位整数，见下 |
| `max(a, b)`、`min(a, b)` | 两个同类型值里较大／较小的一个，见下 |
| `addDays(t, n)` | `Moment` 或 `Day` 加 `n` 整天（`Moment` 按 24 小时一天），`n` 是整数，见下 |
| `时刻 + N unit` | 时间平移，`unit` 是 `second(s)`、`minute(s)`、`hour(s)`、`day(s)` |
| `a < b`、`<=`、`>`、`>=` | 比较，两边同类型 |
| `a == b`、`a != b` | 相等 |
| `a containsLiteral b` | 字面包含，只能用在查询步骤的合法谓词里 |
| `存的 matches 候选` | 口令比对，见下 |
| `a and b`、`a or b` | 逻辑 |
| `( ... )` | 括号 |

**优先级**（从紧到松）：一元（`-`、`not`）→ `*` → `+`／`-` → 比较（`<` 等）→ `containsLiteral`／`matches` → `==`／`!=` → `and` → `or`。乘法从左往右结合；写了括号就按括号算，整数在哪一步溢出不会因为重新排列而改变。

**乘法。**`Count * Count` 得到 32 位整数，`Serial * Serial` 得到 64 位整数；不同宽度的整数不能直接相乘。`Money * Quantity`、`Quantity * Money`、金额乘 64 位整数、两个十进制值相乘，结果都是精确的十进制数，不舍入，也不继承两边的约束；写进字段时按目标字段检查。例如四位小数的单价 `0.1250 * 3` 写不进默认两位小数的 `Money`，`0.1250 * 8` 可以精确写成 `1.00`。整数溢出会报错并回滚事务，不回绕。没有除法、取模、整数和十进制的混合加减，也没有日期相乘或日期差。

```sir-declarations
entity Sale persistent {
  identity id: Id generated auto;
  field total: Money;
}
input SaleInput { field unitPrice: Money; field quantity: Quantity; }
capability RecordSale {
  input SaleInput;
  output Unit;
  requires atomic;
  expose command;
  workflow {
    create Sale as sale { total: input.unitPrice * input.quantity; }
    persist sale;
    return unit;
  }
}
```

**算术用到的输入同样必填。**按第 3.3 节，非可选输入不管怎样使用都必填；参与加、减、乘、取负、内置函数或 `repeat` 次数的输入，没传或传 `null` 同样在工作流之前返回 `400 INVALID_REQUEST`、字段错误码 `NotNull`，不会当成 0，哪怕只在某个分支里用到。分页参数是唯一的例外：在一个能力里只被分页步骤用作页码、页大小时有默认值；作者如果把 `page` 也拿去做运算，它就必须传。局部更新的 `changes` 不受这条影响，仍区分没传和 `null`，也不能参与算术。`Optional` 值不能参与算术，判过空也不行。引用不能参与算术，但整数主键的 `input.ref.id` 可以。查询条件里仍不能对列做乘法，`any` 里也不能做算术；请求这一侧算出的值可以作为普通查询参数。

**可选值。**`is none`／`is present` 只能用于 `Optional<T>`，可以写在查询条件、`validate` 和更新守卫里，优先级和大小比较相同；要否定就写 `not (x is none)`。可选值可以和同底层类型的值比较：任一边为空时，`==`、`!=`、`<` 等比较的结果都是 false，包括 `!=`，两个空值也不相等。`not (x == y)` 是普通取反，有空值时为 true，所以它和 `x != y` 不一样（见下表）。查询和工作流里的比较遵守同一张表。判过空并不改变类型：仍不能取可选引用的成员，不能对可选值做加减乘或时间平移；`containsLiteral` 的右边仍要求非可选文本。`is none` 也不表示“局部更新没传”：普通输入里没传和传 `null` 都算空；要问局部更新里传了没有，用 `.present`。

| 左值 | 右值 | `==` | `!=` | `not (==)` |
| --- | --- | --- | --- | --- |
| 空 | 空或有值 | false | false | true |
| 有值 | 空 | false | false | true |
| 相同值 | 相同值 | true | false | false |
| 不同值 | 不同值 | false | true | true |

**内置函数。**`daysBetween(a, b)` 是从 `a` 到 `b` 经过的整天数，结果是 64 位整数：两个 `Moment` 按 24 小时一天、向下取整（3 天半是 3，早 1 小时是 -1）；两个 `Day` 按日历天数相减。`b` 早于 `a` 时是负数。`max(a, b)`、`min(a, b)` 取两个同类型值里较大或较小的一个，可以是同一种数值，也可以是两个 `Moment` 或两个 `Day`；整数字面量会跟着另一边变宽（`max(0, daysBetween(...))` 里的 `0` 按 64 位理解）。三个函数的参数都不能是 `Optional`，也不能是文本或布尔；参数来自请求时和算术一样必须有值。它们和算术一样在 Java 里计算：查询条件里只能放在不读取 `item` 的一侧，`any(...)` 里不能用；计算视图字段可以用来源行的字段。`addDays(t, n)` 把 `Moment` 或 `Day` 往后挪 `n` 整天（负数往前），结果与 `t` 同类型，`n` 是 `Int32` 或 `Int64` 整数；`Moment` 按 24 小时一天，`Day` 按日历天。它同样不能有可选参数，规则与上面三个函数一致。`max`、`min`、`daysBetween`、`addDays` 不是保留字，别处仍可以当名字。

逾期罚款是典型用法：还书时锁住借阅记录，按逾期整天数乘费率，提前还不罚：

```sir-declarations
entity Loan persistent {
  identity id: Id generated auto;
  field dueAt: Moment;
  field returned: Flag default false;
  field fine: Money default 0.00;
}
view LoanView from Loan {
  field id: Id;
  field fine: Money;
}
input LoanRef { field id: Id; }
error AlreadyReturned http 409;
error LoanNotFound http 404;
capability GiveBack {
  input LoanRef;
  output LoanView;
  fails AlreadyReturned;
  fails LoanNotFound;
  requires atomic;
  expose command;
  workflow {
    load Loan by input.id as loan for update else LoanNotFound;
    update loan when loan.returned == false else AlreadyReturned {
      returned: true;
      fine: max(0, daysBetween(loan.dueAt, now())) * 0.50;
    }
    persist loan;
    return loan;
  }
}
```

**时间平移**：底必须是 `Moment`，或者只按天的 `Day`；数量最大 2147483647；孤立的“15 minutes”不是表达式。“一天”按 24 小时算。`now() + 15 minutes`、`input.start + 3 days`。

**`matches`**：左边必须是 `secret` 字段，右边是文本，结果是布尔。`secret` 字段只能放在它的左边读取；返回它、比较它、拿它做运算、放进 `find` 都会被拒绝（`SIR-VALID-008`）：

```sir-error SIR-VALID-008
entity Account persistent {
  identity id: Id generated auto;
  field name: Text unique;
  field passwordHash: Secret;
}
view AccountView from Account {
  field id: Id;
  field name: Text;
}
input LoginInput {
  field name: Text;
  field password: Text;
}
error BadLogin http 401;
capability Login {
  input LoginInput;
  output AccountView;
  fails BadLogin;
  requires readonly;
  expose command;
  workflow {
    load Account by name: input.name as account else BadLogin;
    validate account.passwordHash == input.password else BadLogin;
    return account;
  }
}
```

视图里放 `secret` 字段同样会被拒绝：

```sir-error SIR-VALID-008
entity Account persistent {
  identity id: Id generated auto;
  field passwordHash: Secret;
}
view AccountView from Account {
  field id: Id;
  field passwordHash: Text;
}
```

## 7. 常见模式

### 7.1 登录（口令只存摘要）

```sir-declarations
entity Account persistent {
  identity id: Id generated auto;
  field name: Text unique;
  field passwordHash: Secret;
}
view AccountView from Account {
  field id: Id;
  field name: Text;
}
input RegisterInput {
  field name: Name where length(1, 64);
  field password: Text where length(8, 128);
}
input LoginInput {
  field name: Text;
  field password: Text;
}
error BadLogin http 401;
capability Register {
  input RegisterInput;
  output AccountView;
  requires atomic;
  expose command;
  workflow {
    create Account as account {
      name: input.name;
      passwordHash: input.password;
    }
    persist account;
    return account;
  }
}
capability Login {
  input LoginInput;
  output AccountView;
  fails BadLogin;
  requires readonly;
  expose command;
  workflow {
    load Account by name: input.name as account else BadLogin;
    validate account.passwordHash matches input.password else BadLogin;
    return account;
  }
}
```

要点：绑定到 `secret` 字段的值（`passwordHash: input.password`）由生成的服务做 PBKDF2-HMAC-SHA256 摘要后再存，每条记录有自己的盐；口令错误和账号不存在**用同一个错误**，响应体一样（但响应时间不同：账号不存在时不做摘要计算，口令强度、锁定、限流不在语言里）。

**`AccountRegistration`：注册的样板。**每个有账号的软件都要写一遍“收名字和口令、建账号、重名报冲突、返回账号视图”，约 20 行。它们可以写成一个 `use`：

```text
use AccountRegistration {
  account: User;
  name: name;
  password: passwordHash;
  show: [role];
}
```

它展开成 `RegisterInput`（请求里的 `name` 和 `password`，口令 6 到 64 个字符）、`UserView`（身份、登录名，再加 `show` 里列的字段；视图名是账号实体名加 `View`）、错误 `NameTaken`（409）和能力 `Register`（`POST /api/register`，`persist … else NameTaken`）。`name`、`password` 是账号上的两个字段名，和 `DualTokenLogin` 里给的同名，默认值也一样；`show` 默认为空；`prefix` 给展开出的名字加前缀，避免和你自己写的同名声明冲突。展开出来的和手写的完全一样：同一个软件模型，逐字节相同的生成工程。

### 7.2 双 Token 登录

访问令牌认证请求，刷新令牌换新令牌且用过即废。一句话：

```sir-declarations
entity User persistent {
  identity id: Id generated auto;
}
use DualTokenLogin {
  account: User;
}
```

它展开成访问令牌（`credential` 实体）、刷新令牌（守卫更新，用过即废）、`Login`／`Refresh`／`Logout` 三个能力和它们的输入、视图与 401 错误，并给 `User` 加上唯一登录名 `loginName`（`Name`）和口令 `password`（`Secret`）两个字段——注册等其它能力照常使用它们。可选参数：`name`、`password`（两个字段叫什么）、`access`（访问令牌时长，默认 `15 minutes`）、`refresh`（默认 `7 days`）、`prefix`（加在它声明的每个名字前，避免重名）。

需要偏离这套标准写法时（比如登录要加验证码），不用 `use`，直接写出同样的声明再自己改：访问令牌是 `credential` 实体，刷新用守卫更新。

### 7.3 增删改查

一个实体的创建、按主键查看、分页列表、修改、删除，一句话：

```sir-declarations
entity Owner persistent {
  identity id: Token generated uuid;
  field name: Name;
}
entity Note persistent {
  identity id: Id generated auto;
  field title: Name unique;
  field body: Optional<Remark>;
  field owner: Ref<Owner>;
  field pinned: Flag default false;
  field version: Version;
}
use Crud {
  entity: Note;
}
use Crud {
  entity: Owner;
}
```

`use Crud { entity: Note; }` 展开成视图 `NoteView`、输入 `CreateNoteInput`／`NoteIdInput`／`ListNoteInput`／`UpdateNoteInput`、错误 `NoteNotFound`（404）／`InvalidNotePage`，以及五个能力：

| 能力 | 做什么 |
| --- | --- |
| `CreateNote` | 按输入创建一行；输入是除有默认值的字段和版本号以外的所有字段，带默认值的字段取默认值 |
| `GetNote` | 按主键读一行，没有时 `NoteNotFound` |
| `ListNote` | 按主键升序分页列出所有行，页码或页大小不对时 `InvalidNotePage` |
| `UpdateNote` | 按主键读一行再改；输入是主键和除口令、版本号以外的所有字段 |
| `DeleteNote` | 按主键删除一行，没有时 `NoteNotFound` |

规则都从实体来：视图显示除口令字段以外的所有字段；带版本号的实体，修改用 `patch` 输入（只改请求里带了的字段），版本不对时报 `StaleNote`（409）；不带版本号的实体，修改时要给出全部可改字段。实体有 `scope`（比如软删除）时，查看、列表、修改、删除都看不到范围外的行。账号实体同时 `use DualTokenLogin` 时，它的增删改查也包括登录名和口令（口令只能写，不会出现在视图里）。

**归属**：`use Crud { entity: Note; owner: author; }` 让每一行属于一个账号（`author` 必须是 `Ref<账号>` 字段）。五个能力都要求已认证（`actor` 是 `author` 指向的账号）；创建时 `author` 自动设为当前用户，输入里没有它，修改也不改它；查看、列表、修改、删除都只能操作自己的行，别人的行按 `NoteNotFound`（404）处理，不暴露它存在。

```sir-declarations
entity User persistent {
  identity id: Id generated auto;
  field name: Name;
}
entity Post persistent {
  identity id: Id generated auto;
  field author: Ref<User>;
  field text: Text;
}
use Crud {
  entity: Post;
  owner: author;
}
```

**路径**：`use Crud { entity: Note; path: notes; }` 用 REST 风格的路径：`POST /api/notes` 创建、`GET /api/notes/{id}` 查看、`GET /api/notes` 列表、`PUT /api/notes/{id}` 修改（带版本号的实体是 `PATCH`）、`DELETE /api/notes/{id}` 删除。`path` 接受小写名字或多段字符串，如 `path: "my/posts";`；拒绝空段、前后斜杠、`..` 和参数占位段。不写时每个能力按自己的名字（`/api/create-note` 等）。

**写权限和写保护**：下面这些参数都可以不写，不写时行为和以前一样。它们展开后仍是普通的 `actor` 和 `validate`，没有 Crud 专用的运行时代码。

| 参数 | 含义 |
| --- | --- |
| `writer: User` | 创建、更新、删除要求这个账号的认证；单独使用只要求登录。与 owner 共用时，账号类型必须一致。 |
| `writeRole: LIBRARIAN` | 必须同时写 `writer`。创建、更新、删除都要求角色匹配，否则报 `${entity}Forbidden`（403）；先检查角色，再读业务行。 |
| `roleField: role` | 默认是 `role`。`writeRole` 读的是 `writer` 账号上的这个字段，它必须是非可选的枚举，`writeRole` 的值是这个枚举的成员。 |
| `writeExclude: [status, createdAt]` | 这些字段同时从创建、普通更新和局部更新的输入里去掉，也不能被赋值；视图里照常显示，也不会自动赋值。不能排除没有默认值的必填字段，也不能排到更新时没有字段可写。 |
| `read: public` | `Get`／`List` 不要求登录，也不加“只看自己”的条件，但实体 `scope` 仍然生效。不写时保持原来的读权限。 |
| `readView: CatalogueBook` | `Get`／`List` 返回这个视图而不是自动生成的 `${entity}View`；创建、更新、删除的输入与返回不变，仍是自动视图。视图必须是同一实体的视图，只能少列字段，不能改变写权限或路由。 |

例如 `use Crud { entity: Book; writer: User; writeRole: LIBRARIAN; }` 保持目录公开读、仅馆员可写。`use Crud { entity: Item; owner: seller; read: public; writeExclude: [status, createdAt]; }` 公开符合 scope 的商品，并仅让本人写可写字段；**不自动筛选在售商品**。角色与归属为 AND，不给予管理员越权；未登录 401、角色错误 403、别人行 404，版本冲突保持 409。完整可编译样例见 [crud-access.sir](../sir-toolchain-application/src/test/resources/valid/crud-access.sir)。

被排除的字段如果出现在请求里，按工程对未知字段的设置处理（忽略或拒绝），但无论如何不会被写入。它也不是并发控制：没有版本号或守卫的普通更新，并发时仍可能互相覆盖。需要别的权限或并发规则时，写普通能力，或者用 `feature` 定义自己的版本。

## 8. 多文件工程

入口文件的 `sources { source "路径"; }` 列出其它文件，其它文件开头写 `sir 0.3` 加 `imports { import 声明名 from "路径"; }` 和 `declarations { ... }`（没有 `software`、`metadata`、`target`）。**跨文件用到哪个声明，就要在用到它的文件里 import 它**，否则报 `SIR-SOURCE-VISIBILITY-001`。声明名在整个工程里不能重复。

## 9. 诊断码与典型错误

诊断消息本身是中文，带文件、行号和列号；修之前先看它说的位置。一次可能报出多条，先修最前面的——后面的常常是前面那条的连带结果（报了太多错之后会出现 `SIR-PARSE-999`，表示停止继续分析）。

| 码 | 意思 |
| --- | --- |
| `SIR-LEX-*` | 词法错误：非法字符、字符串没闭合 |
| `SIR-PARSE-001` | 语法错误（最常见）：缺分号、保留字当名字、子句顺序不对，都是这个码 |
| `SIR-PARSE-002`／`003`／`004` | 语法错误的其它形式：缺少必要的部分／多余的内容／文件提前结束（括号没闭合） |
| `SIR-VERSION-001` | 文档不是 `sir 0.3` |
| `SIR-TARGET-001`、`SIR-LOWER-TARGET-001` | `target` 写了目前不支持的值 |
| `SIR-SYMBOL-001` | 重复定义（实体、字段、变量、错误、能力重名） |
| `SIR-SYMBOL-002` | 引用了不存在的名字或字段 |
| `SIR-SYMBOL-003` | 视图嵌套另一个视图时，两个实体之间有不止一个 `Ref`，关联不唯一 |
| `SIR-IDENTITY-001`／`002` | `@id` 的取值格式不对／重复 |
| `SIR-TYPE-001` | 类型不一致：比较或数值运算的操作数不兼容、返回类型不匹配、字面量超出范围、状态码不在 400／401／403／404／409 里、`secret`／`unique` 用在不允许的类型上 |
| `SIR-TYPE-002` | `Ref<...>` 的目标不是实体 |
| `SIR-TYPE-003` | `Optional<Optional<T>>` |
| `SIR-TYPE-004` | `any` 的条件没有受支持的根行外键／身份关联，或根行／关联目标不匹配 |
| `SIR-VALID-001` | 通用规则：`fails` 没排序、`requires` 顺序不对、`query` 没有 `readonly`、多个写步骤没有 `atomic`、`find` 谓词的比较方向不对、`any` 内同一关联字段自比较、视图字段带了约束等 |
| `SIR-VALID-002` | `versioned` 的规则：每个实体最多一个、版本字段不能带约束或 `unique`、只能用在实体字段上 |
| `SIR-VALID-003` | 局部更新输入（`patch of`）的规则 |
| `SIR-VALID-014` | 联合唯一 `unique (...)` 的规则：字段不能可选、`secret`、版本号、主键或不限长的文本，不能重复，长度加起来不能超过索引上限 |
| `SIR-VALID-004` | 带 `versioned` 实体的更新规则、`persist ... else` 的用法（含 exists 后唯一新行写入必须声明冲突错误）、局部更新的字段必须全部绑定 |
| `SIR-VALID-005` | 视图嵌套太深（最多 2 层） |
| `SIR-VALID-006` | `load` 按字段加载的规则 |
| `SIR-VALID-007` | 守卫更新的规则 |
| `SIR-VALID-008` | `secret` 字段的规则 |
| `SIR-VALID-009` | 凭据实体的规则 |
| `SIR-VALID-010` | `default` 的规则：`unique`、`secret`、`versioned` 字段不能有默认值 |
| `SIR-VALID-011` | `scope` 的规则：不能用 `any(...)` 或 `.present` |
| `SIR-VALID-012` | `delete` 的规则：只能删 `load` 或 `each` 读出来的行，删后不能再用 |
| `SIR-VALID-013` | 方法与路径的规则：`put`/`delete` 只用于 command、路径段与路径字段的写法、方法加路径不能重复 |
| `SIR-TYPE-005` | `type` 声明的问题：名字被语言占用、基类型成环 |
| `SIR-EXTEND-001` | `extend` 的目标不是实体 |
| `SIR-FEATURE-001`…`006` | 功能的问题：不认识的功能、参数缺失或多余、参数值不对（包括该指向实体却没有）、展开失败、对同一组值重复 `use`、`feature` 定义本身有错（包括模板里的循环和条件） |
| `SIR-FLOW-001` | 用了未声明或还没声明的变量，或在 `find` 之外用了 `item` |
| `SIR-FLOW-002` | `return` 的数量不对，或 `return` 后面还有步骤 |
| `SIR-FLOW-003` | 用到的错误没有在 `fails` 里声明 |
| `SIR-VALID-015` | 先 `persist` 指向一行的新行、再改这一行，而这一行没有用 `for update` 加载：两个并发请求会死锁 |
| `SIR-WARN-001` | （警告，不阻止编译）先 `exists`／`count` 检查再 `create`，既没有 `for update` 锁也没有 `unique` 兜底，并发时两个请求可能同时通过 |
| `SIR-FLOW-004` | 只读能力里有 `create`／`update`／`persist`／`delete` |
| `SIR-FLOW-005`／`006` | `any` 出现在 `find`／`exists`／`count`／`sum` 的谓词之外／`any` 里面又嵌套了 `any` |
| `SIR-FLOW-007` | 新建的行还没 `persist` 就读了它的主键：读 `变量.主键`、把它当引用的值，或返回含主键的视图 |
| `SIR-SOURCE-*` | 多文件工程的问题：入口没列出文件、`import` 找不到声明、没 import 就用了别的文件的声明（`SIR-SOURCE-VISIBILITY-001`） |
| `SIR-LOWER-*` | 软件本身合法，但目标（Java／Spring Boot／MySQL）暂不支持 |

下面每一条都是真的会触发那个码的最小写法。

```sir-error SIR-PARSE-001
entity Note persistent {
  identity id: Id generated auto;
  field text: Text
}
```

```sir-error SIR-VERSION-001
sir 0.1

software Old {
  metadata {
    displayName "Old";
    namespace "example.old";
  }
  target {
    language java 21;
    framework spring_boot;
    persistence mybatis_plus;
    database mysql;
    build maven;
    interface rest;
  }
  sources { }
  imports { }
  declarations { }
}
```

```sir-error SIR-TARGET-001
sir 0.3

software Elsewhere {
  metadata {
    displayName "Elsewhere";
    namespace "example.elsewhere";
  }
  target {
    language java 21;
    framework spring_boot;
    persistence mybatis_plus;
    database postgres;
    build maven;
    interface rest;
  }
  sources { }
  imports { }
  declarations { }
}
```

```sir-error SIR-SYMBOL-001
entity Note persistent {
  identity id: Id generated auto;
}
entity Note persistent {
  identity id: Id generated auto;
}
```

```sir-error SIR-SYMBOL-002
entity Note persistent {
  identity id: Id generated auto;
  field text: Text;
}
view NoteView from Note {
  field id: Id;
  field text: Text;
}
input NoteRef {
  field id: Id;
}
error NoNote http 404;
capability GetNote {
  input NoteRef;
  output NoteView;
  fails NoNote;
  requires readonly;
  expose query;
  workflow {
    load Note by input.ident as note else NoNote;
    return note;
  }
}
```

```sir-error SIR-SYMBOL-003
entity Team persistent {
  identity id: Id generated auto;
  field name: Text;
}
entity Match persistent {
  identity id: Id generated auto;
  field home: Ref<Team>;
  field away: Ref<Team>;
}
view TeamView from Team {
  field id: Id;
  field name: Text;
}
view MatchView from Match {
  field id: Id;
  field home: TeamView;
}
```

```sir-error SIR-TYPE-001
error Boom http 500;
```

```sir-error SIR-TYPE-002
enum Level { LOW, HIGH }
entity Task persistent {
  identity id: Id generated auto;
  field level: Ref<Level>;
}
```

```sir-error SIR-TYPE-003
entity Task persistent {
  identity id: Id generated auto;
  field note: Optional<Optional<Text>>;
}
```

```sir-error SIR-TYPE-004
entity Course persistent {
  identity id: Id generated auto;
  field code: Text;
}
entity Enrollment persistent {
  identity id: Id generated auto;
  field course: Ref<Course>;
  field active: Flag;
}
view CourseView from Course {
  field id: Id;
}
input PageInput {
  field page: PageNo;
  field size: PageSize;
}
error InvalidPage;
capability ActiveCourses {
  input PageInput;
  output Page<CourseView>;
  fails InvalidPage;
  requires readonly;
  expose query;
  workflow {
    find Course where any(Enrollment, active == true) Page input.page, input.size else InvalidPage as courses;
    return courses;
  }
}
```

```sir-error SIR-VALID-001
entity Note persistent {
  identity id: Id generated auto;
  field text: Text;
}
view NoteView from Note {
  field id: Id;
  field text: Text;
}
input NoteInput {
  field text: Text where notBlank;
}
capability TwoWrites {
  input NoteInput;
  output NoteView;
  expose command;
  workflow {
    create Note as first {
      text: input.text;
    }
    create Note as second {
      text: input.text;
    }
    persist first;
    persist second;
    return second;
  }
}
```

```sir-error SIR-VALID-002
entity Counter persistent {
  identity id: Id generated auto;
  field a: Version;
  field b: Version;
}
```

```sir-error SIR-VALID-003
entity Course persistent {
  identity id: Id generated auto;
  field name: Text;
  field version: Version;
}
input RenameCourse patch of Course {
  field name: Text;
}
```

```sir-error SIR-VALID-004
entity Note persistent {
  identity id: Id generated auto;
  field text: Text;
}
view NoteView from Note {
  field id: Id;
  field text: Text;
}
input NoteInput {
  field text: Text;
}
error Stale http 409;
capability PostNote {
  input NoteInput;
  output NoteView;
  fails Stale;
  requires atomic;
  expose command;
  workflow {
    create Note as note {
      text: input.text;
    }
    persist note else Stale;
    return note;
  }
}
```

```sir-error SIR-VALID-005
entity A persistent {
  identity id: Id generated auto;
  field name: Text;
}
entity B persistent {
  identity id: Id generated auto;
  field a: Ref<A>;
}
entity C persistent {
  identity id: Id generated auto;
  field b: Ref<B>;
}
entity D persistent {
  identity id: Id generated auto;
  field c: Ref<C>;
}
view AView from A {
  field id: Id;
  field name: Text;
}
view BView from B {
  field id: Id;
  field a: AView;
}
view CView from C {
  field id: Id;
  field b: BView;
}
view DView from D {
  field id: Id;
  field c: CView;
}
```

```sir-error SIR-VALID-006
entity Account persistent {
  identity id: Id generated auto;
  field name: Text;
}
view AccountView from Account {
  field id: Id;
}
input ByName {
  field name: Text;
}
error AccountNotFound http 404;
capability FindAccount {
  input ByName;
  output AccountView;
  fails AccountNotFound;
  requires readonly;
  expose query;
  workflow {
    load Account by name: input.name as account else AccountNotFound;
    return account;
  }
}
```

```sir-error SIR-VALID-007
entity Token persistent {
  identity id: Token generated uuid;
  field used: Flag;
  field uses: Count;
}
view TokenView from Token {
  field id: Token;
}
input TokenInput {
  field token: Token;
}
error Gone http 409;
capability Spend {
  input TokenInput;
  output TokenView;
  fails Gone;
  requires atomic;
  expose command;
  workflow {
    load Token by input.token as token else Gone;
    update token when token.used == false else Gone { uses: token.uses + 1; }
    persist token;
    return token;
  }
}
```

```sir-error SIR-VALID-009
entity User persistent {
  identity id: Id generated auto;
}
entity AccessToken persistent {
  identity id: Token generated uuid;
  field owner: Ref<User>;
  field expiresAt: Moment;
  credential subject owner expires expiresAt;
}
entity ApiKey persistent {
  identity id: Token generated uuid;
  field owner: Ref<User>;
  field expiresAt: Moment;
  credential subject owner expires expiresAt;
}
```

```sir-error SIR-FLOW-001
entity Note persistent {
  identity id: Id generated auto;
  field text: Text;
}
view NoteView from Note {
  field id: Id;
  field text: Text;
}
capability Nothing {
  output NoteView;
  requires readonly;
  expose query;
  workflow {
    return note;
  }
}
```

```sir-error SIR-FLOW-002
entity Note persistent {
  identity id: Id generated auto;
  field text: Text;
}
view NoteView from Note {
  field id: Id;
}
input NoteRef {
  field id: Id;
}
error NoNote http 404;
capability GetNote {
  input NoteRef;
  output NoteView;
  fails NoNote;
  requires readonly;
  expose query;
  workflow {
    load Note by input.id as note else NoNote;
  }
}
```

```sir-error SIR-FLOW-003
entity Note persistent {
  identity id: Id generated auto;
  field text: Text;
}
view NoteView from Note {
  field id: Id;
}
input NoteRef {
  field id: Id;
}
error NoNote http 404;
capability GetNote {
  input NoteRef;
  output NoteView;
  requires readonly;
  expose query;
  workflow {
    load Note by input.id as note else NoNote;
    return note;
  }
}
```

```sir-error SIR-FLOW-004
entity Note persistent {
  identity id: Id generated auto;
  field text: Text;
}
view NoteView from Note {
  field id: Id;
}
input NoteInput {
  field text: Text;
}
capability Wrong {
  input NoteInput;
  output NoteView;
  requires readonly;
  expose query;
  workflow {
    create Note as note {
      text: input.text;
    }
    return note;
  }
}
```

```sir-error SIR-FLOW-005
entity Course persistent {
  identity id: Id generated auto;
  field code: Text;
}
entity Enrollment persistent {
  identity id: Id generated auto;
  field course: Ref<Course>;
}
view CourseView from Course {
  field id: Id;
}
input CourseRef {
  field id: Id;
}
error NoCourse http 404;
capability Wrong {
  input CourseRef;
  output CourseView;
  fails NoCourse;
  requires readonly;
  expose query;
  workflow {
    load Course by input.id as course else NoCourse;
    validate any(Enrollment, course == course) else NoCourse;
    return course;
  }
}
```

## 10. 还没有的东西

- 已上线系统的部分结构变更：改列的类型（除放宽外）、给已有表加引用、唯一列或联合唯一、删掉引用别的表的字段，会在打交付包时被拒绝。可以做的有：改列的默认值（已有的行保持原值）；放宽（`Count` 改 `Serial`、`Name where length(1, 16)` 改成更大的长度或去掉长度、必填改 `Optional<…>`，已有的值一个都不变）；删实体或字段，但要在打包时写 `--confirm-backup yes` 表示已备份，数据会随之丢掉。数据库里字段名是下划线形式（`passwordHash` → `password_hash`，`Ref` 字段 `owner` → `owner_id`），`Token` 列用 `CHAR(36)`。
- 口令强度、登录失败的锁定与限流、令牌的缓存。
- 除上面的 target 之外的语言、框架、数据库。
