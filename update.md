# 📋 CICD Platform — Change Log

> **Branch**: `viraj`  
> **Last Updated**: 2026-09-23

---

## [2026-09-23] — Agent Bug Fixes & Type Safety

### 🐍 Agents — LLM Client (`agents/llm_client.py`)
- **Fixed** type mismatch error: `payload` dict annotated as `dict[str, Any]` to resolve `list[dict[str, str]]` not assignable to `float | int` Pyright error.
- **Added** `Any` to `typing` imports.
- **Added** keyword-only marker (`*`) to `call_llm()` to prevent positional argument misuse.
- **Added** configurable `timeout` parameter to `call_llm()` and `_call_gemini()` (default: `30s`, previously hardcoded `12s`).
- **Improved** retry logic: primary model now retries up to 3× on HTTP 429 with incremental backoff (1s, 2s, 3s); secondary models fail fast.
- **Improved** error handling: separate treatment of 429 (rate-limit → retry) vs 503 (unavailable → next model).
- **Added** `_redact()` helper to strip leaked API key fragments from error messages.

### 🔍 Agents — Code Review Agent (`agents/code_review_agent/reviewer.py`)
- **Fixed** `AttributeError` on `f.severity.upper()` — now safely extracts `.value` from `Severity` enum before calling `.upper()`.
- **Removed** unused imports: `DiffFile`, `FindingCategory`.
- **Added** `timeout=45.0` to the LLM call in `_execute_llm_and_build_result()` to prevent review timeouts on large PRs.
- **Fixed** type narrowing issue in `_merge_findings()` — explicitly typed `sig` tuple and reconstructed it in `seen_signatures.add()`.

### ⚙️ Config — Pyright (`pyrightconfig.json`) `[NEW]`
- Added Pyright configuration scoped to the `agents/` directory.
- Configured `extraPaths` for module resolution (agents dir + site-packages).
- Set Python version to `3.12`, platform to `Windows`.

---

## [2026-09-23] — Authentication System & Login UI Overhaul

### ☕ Backend — Auth System (`backend/`)

#### `AuthController.java`
- **Added** email/password sign-up endpoint (`POST /api/auth/signup`) with BCrypt hashing.
- **Added** email/password login endpoint (`POST /api/auth/login`).
- **Added** Google OAuth login endpoint (`POST /api/auth/google`) with Google ID token verification.
- **Refactored** user response building into shared `buildUserResponse()` helper method.
- **Added** `authProvider` field to user response payload.

#### `User.java` (Model)
- **Added** fields: `passwordHash`, `authProvider`, `emailVerified`, `googleId`.
- **Changed** `githubId` from `NOT NULL` to nullable (supports non-GitHub auth).
- **Added** `email` unique constraint.
- Defaults: `authProvider = "GITHUB"`, `emailVerified = false`.

#### `UserRepository.java`
- **Added** `findByEmail()` and `findByGoogleId()` query methods.

#### DTOs `[NEW]`
- **Created** `LoginRequest.java` — email + password DTO.
- **Created** `SignUpRequest.java` — name + email + password DTO.
- **Created** `GoogleTokenRequest.java` — Google credential DTO.

#### `application.yml`
- **Added** Google OAuth client ID configuration (`google.client.id`).

#### `.env.example`
- **Added** `GOOGLE_CLIENT_ID` placeholder.

### 🎨 Frontend — Login Page Redesign (`frontend/`)

#### `LoginPage.jsx`
- **Redesigned** from feature-showcase landing page to full authentication UI.
- **Added** tabbed Login / Sign Up forms with animated transitions.
- **Added** form validation with inline error messages.
- **Added** password visibility toggles (show/hide).
- **Added** password strength indicator (weak / fair / strong / very strong).
- **Added** Google OAuth button with `@react-oauth/google` integration.
- **Added** loading spinners on form submission.
- **Added** terms of service / privacy policy footer.

#### `LoginPage.css`
- **Major overhaul** — 627 lines of new/updated styles.
- Glassmorphism card design with dark theme.
- Custom input styling with icon integration.
- Animated tab indicators, error shake animations.
- Password strength bar with color-coded levels.
- OAuth button styles (GitHub dark, Google white).
- Responsive layout adjustments.

#### `App.jsx`
- **Updated** routing and component integration for new auth flow.

#### `github.js` `[NEW]`
- **Created** GitHub API helper module (`frontend/src/api/github.js`).

#### `index.html`
- Minor updates (meta tags / script references).

---

## [2026-09-11] — Pipeline Agent Integration

> Commit: `f30eef6` — *added pipeline agent successfully and backend*

- Integrated pipeline generation agent with backend services.
- Added pipeline agent backend API endpoints.

---

## [2026-09-04] — Code Review Agent & Error Fixes

> Commit: `c418341` — *adding code review md file*  
> Commit: `b8c1c1e` — *fixing errors*

- Added `code_review_agent_guide.md` documentation.
- Fixed assorted runtime errors across agents.

---

## [2026-08-16] — Initial Integration

> Commit: `21fad2a` — *integrate pipeline generation agent repository context api & helper UI*

- Initial integration of pipeline generation agent.
- Repository context API implementation.
- Helper UI components.

---

## 📁 Project Structure

```
CICD_Platform/
├── agents/                    # Python AI agents
│   ├── llm_client.py          # Shared LLM client (Gemini API)
│   ├── code_review_agent/     # Automated PR code review
│   ├── log_analysis_agent/    # Log parsing & analysis
│   └── pipeline_agent/        # CI/CD pipeline generation
├── backend/                   # Spring Boot (Java) API server
│   └── src/main/java/com/cicd/platform/
│       ├── controller/        # REST controllers
│       ├── dto/               # Request/Response DTOs
│       ├── model/             # JPA entities
│       └── repository/        # Data access layer
├── frontend/                  # React (Vite) UI
│   └── src/
│       ├── pages/             # Page components
│       └── api/               # API helpers
├── pyrightconfig.json         # Python type-checking config
└── update.md                  # ← This file
```
