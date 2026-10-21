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

**金额精度。** `Money` 默认最多 17 位整数、2 位小数，不隐含非负或币种。`precision(p, s)` 表示总位数和小数位数，参数必须为整数常量，`p >= 1`、`0 <= s <= p`；只用于十进制值。类型和字段上可按原有覆盖规则改精度：

```sir-declarations
type UnitPrice = Money where precision(19, 4), min(0);
entity PriceTag persistent {
  identity id: Id generated auto;
  field amount: Money default 0.0;
  field unitPrice: UnitPrice;
  field fraction: Money where precision(2, 2);
}
```

按数值精确可表示判断：默认 Money 接受 `1.23`、`1.2300`，HTTP 输入也接受等值指数写法；拒绝 `1.234`，不会隐式舍入。`precision(2, 2)` 接受零和 `0.99`，不接受 `1`。输入校验与写入候选校验遵守同一规则；计算后仍按目标字段的精度检查，不能靠较宽输入让数据库舍入。Money 的新默认收紧了旧的无界十进制合法值范围；旧金额分数据不会自动转换。

MySQL 交付为显式 `DECIMAL(p,s)`，还需 `p <= 65`、`s <= 30`；精度变化不自动迁移。PATCH 的继承精度以目标实体为准，不采用载荷类型的默认精度；显式 PATCH 约束与其他类型带入的业务约束仍按原协议拒绝。可选字段的 absent／null／present 语义不变。

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

然后是若干 `field`。字段后面可以接修饰，**顺序固定**：约束（`where ...`）、`versioned`、`unique`、`secret`、`default ...`、`@id("...")`。最后可以有 `credential ...;`（见下）和 `scope ...;`（见下）。

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

**约束**（`where` 后面，逗号分隔）：`notBlank`（非空白，文本）、`email`（邮箱格式，文本）、`length(最小, 最大)`（文本）、`min(值)`、`max(值)`（数字）、`precision(总位数, 小数位)`（十进制）。同一个约束不能重复，`min` 不能大于 `max`。

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

`scope 条件;` 写在实体最后，说明哪些行对软件来说“存在”。这个实体的每个 `load`、`find`、`exists`、`count` 和 `any(...)` 都只看满足条件的行：`find` 自动加上这个条件，`load` 到范围外的行时按 `load` 自己的错误失败。条件里直接写本实体的字段名（和 `any` 的条件一样）。典型用法是软删除：

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

输入描述一个能力接收的请求内容。输入（和视图）的字段只有名字、类型和可选的约束：`unique`、`secret`、`@id` 是实体字段才有的修饰，写在输入或视图里是语法错误。

```sir-declarations
input RegisterInput {
  field name: Name where length(1, 64);
  field note: Optional<Text>;
}
```

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

视图描述返回给客户端的内容，是某个实体的**投影**：`view 名字 from 实体 { field 同名字段: 类型; }`。字段名和类型必须和实体字段一致，**视图字段不能带约束**，也不能出现 `secret` 字段。一个视图字段还可以是关联实体的视图（嵌套）：

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

一个功能是一组声明的名字：`use 功能 { 参数: 值; }` 在编译时展开成它代表的声明，展开出来的东西和手写的一样被检查、生成、部署，但不写进源码，编辑时也只改 `use` 这一处。语言自带 `DualTokenLogin`（双 Token 登录，见 7.2）和 `Crud`（增删改查，见 7.3）。

自己的功能用 `feature` 定义：参数有 `name`（一个名字，默认值可以是空的 `""`）、`duration`（如 `7 days`）、`text`（如 `"my/posts"`，兼容单个名字）和 `names`（如 `[status, createdAt]`，空列表是 `[]`），可以有默认值。列表供字段 `only`／`except` 选择，不作为拼接的标量；模板是三个反引号括起来的 SIR 声明，用 `${参数}` 引用标量参数：

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

