# Working Directory Picker — Design Spec

**Date:** 2026-06-01
**Status:** approved

## Problem

1. 用户每次重启后前端设置页的"工作目录"显示为空，需要手动输入完整路径
2. 手动输入路径容易出错（拼写错误、路径不准确）

## Solution

在输入框旁边添加"浏览"按钮，点击调用浏览器原生 `window.showDirectoryPicker()` API，弹出系统文件夹选择对话框，选择后路径自动填入输入框。

## Scope

仅修改 `AIagent_frontend/src/views/SettingsView.vue` 一个文件，约 25 行改动。无后端改动。

## UI Changes

**Before:** 纯文本输入框，用户手动输入路径
```
[  输入框：如 D:\projects\my-app  ]  [保存] [清除]
```

**After:** 输入框 + 浏览按钮，点击弹出系统原生文件夹选择器
```
[  输入框：如 D:\projects\my-app  ] [📁 浏览] [保存] [清除]
  当前工作目录：D:\projects\my-agent   ← 新增：绿色提示条，显示已保存的路径
```

## Implementation Details

### 1. "浏览"按钮 (`browseDirectory`)

```js
async function browseDirectory() {
  try {
    const handle = await window.showDirectoryPicker()
    // showDirectoryPicker 返回的是 FileSystemDirectoryHandle
    // 但在 Web 应用中无法直接获取文件系统路径
    // 实际上这个 API 只适用于本地文件访问场景
  } catch (err) {
    if (err.name !== 'AbortError') {
      pathMsg.value = '目录选择失败：' + err.message
    }
  }
}
```

**实际情况：** `showDirectoryPicker()` 返回的是 `FileSystemDirectoryHandle`，不暴露文件系统的绝对路径。在 Web 环境中，无法从该 handle 获取到类似 `D:\projects\my-app` 这样的路径。因此：

- **如果需要绝对路径** → 采用服务端目录浏览方案
- **如果只需要目录访问权限** → 可用 `showDirectoryPicker()`，但后续文件操作必须通过 File System Access API 进行

### 修订方案（考虑到实际需要绝对路径给后端 Agent CLI 使用）

采用**轻量服务端方案**：

#### 前端改动 (`SettingsView.vue`)

1. 输入框区域改为：输入框 + "浏览"按钮 + 弹出面板
2. 点击"浏览"弹出 Popover/Modal，内含：
   - 面包屑导航（显示当前路径，可点击跳转）
   - 目录列表（只显示文件夹，点击可进入子目录或选中）
   - "选择此目录" / "取消" 按钮
3. 新增 `dirPickerVisible`、`dirPath`、`dirList` 等响应式状态
4. `openDirPicker()` → 调用 API 获取根目录列表 → 显示面板
5. `navigateTo(path)` → 调用 API 获取子目录列表 → 更新列表
6. `selectDir(path)` → 填入输入框，关闭面板
7. 面板关闭时不清空输入框已有内容

#### 后端改动（新增）

**`GET /api/v1/filesystem/dirs?path=`**

- `mateclaw-server` 新增 `FileSystemController`
- `mateclaw-domain` 新增 `FileSystemService`
- `path` 为空时返回根级别：
  - Windows: 盘符列表 `[C:\, D:\, ...]`
  - Linux/macOS: `/` 下的一级目录
- `path` 非空时返回该路径下的目录列表
- 安全限制：
  - 只返回目录，不返回文件
  - 验证用户已登录（Spring Security）
  - 只读操作，不修改文件系统
  - 对路径做规范化处理（防路径穿越）

**响应格式：**
```json
{
  "code": 200,
  "data": {
    "path": "D:\\projects",
    "parent": "D:\\",
    "dirs": [
      { "name": "my-agent", "path": "D:\\projects\\my-agent" },
      { "name": "node-app", "path": "D:\\projects\\node-app" }
    ]
  }
}
```

### 2. 持久化显示修复

在 `onMounted` 和 `watch` 中已正确处理 `basePath` 的加载。额外增加：

- 输入框下方显示当前已保存的工作目录（绿色提示条），即使输入框为空也能看到已保存的值
- 输入框 placeholder 动态显示当前已保存路径（如果已设置）

## Files Changed

| File | Change | Lines |
|------|--------|-------|
| `AIagent_frontend/src/views/SettingsView.vue` | 添加目录浏览面板 + "浏览"按钮 + 当前路径展示 | ~60 |
| `mateclaw-dev/mateclaw-server/.../FileSystemController.java` | 新增 GET /api/v1/filesystem/dirs | ~25 |
| `mateclaw-dev/mateclaw-domain/.../FileSystemService.java` | 新增，目录列表逻辑 | ~20 |
| `AIagent_frontend/src/api/filesystem.js` | 新增 API 封装 | ~8 |

## Edge Cases

- **目录不存在/不可访问：** API 返回空列表 + 错误提示
- **路径穿越攻击：** 后端规范化路径，检查 path 是否在允许范围内
- **面板打开时工作区未加载：** 提示"请先选择工作区"
- **已有输入内容再打开面板：** 面板从输入框当前路径开始浏览

## Verification

1. 打开设置页 → 已保存的目录显示在绿色提示条中
2. 点击"浏览"→ 弹出目录选择面板
3. 逐层点击目录 → 面包屑更新
4. 点击"选择此目录"→ 路径填入输入框，面板关闭
5. 点击"保存"→ 调用 API 保存 → 成功提示
6. 刷新页面 → 已保存的路径正确显示
7. Firefox/Safari 下不弹窗，仍可手动输入（渐进增强）

## Out of Scope

- 文件预览
- 多选目录
- 新建目录功能
