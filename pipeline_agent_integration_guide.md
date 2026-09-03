# Integration Guide: Python Pipeline Generation Agent

This document explains how the Python-based **Pipeline Generation Agent** and **Orchestrator** consume the repository context built by Spring Boot to generate a target CI/CD pipeline.

---

## 🔄 Architectural Workflow

```
┌────────────┐               ┌─────────────┐               ┌──────────────┐
│            │ (1) Fetch Context│             │ (2) Parse Context│  Pipeline    │
│  Spring    │──────────────>│  Python     │──────────────>│  Generation  │
│  Boot      │               │  Orchestrator│               │  AI Agent    │
│  Backend   │<──────────────│             │<──────────────│  (LLM model) │
│            │ (4) Push YAML │             │ (3) Ret. YAML │              │
└────────────┘               └─────────────┘               └──────────────┘
```

---

## 📡 1. The Spring Boot API Endpoint

The orchestrator calls this endpoint to get the complete repository data:

```http
GET http://localhost:8080/api/repos/{owner}/{repo}/context?branch={branch}
Authorization: Bearer <session_token>
Accept: application/json
```

---

## 📦 2. JSON Payload Structure (`RepositoryContext`)

The response returns a single JSON object containing:

```json
{
  "repository": {
    "githubRepositoryId": 123456,
    "owner": "example-owner",
    "repositoryName": "java-spring-app",
    "branch": "main",
    "commitSha": "a83f91c987654321..."
  },
  "structure": [
    { "path": "src", "name": "src", "type": "directory" },
    { "path": "src/main/java/App.java", "name": "App.java", "type": "text" },
    { "path": "pom.xml", "name": "pom.xml", "type": "text" },
    { "path": "assets/logo.png", "name": "logo.png", "type": "binary" },
    { "path": ".env", "name": ".env", "type": "text" }
  ],
  "files": [
    {
      "path": "src/main/java/App.java",
      "name": "App.java",
      "type": "text",
      "size": 1500,
      "sha": "blob_sha_123",
      "content": "package com.example;\npublic class App { ... }",
      "secret": false
    },
    {
      "path": "assets/logo.png",
      "name": "logo.png",
      "type": "binary",
      "size": 18234,
      "sha": "blob_sha_456",
      "content": null,
      "secret": false
    },
    {
      "path": ".env",
      "name": ".env",
      "type": "text",
      "size": 120,
      "sha": "blob_sha_789",
      "content": "[REDACTED: Sensitive file contents masked for security]",
      "secret": true
    }
  ],
  "totalBytes": 4020,
  "truncated": false,
  "message": "Complete repository context successfully constructed from GitHub."
}
```

---

## 🐍 3. How the Python Agent Consumes the Payload

The agent parses the JSON context payload and constructs the input prompt for the Large Language Model (LLM).

### Step A: Parse and Format the Directory Structure
Create a human-readable directory tree for the LLM to understand project hierarchy:

```python
def format_directory_tree(structure_list):
    # Returns an ASCII tree structure of the repository
    lines = []
    for node in sorted(structure_list, key=lambda x: x["path"]):
        depth = node["path"].count("/")
        indent = "  " * depth
        node_type = "/" if node["type"] == "directory" else ""
        lines.append(f"{indent}├── {node['name']}{node_type} ({node['type']})")
    return "\n".join(lines)
```

### Step B: Format File Contents
Concatenate text file paths and contents to inject into the LLM system prompt:

```python
def format_file_contents(files_list):
    content_blocks = []
    for file in files_list:
        if file["type"] == "text" and not file["secret"]:
            block = f"--- START FILE: {file['path']} ---\n"
            block += file["content"]
            block += f"\n--- END FILE: {file['path']} ---\n"
            content_blocks.append(block)
        elif file["type"] == "binary":
            content_blocks.append(f"--- BINARY FILE: {file['path']} (Size: {file['size']} bytes) ---")
        elif file["secret"]:
            content_blocks.append(f"--- SENSITIVE FILE MASKED: {file['path']} ---")
    return "\n\n".join(content_blocks)
```

---

## 🤖 4. The LLM Prompt Strategy

The agent sends the compiled prompt to the LLM (e.g. Gemini 1.5 Pro, Claude 3.5 Sonnet, or GPT-4o):

```python
def generate_pipeline_yaml(repo_context):
    tree_str = format_directory_tree(repo_context["structure"])
    files_str = format_file_contents(repo_context["files"])
    
    prompt = f"""
You are a Senior Devops Engineer. Analyze the repository structure and file contents below.
Generate the correct GitHub Actions CI/CD pipeline YAML configuration.

DIRECTORY STRUCTURE:
{tree_str}

REPRESENTATIVE FILE CONTENTS:
{files_str}

REQUIREMENTS:
- Identify the project language, framework, build tool, and runtime settings.
- Write a complete production-ready GitHub Actions YAML.
- Respond ONLY with the raw YAML inside code blocks.
"""
    # Call your LLM API here
    # response = client.generate_content(prompt)
    # return response.text
```

---

## 📤 5. Writing the Pipeline Back to GitHub

Once the agent generates the pipeline YAML, the orchestrator writes the file back to the repository using the Spring Boot Git Push API:

```http
POST http://localhost:8080/api/git/push
Authorization: Bearer <session_token>
Content-Type: application/json

{
  "owner": "example-owner",
  "repo": "java-spring-app",
  "path": ".github/workflows/deploy.yml",
  "content": "name: CI/CD Pipeline\non:\n  push:\n    branches: [ main ]\n...",
  "message": "Create CI/CD Pipeline via DeployHub Agent",
  "branch": "main"
}
```