字段列表参数例如 `parameter hidden: names = [];`，在 `${for f in entity.fields except secret, hidden}` 中使用；名字必须属于该实体（含 extend），重复项、未知项定位到具体元素。实体、字段和枚举参数依正式符号绑定改名；路径文字、模式值和另一个实体的同名字段不会被顺便改动。省略的默认引用在改名后会按需写为显式参数。

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

工作流是一串**步骤**，按顺序执行，**以且只以一个 `return` 结束**（`SIR-FLOW-002`）。变量由 `load`、`find`、`exists`、`count`、`create` 的 `as 名字` 引入，之后才能用（`SIR-FLOW-001`）。

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

`any` 只能出现在查询步骤的谓词里，不能嵌套，条件里必须有 `指向根实体的Ref字段 == item`。

### 5.4 `create`

```
create 实体 as 变量 { 字段: 值; ... }
```

必须给出所有**必填**字段：除了主键（系统生成）、`versioned` 字段（框架初始化）、`Optional<...>` 字段和带 `default` 的字段之外的每一个，漏了报 `SIR-FLOW-001`。不能绑定主键或 `versioned` 字段，同一个字段也不能绑两次。创建后要 `persist` 才会写入。若另一行要引用这条新建的自增主键行，应先 `persist` 生成主键，再创建引用它的行；引用绑定读取当时的主键，不会在稍后自动重绑。

### 5.5 `update` 与 `persist`

```
update 变量 { 字段: 值; ... }
persist 变量;
```

`update` 只改内存里的这一行，`persist` 才写入。`update` 不能改主键，也不能改 `versioned` 字段（框架负责递增）。

对**带 `versioned` 的实体**：更新它的能力必须用这个实体的局部更新输入（`patch of`，见 3.3），更新后的 `persist` 必须写 `else 错误名`，在别人先改过时报这个错误；对没有 `versioned` 的实体，`else` 只有下面说的一种用法。违反这几条都是 `SIR-VALID-004`。

**新建的行撞上已有的行。**实体里有 `unique` 字段时，新建的行可能和已经存在的行重复（比如重复注册同一个名字）。`persist 变量 else 错误名;` 说明这时报哪个已声明的错误；不写 `else`，数据库的异常会原样变成 500。这个 `else` 只对 `create` 出来的行、且实体有唯一约束时才能写（`SIR-VALID-004`）：

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
    create Account as made {
      name: input.name;
    }
    persist made else NameTaken;
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

规则：守卫是布尔表达式，由“目标字段与值的比较”、目标可选字段的空值判断或不读取目标的可选值空值判断用 `and`／`or`／`not` 组合，比较的右边可以是值，也可以恰好是目标的另一个字段（`course.enrolled < course.capacity`，由数据库拿两列比）；目标必须是 `load` 出来的行；实体不能带 `versioned`；错误要在 `fails` 里声明；绑定的值**不能读目标自己的字段**（守卫只钉住它判断的那些字段，其它字段在内存里是旧的，读它们再写回会丢失更新），可以读 `input`、常量、`now()` 和别的变量，唯一的例外是下一节的“本字段加减一个值”（`SIR-VALID-007`）；带守卫的 `update` 后面要有对同一行的普通 `persist`，这一行只能有这一个 `update`。

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

规则（`SIR-VALID-007`）：形式只有 `字段: 变量.同一个字段 + 值` 和 `字段: 变量.同一个字段 - 值`，值不能读这一行；字段必须是整数（`Count`、`Quantity`、`Serial` 等）或带精度的 `Money`；行必须是 `load` 出来的，不能是本次新建的；实体不能带 `versioned`；这一行只能有这一个 `update`（它整个写成一条语句，只写绑定的那些列）。不带守卫也可以，行不在了会以服务器错误结束。写入后会把这一列重新读回来，所以返回的是存着的值，不是读到的旧值加一。

一个常见的搭配是“新建一行并给被引用的行计数”（选课：新建选课记录，课程的已选人数加一）。**先 `persist` 被计数的行，再 `persist` 新建的行**：在 MySQL（InnoDB）里，插入引用了某一行的记录会先给被引用的行加读锁，两个请求若各自拿着读锁再去改同一行，会互相等待而死锁；先改被计数的行，后到的请求在这里排队，就不会。

