"""
Context Parser — transforms RepositoryContext into structured prompt sections
for the code review LLM.
"""

from config import MAX_FILES_FOR_REVIEW, MAX_CHARS_FOR_PROMPT

SKIP_PREFIXES = (
    "node_modules/", "vendor/", "target/", "dist/", "build/",
    ".git/", "__pycache__/", ".next/", ".nuxt/", "coverage/",
    ".idea/", ".vscode/", ".gradle/",
)

SKIP_EXTENSIONS = (
    ".min.js", ".min.css", ".map", ".lock", ".sum",
    ".ico", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp",
    ".woff", ".woff2", ".ttf", ".eot",
    ".zip", ".tar", ".gz", ".jar", ".war", ".class",
    ".pyc", ".pyo", ".so", ".dll", ".exe",
)


def format_directory_tree(structure: list[dict]) -> str:
    """Build a human-readable directory tree."""
    lines = []
    for node in sorted(structure, key=lambda x: x.get("path", "")):
        path = node.get("path", "")
        depth = path.count("/")
        indent = "  " * depth
        suffix = "/" if node.get("type") == "directory" else ""
        lines.append(f"{indent}├── {node.get('name', '')}{suffix}")
    if len(lines) > 120:
        lines = lines[:120]
        lines.append("... (truncated)")
    return "\n".join(lines)


def is_reviewable(file: dict) -> bool:
    """Check if a file should be included in the review prompt."""
    if file.get("type") != "text":
        return False
    if file.get("secret"):
        return False
    if not file.get("content"):
        return False

    path = file.get("path", "")
    name = file.get("name", "")

    if any(path.startswith(p) for p in SKIP_PREFIXES):
        return False
    if any(name.endswith(ext) for ext in SKIP_EXTENSIONS):
        return False

    # Skip very large files (> 50KB)
    size = file.get("size", 0)
    if size > 50000:
        return False

    return True


def format_file_contents(files: list[dict]) -> tuple[str, int, int]:
    """
    Format reviewable file contents for the LLM prompt.

    Returns: (formatted_text, files_reviewed, files_skipped)
    """
    blocks = []
    total_chars = 0
    reviewed = 0
    skipped = 0

    for f in files:
        if not is_reviewable(f):
            skipped += 1
            continue

        if reviewed >= MAX_FILES_FOR_REVIEW:
            skipped += 1
            continue

        content = f["content"]
        if total_chars + len(content) > MAX_CHARS_FOR_PROMPT:
            skipped += 1
            continue

        blocks.append(
            f"--- START FILE: {f['path']} ({f.get('size', 0)} bytes) ---\n"
            f"{content}\n"
            f"--- END FILE: {f['path']} ---"
        )
        total_chars += len(content)
        reviewed += 1

    return "\n\n".join(blocks), reviewed, skipped


def build_truncation_warning(context: dict) -> str:
    """Build a warning string if the context was truncated."""
    if not context.get("truncated"):
        return ""
    return (
        f"WARNING: Repository context was truncated. "
        f"Message: {context.get('message', 'unknown')}. "
        f"Review may be incomplete."
    )
