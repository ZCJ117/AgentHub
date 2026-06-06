# Working Directory Picker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Browse" button to the working directory input that opens a directory picker panel, and fix persistence display.

**Architecture:** New backend endpoint `GET /api/v1/filesystem/dirs` lists directories on the server filesystem. Frontend `SettingsView.vue` gets a "Browse" button that opens a Naive UI Modal with breadcrumb navigation and directory list. Selecting a directory fills the input. No database changes.

**Tech Stack:** Java 21 + Spring Boot (backend), Vue 3 + Naive UI (frontend)

---

## File Structure

- **Create:** `mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/filesystem/service/FileSystemService.java` — lists directories on server FS, handles Win/Unix root, path traversal prevention
- **Create:** `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/server/filesystem/controller/FileSystemController.java` — `GET /api/v1/filesystem/dirs?path=` endpoint
- **Create:** `AIagent_frontend/src/api/filesystem.js` — `fetchDirs(path)` API wrapper
- **Modify:** `AIagent_frontend/src/views/SettingsView.vue` — browse button, directory picker modal, current path display

---

### Task 1: Backend — FileSystemService (domain layer)

**Files:**
- Create: `mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/filesystem/service/FileSystemService.java`

- [ ] **Step 1: Create FileSystemService with listDirs method**

```java
package vip.mate.domain.filesystem.service;

import lombok.Data;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class FileSystemService {

    @Data
    public static class DirEntry {
        private final String name;
        private final String path;
    }

    @Data
    public static class DirResult {
        private final String path;
        private final String parent;
        private final List<DirEntry> dirs;
    }

    /**
     * List directories under the given path.
     * When path is empty or "/", returns root-level entries:
     *   Windows: drive letters (C:\, D:\, ...)
     *   Linux/macOS: / directories
     */
    public DirResult listDirs(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return listRoots();
        }

        // Normalize to prevent path traversal
        Path normalized = Paths.get(path).normalize().toAbsolutePath();
        File dir = normalized.toFile();

        if (!dir.exists() || !dir.isDirectory() || !dir.canRead()) {
            return new DirResult(normalized.toString(), parentPath(normalized), List.of());
        }

        File[] children = dir.listFiles(File::isDirectory);
        if (children == null) {
            return new DirResult(normalized.toString(), parentPath(normalized), List.of());
        }

        List<DirEntry> entries = Arrays.stream(children)
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .map(f -> new DirEntry(f.getName(), f.getAbsolutePath()))
                .collect(Collectors.toList());

        return new DirResult(normalized.toString(), parentPath(normalized), entries);
    }

    private DirResult listRoots() {
        String os = System.getProperty("os.name", "").toLowerCase();
        List<DirEntry> entries = new ArrayList<>();

        if (os.contains("win")) {
            // Windows: list drive letters
            for (File root : File.listRoots()) {
                entries.add(new DirEntry(root.getPath(), root.getPath()));
            }
        } else {
            // Unix: list / children
            File rootDir = new File("/");
            File[] children = rootDir.listFiles(File::isDirectory);
            if (children != null) {
                entries = Arrays.stream(children)
                        .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                        .map(f -> new DirEntry(f.getName(), f.getAbsolutePath()))
                        .collect(Collectors.toList());
            }
        }

        return new DirResult("/", null, entries);
    }

    private String parentPath(Path path) {
        Path parent = path.getParent();
        return parent != null ? parent.toString() : null;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/filesystem/service/FileSystemService.java
git commit -m "feat: add FileSystemService for directory listing"
```

---

### Task 2: Backend — FileSystemController (server layer)

**Files:**
- Create: `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/server/filesystem/controller/FileSystemController.java`

- [ ] **Step 1: Create FileSystemController with GET /api/v1/filesystem/dirs endpoint**