没有加守卫、又不是这个形式的“读自己的字段再写回”（例如 `ratio: row.total / ...` 一类，或 `a: row.b + 1`）仍然合法，但那是“读、改、写”，并发下会丢更新；要并发安全就按这一节写。原子写只支持本字段加减一个独立值，不支持本字段乘一个值；这个独立 delta 可以用精确乘法计算，但不能读取目标行。Money 的 delta 必须能精确表示到目标小数位；实际原子写事务连接必须处于 `STRICT_TRANS_TABLES` 或 `STRICT_ALL_TABLES`。最终金额溢出属于数据库执行失败并回滚，不冒充作者的 guard 错误；缺少严格模式时也拒写，不修改服务器全局配置。非负余额等业务边界仍显式写 guard。

### 5.7 `delete`

```
delete 变量;
```

删除 `load` 读出来的这一行。只能删 `load` 出来的变量（不能删刚 `create` 的），删除后不能再 `update`、`persist` 或再次 `delete` 它（`SIR-VALID-012`）；只读能力里不能有（`SIR-FLOW-004`）。

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

两者都是显式的工作流步骤，执行到这里才读数据库，结果变量之后可以复用。`item` 只在本次查询的谓词内有效。`exists` 是 Boolean，零行时 false；`count` 是非负的核心 Int64（Java long），零行时 0。没有隐含分页，也不取回全表后在 Java 中数行；实体 `scope` 和 G7 空值规则均生效。不能附加 `Page`、排序或视图投影。`exists`、`count` 是上下文词，不增加全局保留字。

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

每个查询步骤对应一条 SQL。Spring Boot 目标将 exists 降级成只投影主键、带固定 `LIMIT 1` 的读取，再转成布尔值；count 使用 `COUNT(*)`。这些物理选择和语句数量都记录在 Lowered IR 中。若读取 actor 的其它字段，仍会按第 4.2 节独立加载 actor 行，不能把步骤的单条查询预算误当成整次请求只查一次。

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

**不是并发保护。** `count < 5` 或“不存在”后插入，两个请求仍可能同时通过；`requires atomic` 不会自动锁住查询范围。硬限额继续组合行内原子计数和守卫；固定预约时段用联合唯一。没有通用锁、任意时间段并发排他、SUM／AVG／分组，也没有把 `any`／`count` 放进任意表达式或更新守卫。

## 6. 表达式

| 写法 | 含义 |
| --- | --- |
| `true`、`false`、`42`、`0.01`、`"文本"`、`unit` | 字面量 |
| `名字`、`input`、`actor`、`item` | 变量；`input` 是请求，`actor` 是发起人，`item` 只在 `find` 里 |
| `a.b.c` | 取成员 |
| `x.字段.present` | 局部更新里，客户端传了没有（包括显式传 null） |
| `x is none`、`x is present` | 可选值为空／非空；不是 PATCH 的传参标记 |
| `枚举.成员` | 枚举值，如 `Status.OPEN` |
| `now()` | 现在（`Moment`） |
| `- x`、`not x` | 负号、非 |
| `a + b`、`a - b` | 同底层的整数（32 位或 64 位）或 `Money` 加减；整数溢出会抛错而不是回绕 |
| `a * b` | 同型整数精确乘法，或 Decimal 底层值乘 Decimal／整数；金额乘整数无损转换，不经浮点数 |
| `时刻 + N unit` | 时间平移，`unit` 是 `second(s)`、`minute(s)`、`hour(s)`、`day(s)` |
| `a < b`、`<=`、`>`、`>=` | 比较，两边同类型 |
| `a == b`、`a != b` | 相等 |
| `a containsLiteral b` | 字面包含，只能用在 `find` 的谓词里 |
| `存的 matches 候选` | 口令比对，见下 |
| `a and b`、`a or b` | 逻辑 |
| `( ... )` | 括号 |

