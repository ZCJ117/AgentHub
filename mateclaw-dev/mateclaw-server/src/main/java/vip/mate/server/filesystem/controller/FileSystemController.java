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