```java
package vip.mate.server.filesystem.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.domain.filesystem.service.FileSystemService;
import vip.mate.domain.filesystem.service.FileSystemService.DirResult;

@Tag(name = "文件系统")
@RestController
@RequestMapping("/api/v1/filesystem")
@RequiredArgsConstructor
public class FileSystemController {

    private final FileSystemService fileSystemService;

    @Operation(summary = "列出目录（仅子目录）")
    @GetMapping("/dirs")
    public R<DirResult> listDirs(@RequestParam(required = false) String path) {
        return R.ok(fileSystemService.listDirs(path));
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add mateclaw-dev/mateclaw-server/src/main/java/vip/mate/server/filesystem/controller/FileSystemController.java
git commit -m "feat: add FileSystemController GET /api/v1/filesystem/dirs"
```

---

### Task 3: Frontend — API wrapper

**Files:**
- Create: `AIagent_frontend/src/api/filesystem.js`

- [ ] **Step 1: Create filesystem.js API module**

```js
import apiClient from './client'

export function fetchDirs(path) {
  const params = path ? { path } : {}
  return apiClient.get('/api/v1/filesystem/dirs', { params })
}
```

- [ ] **Step 2: Commit**

```bash
git add AIagent_frontend/src/api/filesystem.js
git commit -m "feat: add filesystem API wrapper for directory listing"
```

---

### Task 4: Frontend — SettingsView.vue (directory picker)

**Files:**
- Modify: `AIagent_frontend/src/views/SettingsView.vue`

- [ ] **Step 1: Add imports for Modal and new API**

Insert after existing imports (lines 6-9):

```js
import { NModal, NButton, NSpace, NCard, NInput, NTag, NText, NCode, NPopconfirm, NDataTable, NEmpty, NDatePicker, NForm, NFormItem, NAlert } from 'naive-ui'
import { fetchDirs } from '@/api/filesystem'
```

Note: `NModal` is already imported (line 6). We just need to add `fetchDirs` import. Add after line 9:

```js
import { fetchDirs } from '@/api/filesystem'
```

- [ ] **Step 2: Add directory picker reactive state**

Insert after line 27 (`const tokenMsg = ref('')`):

```js
// --- Directory picker state ---
const dirPickerVisible = ref(false)
const dirPickerPath = ref('')
const dirPickerDirs = ref([])
const dirPickerBreadcrumb = ref([])  // [{label, path}] for breadcrumb nav
const dirPickerLoading = ref(false)
```

- [ ] **Step 3: Add directory picker functions**

Insert after `saveBasePath` (before line 156 `</script>`):

```js
async function openDirPicker() {
  await loadDirs(basePath.value || '')
  dirPickerVisible.value = true
}

async function loadDirs(targetPath) {
  dirPickerLoading.value = true
  try {
    const result = await fetchDirs(targetPath || null)
    dirPickerPath.value = result.path
    dirPickerDirs.value = result.dirs || []

    // Build breadcrumb: split path into segments, each with its ancestor path
    const crumbs = [{ label: '根目录', path: '' }]
    if (result.path) {
      const parts = result.path.replace(/\\/g, '/').split('/').filter(Boolean)
      let built = ''
      for (const part of parts) {
        built = built ? built + '/' + part : (result.path.match(/^[A-Za-z]:/) ? part + '/' : '/' + part)
        crumbs.push({ label: part, path: built })
      }
    }
    dirPickerBreadcrumb.value = crumbs
  } catch (err) {
    pathMsg.value = '目录加载失败：' + (err.message || '未知错误')
    dirPickerDirs.value = []
  } finally {
    dirPickerLoading.value = false
  }
}

function navigateToDir(targetPath) {
  loadDirs(targetPath)
}

function selectDir(targetPath) {
  basePath.value = targetPath
  dirPickerVisible.value = false
}

function closeDirPicker() {
  dirPickerVisible.value = false
}
```

- [ ] **Step 4: Replace the working directory input section**

Replace lines 199-205 (the `<div><label>工作目录...` block and the save button):