**优先级**（从紧到松）：一元（`-`、`not`）→ `*` → `+`／`-` → 比较（`<` 等）→ `containsLiteral`／`matches` → `==`／`!=` → `and` → `or`。乘法同级向左结合；`a * (b * c)` 保留括号指定的求值树，整数中间溢出的时点不会被重排。

**精确乘法（G6）。** `Count * Count` 得到 32 位整数，`Serial * Serial` 得到 64 位整数；两个不同宽度的整数字段不能直接相乘。`Money * Quantity`、`Quantity * Money`、金额乘 64 位整数、两个 Decimal 底层值相乘都得到精确 Decimal 中间值。没有隐式舍入，也不自动继承两边约束的并集；最终写入仍按目标字段检查。四位单价 `0.1250 * 3` 不能写入默认两位 Money，`0.1250 * 8` 可以精确写成 `1.00`。整数溢出沿用执行失败与事务回滚，不回绕，不假装为作者的业务错误。没有新增混合整数加减、除法、取模、日期乘法或日期差。

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

**算术输入缺值。** 本能力实际用于加、减、乘和数值取负的非 Optional 数值输入，在执行工作流之前检查；缺省或显式 null 返回 `400 INVALID_REQUEST`，字段错误码为 `NotNull`，不补零。短路分支也遵守此必需值约定；同一共享输入用于纯分页的另一个能力时不因此变必填，分页仍保留 page／size 默认。若作者把 page 另用于算术，它必须显式传入。PATCH 的 changes 仍保留 absent／null，不提升成必需数值；直接对 changes 值做数值运算仍被目标拒绝，主键信封值不属于 changes。Optional 不参与算术，即使先判空也不自动解包。引用本身不参与算术；整数主键的显式 `input.ref.id` 可按 Id 运算，缺值路径使用实际 DTO 字段名 `refId`。乘法仍不能作用于查询列；请求侧独立算式可以作普通查询参数，any 内算术不在本轮放开。

**可选值（G7）。** `is none`／`is present` 只用于 `Optional<T>`，作用于查询、工作流校验和更新守卫；优先级与大小比较相同。要否定空值判断，写 `not (x is none)`。普通比较要求去掉可选包装后的类型兼容，任意一侧为空均为 false（包括 `!=`，两个空值也不相等）；`not (x == y)` 则是普通取反，有空值时为 true，因此不等同于 `x != y`。查询和 Java 工作流遵守同一规则。空值判断不改变静态类型，不允许借此访问可选引用的成员、做可选值加减乘或时间平移；`containsLiteral` 的右侧仍要求非可选文本。`is none` 不表示“PATCH 没传”，缺席和显式 null 在普通可选输入中都按空值处理，PATCH 是否传过字段仍问 `.present`。

| 左值 | 右值 | `==` | `!=` | `not (==)` |
| --- | --- | --- | --- | --- |
| 空 | 空或有值 | false | false | true |
| 有值 | 空 | false | false | true |
| 相同值 | 相同值 | true | false | false |
| 不同值 | 不同值 | false | true | true |

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

**写权限和写保护（G9）**：原三个参数和省略时的行为不变，增加以下可选组合。角色比较和归属检查仍展开成普通 `actor`／`validate`，没有 Crud 专属运行时。

| 参数 | 含义 |
| --- | --- |
| `writer: User` | 创建、更新、删除要求这个账号的认证；单独使用只要求登录。与 owner 共用时，账号类型必须一致。 |
| `writeRole: LIBRARIAN` | 必须同时给 writer；三个写能力要求角色匹配，生成 `${entity}Forbidden`（403），且先验角色、后加载业务行。 |
| `roleField: role` | 默认 role；writeRole 所读的 writer 字段，须为非可选枚举，其类型决定成员属于哪个枚举。 |
| `writeExclude: [status, createdAt]` | 同时从创建／普通更新／PATCH 的输入与赋值排除这些字段，不从视图隐藏、不自动赋值。不允许排除无默认值的创建必填字段，或得到没有可写字段的更新。 |
| `read: public` | Get／List 不要求 owner 认证、也不追加本人条件，仍应用实体 scope。省略时保留原读权限。 |

