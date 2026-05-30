#!/usr/bin/env python3
"""
Migrate mateclaw-server Java files to domain and infrastructure modules.
Only moves files and updates package declarations and imports.
Never modifies business logic.
"""
import os, re, shutil
from pathlib import Path

SERVER = Path("D:/code/Loom/mateclaw-dev/mateclaw-server/src/main/java/vip/mate")
DOMAIN = Path("D:/code/Loom/mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain")
INFRA = Path("D:/code/Loom/mateclaw-dev/mateclaw-infrastructure/src/main/java/vip/mate/infra")
SVRDST = Path("D:/code/Loom/mateclaw-dev/mateclaw-server/src/main/java/vip/mate/server")

# ========== Infrastructure file rules ==========
INFRA_PATHS = {
    # Channel adapters (concrete implementations)
    "channel/dingtalk", "channel/feishu", "channel/wecom", "channel/discord",
    "channel/slack", "channel/telegram", "channel/qq",
    "channel/weixin", "channel/webchat/WebChatChannelAdapter.java",
    "channel/web/WebChannelAdapter.java",
    # Channel infrastructure
    "channel/leader", "channel/health", "channel/media", "channel/verifier",
    "channel/qrcode", "channel/cards",
    # LLM client implementations
    "llm/chatgpt", "llm/chatmodel", "llm/embedding", "llm/gemini",
    "llm/anthropic", "llm/oauth", "llm/failover",
    "llm/config/OllamaAutoDiscoveryRunner.java",
    # Tool providers (concrete external implementations)
    "tool/search", "tool/image/provider", "tool/video/provider",
    "tool/music/provider", "tool/model3d/provider",
    "tool/mcp/runtime", "tool/browser",
    # External adapter clients
    "acp/client", "agent/cli", "agent/bridge",
    "skill/acp", "skill/installer", "skill/mcp",
}

# ========== Controller/server file patterns ==========
SERVER_PATHS = {
    "MateClawApplication.java",
}

# ========== Pattern-based rules ==========
def is_infrastructure(rel_path):
    """Check if file belongs in infrastructure module."""
    for p in INFRA_PATHS:
        if rel_path.startswith(p):
            return True
    # Special files
    basename = os.path.basename(rel_path)
    if basename in {"MybatisPlusConfig.java", "ShedLockConfig.java",
                     "FlywayRepairConfig.java", "DatabaseBootstrapRunner.java"}:
        # Only if in config/ dir
        if "config/" in rel_path:
            return True
    # Channel web infra (not ChatController or ChatStreamTracker)
    if rel_path == "channel/web/WebChannelAdapter.java":
        return True
    return False

def is_controller(rel_path):
    """Check if file is a Controller (belongs in server)."""
    basename = os.path.basename(rel_path)
    # Any file in a controller/ package
    if "/controller/" in rel_path or "\\controller\\" in rel_path:
        return True
    # Web controllers
    if rel_path in {"channel/web/ChatController.java", "channel/web/ChatStreamTracker.java"}:
        return True
    if rel_path in {"channel/webchat/WebChatController.java"}:
        return True
    if basename == "MateClawApplication.java":
        return True
    return False

def get_target_module(rel_path):
    """Determine target module for a file."""
    if is_controller(rel_path):
        return "server"
    if is_infrastructure(rel_path):
        return "infrastructure"
    return "domain"

def get_new_package(rel_path, module):
    """Compute the new package from the relative file path."""
    # rel_path like: "agent/service/AgentService.java"
    parts = rel_path.split("/")
    # Remove filename
    pkg_parts = parts[:-1]
    # Remove .java extension from parts
    pkg_parts = [p.replace(".java", "") for p in pkg_parts]

    if module == "domain":
        return "vip.mate.domain." + ".".join(pkg_parts)
    elif module == "infrastructure":
        return "vip.mate.infra." + ".".join(pkg_parts)
    elif module == "server":
        # server module: controller packages become vip.mate.server.<domain>
        new_parts = []
        for p in pkg_parts:
            if p == "controller":
                continue
            new_parts.append(p)
        return "vip.mate.server." + ".".join(new_parts)
    return None

def get_target_src(module):
    if module == "domain":
        return DOMAIN
    elif module == "infrastructure":
        return INFRA
    elif module == "server":
        return SVRDST
    return None

def update_imports(content, rel_path, target_module):
    """Update import statements based on where the file is going."""
    # Common imports that changed
    content = content.replace("import vip.mate.exception.", "import vip.mate.common.exception.")
    content = content.replace("import vip.mate.i18n.", "import vip.mate.domain.system.")

    # Map of domain packages
    domain_pkgs = [
        "acp", "agent", "approval", "artifact", "audit", "auth",
        "channel", "dashboard", "group", "llm", "memory", "message",
        "notification", "orchestrator", "planning", "skill", "system",
        "task", "tool", "wiki", "workspace"
    ]

    for dp in domain_pkgs:
        old_import = f"import vip.mate.{dp}."
        new_import = f"import vip.mate.domain.{dp}."
        content = content.replace(old_import, new_import)

    # Fix common imports that reference old i18n
    content = content.replace("import vip.mate.domain.system.I18nService;", "import vip.mate.domain.system.I18nService;")

    return content

def migrate_file(src_file):
    """Process a single Java file: determine target, compute new package, move."""
    rel_path = str(src_file.relative_to(SERVER)).replace("\\", "/")
    module = get_target_module(rel_path)

    target_src = get_target_src(module)
    if target_src is None:
        return None

    # Compute target path
    target_dir = target_src / src_file.relative_to(SERVER).parent
    target_file = target_dir / src_file.name

    # Read content
    content = src_file.read_text(encoding="utf-8")

    # Update package declaration
    new_pkg = get_new_package(rel_path, module)
    if new_pkg:
        # Find the package declaration and replace it
        content = re.sub(
            r'^package\s+vip\.mate(\.[a-z0-9_.]+)?\s*;',
            f'package {new_pkg};',
            content,
            flags=re.MULTILINE
        )

    # Update imports based on module
    content = update_imports(content, rel_path, module)

    # Write to target
    target_dir.mkdir(parents=True, exist_ok=True)
    target_file.write_text(content, encoding="utf-8")

    # Remove from source if not staying in server
    if module != "server":
        src_file.unlink()

    return module

def main():
    # Walk all Java files in server
    java_files = list(SERVER.rglob("*.java"))
    stats = {"domain": 0, "infrastructure": 0, "server": 0, "skipped": 0}

    # Skip files in common/ (already moved)
    for src_file in java_files:
        rel = str(src_file.relative_to(SERVER)).replace("\\", "/")
        if rel.startswith("common/"):
            continue
        if rel.startswith("exception/"):
            continue
        if rel.startswith("i18n/"):
            continue

        module = migrate_file(src_file)
        if module:
            stats[module] += 1
        else:
            stats["skipped"] += 1

    print(f"Migration complete:")
    print(f"  Domain: {stats['domain']}")
    print(f"  Infrastructure: {stats['infrastructure']}")
    print(f"  Server (kept/moved): {stats['server']}")
    print(f"  Skipped: {stats['skipped']}")

if __name__ == "__main__":
    main()
