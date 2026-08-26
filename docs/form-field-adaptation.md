# 表单字段自适应算法

WYU-Monet 自动打卡支持表单字段动态变化，无需手动修改代码。无论表单增减字段，模块自动识别当前需要提交哪些字段。

## 背景

kdocs 表单（ksform）的字段结构中，每个字段有 `baseInfo.delete` 标记：

- `delete: false` — 字段生效，需要填写
- `delete: true` — 字段已删除（历史遗留，表单仍保留结构但不再显示）

例如五邑大学打卡表单从 4 个字段变为 2 个字段时，被删除的 `clockinName` 和 `clockinDepartment` 的 `baseInfo.delete` 会变为 `true`，而 `clockinLocation` 和 `clockinStudentId` 保持 `false`。

## 算法流程

### 1. 表单结构获取

```
GET https://f-api.kdocs.cn/ksform/api/v3/campaign/{campaignId}
```

返回 JSON 中的 `questionMap` 包含所有问题，其中 `type: "clockinInfo"` 的问题是打卡字段容器，其 `subQuestions` 数组包含所有子字段定义。

### 2. 字段过滤（模块侧）

`collectClockinNames` 递归遍历 JSON 节点，对每个字段检查：

```
字段对象 → baseInfo.delete == true ?
  → 是：跳过该字段
  → 否：收集字段名
```

伪代码：

```python
def collect_fields(node):
    if node is JSONObject:
        base = node.get("baseInfo")
        if base and base.get("delete") == true:
            return  # 整个节点跳过

        for key, value in node.items():
            if value is JSONObject:
                val_base = value.get("baseInfo")
                if val_base and val_base.get("delete") == true:
                    continue  # 跳过该子字段
            add_field(key)
            collect_fields(value)

    elif node is JSONArray:
        for item in node:
            collect_fields(item)
```

### 3. 字段过滤（CheckinWorker 侧）

CheckinWorker 运行在独立 root 进程中，直接解析表单 API 返回的 JSON 字符串：

```python
# 1. 定位 subQuestions 数组
sq_start = form_response.indexOf('"subQuestions"')
arr_start = form_response.indexOf("[", sq_start)
arr_end = form_response.indexOf("]", arr_start)

# 2. 提取字段名，跳过 delete:true 的字段
for each "clockinXxx" match in subQuestions block:
    obj = enclosing { ... } block
    if obj contains '"delete":true':
        skip
    else:
        collect field name
```

### 4. 缓存清空机制

每次 WPS 启动时，强制清空内存中的 `fields` 和 `values` 缓存：

```java
checkinClockinFields = "";
checkinClockinValues = "";
```

下次打卡时重新从 API 读取表单结构，确保字段列表始终是最新的。

### 5. 字段值获取

字段值（用户填了什么）从历史打卡记录获取：

```
POST .../answers/list  →  answerJson.answers.clockinInfoValue
```

提取其中每个 key 的 `strValue` 作为下次提交的值。

## 数据流

```
表单 API (campaign/{cid})
    │
    ▼
subQuestions 数组 ──→ baseInfo.delete 过滤 ──→ 有效字段列表
    │                                              │
    │                                              ▼
    │                                    存入 checkinClockinFields
    │
    ▼
answers/list API ──→ clockinInfoValue ──→ 字段值
    │
    ▼
  存入 checkinClockinValues
    │
    ▼
  CheckinWorker 读取并提交
```

## 兼容性

| 场景 | 行为 |
|------|------|
| 表单 2 个字段（delete 后） | 只提交 clockinLocation + clockinStudentId |
| 表单 4 个字段（全开） | 提交全部 4 个字段 |
| 表单新增字段 | 自动识别并提交 |
| API 不可用 | fallback 到旧的正则匹配 |