```html
        <div>
          <label>工作目录（Agent CLI 执行路径）</label>
          <div style="display: flex; gap: 8px;">
            <NInput v-model:value="basePath" placeholder="如 D:\projects\my-app" style="flex: 1;" />
            <NButton @click="openDirPicker" :disabled="workspaceStore.activeId == null">浏览</NButton>
          </div>
          <div v-if="workspaceStore.activeWorkspace?.basePath" style="margin-top: 6px; font-size: 12px; color: #18a058;">
            当前工作目录：{{ workspaceStore.activeWorkspace.basePath }}
          </div>
        </div>
        <NButton type="primary" @click="saveBasePath" :loading="savingPath">保存</NButton>
```

- [ ] **Step 5: Add directory picker Modal**

Insert before the closing `</div>` of the settings-view div (before line 269), after the last `</NCard>`:

```html
    <NModal v-model:show="dirPickerVisible" title="选择工作目录" style="width: 520px;">
      <div style="padding: 12px 0;">
        <!-- Breadcrumb -->
        <div style="display: flex; align-items: center; gap: 2px; flex-wrap: wrap; margin-bottom: 12px; font-size: 13px;">
          <span style="color: #666; margin-right: 4px;">路径：</span>
          <template v-for="(crumb, idx) in dirPickerBreadcrumb" :key="idx">
            <span v-if="idx > 0" style="color: #999;">›</span>
            <NButton
              text
              size="tiny"
              @click="navigateToDir(crumb.path)"
              :style="{ color: idx === dirPickerBreadcrumb.length - 1 ? '#333' : '#1890ff', fontWeight: idx === dirPickerBreadcrumb.length - 1 ? '500' : 'normal' }"
            >
              {{ crumb.label }}
            </NButton>
          </template>
        </div>

        <!-- Directory list -->
        <div style="max-height: 300px; overflow-y: auto; border: 1px solid #e8e8e8; border-radius: 6px;">
          <div v-if="dirPickerLoading" style="padding: 40px; text-align: center; color: #999;">加载中...</div>
          <div v-else-if="dirPickerDirs.length === 0" style="padding: 40px; text-align: center; color: #999;">此目录为空或无法访问</div>
          <div
            v-else
            v-for="entry in dirPickerDirs"
            :key="entry.path"
            style="padding: 8px 12px; display: flex; align-items: center; gap: 8px; cursor: pointer; border-bottom: 1px solid #f0f0f0; font-size: 14px;"
            :style="{ background: entry.path === basePath ? '#e6f7ff' : 'transparent' }"
            @click="navigateToDir(entry.path)"
          >
            <span style="font-size: 16px;">📁</span>
            <span style="flex: 1;">{{ entry.name }}</span>
            <NButton size="tiny" @click.stop="selectDir(entry.path)">选择</NButton>
          </div>
        </div>
      </div>

      <template #footer>
        <NSpace justify="end">
          <NButton @click="closeDirPicker">取消</NButton>
        </NSpace>
      </template>
    </NModal>
```

- [ ] **Step 6: Verify — start backend and frontend, test the flow**

```bash
# Terminal 1: Start backend
cd mateclaw-dev/mateclaw-server && mvn spring-boot:run

# Terminal 2: Start frontend
cd AIagent_frontend && npm run dev
```

Test steps:
1. Open http://localhost:3000 → login → go to Settings
2. Observe green "当前工作目录" text shows saved basePath (if any)
3. Click "浏览" button → Modal opens showing directory list
4. Click a directory name to navigate into it → breadcrumb updates
5. Click "选择" button on a directory → Modal closes, path fills input
6. Type a path manually → works as before
7. Click "保存" → API saves → success message
8. Refresh page → saved path displays correctly

- [ ] **Step 7: Commit**

```bash
git add AIagent_frontend/src/views/SettingsView.vue
git commit -m "feat: add directory picker to working directory setting"
```