例如 `use Crud { entity: Book; writer: User; writeRole: LIBRARIAN; }` 保持目录公开读、仅馆员可写。`use Crud { entity: Item; owner: seller; read: public; writeExclude: [status, createdAt]; }` 公开符合 scope 的商品，并仅让本人写可写字段；**不自动筛选在售商品**。角色与归属为 AND，不给予管理员越权；未登录 401、角色错误 403、别人行 404，版本冲突保持 409。完整可编译样例见 [crud-access.sir](../sir-toolchain-application/src/test/resources/valid/crud-access.sir)。

排除字段沿用既有请求未知字段策略（忽略或拒绝），但不能向这些字段赋值。它不是并发锁：未带 Version／guard 的普通更新仍遵循原持久化语义，不据此宣称解决并发写覆盖。需要不同的权限或并发规则时，写普通能力（或者用 `feature` 定义自己的版本）。

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
| `SIR-TYPE-004` | `any` 的条件里没有“指向根实体的 Ref 字段 `== item`” |
| `SIR-VALID-001` | 通用规则：`fails` 没排序、`requires` 顺序不对、`query` 没有 `readonly`、多个写步骤没有 `atomic`、`find` 谓词的比较方向不对、视图字段带了约束等 |
| `SIR-VALID-002` | `versioned` 的规则：每个实体最多一个、版本字段不能带约束或 `unique`、只能用在实体字段上 |
| `SIR-VALID-003` | 局部更新输入（`patch of`）的规则 |
| `SIR-VALID-014` | 联合唯一 `unique (...)` 的规则：字段不能可选、`secret`、版本号、主键或不限长的文本，不能重复，长度加起来不能超过索引上限 |
| `SIR-VALID-004` | 带 `versioned` 实体的更新规则、`persist ... else` 的用法、局部更新的字段必须全部绑定 |
| `SIR-VALID-005` | 视图嵌套太深（最多 2 层） |
| `SIR-VALID-006` | `load` 按字段加载的规则 |
| `SIR-VALID-007` | 守卫更新的规则 |
| `SIR-VALID-008` | `secret` 字段的规则 |
| `SIR-VALID-009` | 凭据实体的规则 |
| `SIR-VALID-010` | `default` 的规则：`unique`、`secret`、`versioned` 字段不能有默认值 |
| `SIR-VALID-011` | `scope` 的规则：不能用 `any(...)` 或 `.present` |
| `SIR-VALID-012` | `delete` 的规则：只能删 `load` 出来的行，删后不能再用 |
| `SIR-VALID-013` | 方法与路径的规则：`put`/`delete` 只用于 command、路径段与路径字段的写法、方法加路径不能重复 |
| `SIR-TYPE-005` | `type` 声明的问题：名字被语言占用、基类型成环 |
| `SIR-EXTEND-001` | `extend` 的目标不是实体 |
| `SIR-FEATURE-001`…`006` | 功能的问题：不认识的功能、参数缺失或多余、参数值不对（包括该指向实体却没有）、展开失败、对同一组值重复 `use`、`feature` 定义本身有错（包括模板里的循环和条件） |
| `SIR-FLOW-001` | 用了未声明或还没声明的变量，或在 `find` 之外用了 `item` |
| `SIR-FLOW-002` | `return` 的数量不对，或 `return` 后面还有步骤 |
| `SIR-FLOW-003` | 用到的错误没有在 `fails` 里声明 |
| `SIR-FLOW-004` | 只读能力里有 `create`／`update`／`persist`／`delete` |
| `SIR-FLOW-005`／`006` | `any` 出现在 `find`／`exists`／`count` 的谓词之外／`any` 里面又嵌套了 `any` |
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
