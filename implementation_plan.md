# GitHub OAuth Login & Repository Import Platform

Build a Vercel-like platform where users can **login with GitHub**, **browse & select their repositories**, and the app has **push permissions** back to GitHub. Stack: **Spring Boot 3.x** backend + **React (Vite)** frontend.

---

## Pre-requisite: Create a GitHub OAuth App

> [!IMPORTANT]
> You must register a GitHub OAuth App before this project will work. Follow these steps:

1. Go to **GitHub → Settings → Developer Settings → OAuth Apps → New OAuth App**
2. Fill in:
   - **Application name**: `CICD Platform` (or any name)
   - **Homepage URL**: `http://localhost:5173`
   - **Authorization callback URL**: `http://localhost:8080/api/auth/github/callback`
3. Click **Register Application**
4. Copy the **Client ID**
5. Click **Generate a new client secret** → copy the **Client Secret**
6. You'll paste these into the backend's `application.yml` later

---

## Open Questions

> [!IMPORTANT]
> **Do you have Java 17+ and Maven installed?** Spring Boot 3.x requires Java 17 or higher. If not, I can provide installation guidance.

> [!NOTE]
> The OAuth scope `repo` grants **full read/write access** to all repositories (public + private). There is no read-only scope for private repos on GitHub. This means your app will automatically have push permission once the user authorizes.

---

## Proposed Changes

### Backend — Spring Boot (Java 17+, Maven)

The backend handles OAuth token exchange, stores the GitHub access token in the session, and proxies GitHub API calls.

#### [NEW] `backend/pom.xml`
Maven project configuration with dependencies:
- `spring-boot-starter-web` — REST API
- `spring-boot-starter-security` — Security framework
- `spring-boot-starter-data-jpa` + `h2` — Session/user persistence (H2 for dev)
- `spring-boot-starter-webflux` (WebClient) — HTTP client to call GitHub APIs
- `lombok` — Reduce boilerplate

#### [NEW] `backend/src/main/resources/application.yml`
Configuration file containing:
- GitHub OAuth Client ID & Client Secret (placeholders)
- CORS settings for React frontend (`http://localhost:5173`)
- Server port `8080`

#### [NEW] `backend/src/main/java/.../CicdApplication.java`
Spring Boot main application entry point.

#### [NEW] `backend/src/main/java/.../config/SecurityConfig.java`
Spring Security configuration:
- Disable CSRF for API routes
- Allow unauthenticated access to `/api/auth/**`
- Require authentication for `/api/repos/**` and `/api/user/**`
- Configure CORS for the React frontend
- Session-based authentication (cookie)

#### [NEW] `backend/src/main/java/.../config/CorsConfig.java`
Global CORS configuration to allow the React dev server.

#### [NEW] `backend/src/main/java/.../controller/AuthController.java`
Handles the GitHub OAuth flow:
- `GET /api/auth/github` — Redirects user to GitHub's authorization page with `scope=repo,user:email`
- `GET /api/auth/github/callback?code=xxx` — Exchanges the code for an access token, fetches user profile, stores token in session, redirects to frontend
- `GET /api/auth/me` — Returns the currently logged-in user info
- `POST /api/auth/logout` — Clears session

#### [NEW] `backend/src/main/java/.../controller/RepoController.java`
Handles repository operations:
- `GET /api/repos` — Lists all user repositories (calls GitHub `GET /user/repos` with pagination)
- `GET /api/repos/{owner}/{repo}` — Get details of a specific repo
- `POST /api/repos/import` — Marks a repo as "imported" in our platform
- `GET /api/repos/imported` — Lists all imported repos

#### [NEW] `backend/src/main/java/.../controller/GitPushController.java`
Handles pushing code back to GitHub:
- `POST /api/git/push` — Pushes file content to a repo via GitHub's Contents API (`PUT /repos/{owner}/{repo}/contents/{path}`)

#### [NEW] `backend/src/main/java/.../service/GitHubService.java`
Service layer that wraps GitHub REST API calls using WebClient:
- `exchangeCodeForToken(code)` — Token exchange
- `getUserProfile(token)` — Get user info
- `getUserRepos(token, page, perPage)` — List repos with pagination
- `getRepoDetails(token, owner, repo)` — Single repo details
- `pushFile(token, owner, repo, path, content, message)` — Create/update file via Contents API

#### [NEW] `backend/src/main/java/.../model/User.java`
JPA entity to persist user info (GitHub ID, username, avatar URL, access token).

#### [NEW] `backend/src/main/java/.../model/ImportedRepo.java`
JPA entity to track which repos the user has imported.

#### [NEW] `backend/src/main/java/.../repository/UserRepository.java`
Spring Data JPA repository for User entity.

#### [NEW] `backend/src/main/java/.../repository/ImportedRepoRepository.java`
Spring Data JPA repository for ImportedRepo entity.

---

### Frontend — React (Vite + JavaScript)

A modern, premium UI inspired by Vercel's clean dark-mode design.

#### [NEW] `frontend/` (scaffolded via `npm create vite@latest`)
React project with Vite, JavaScript variant.

#### [NEW] `frontend/src/index.css`
Global design system:
- Dark mode color palette (deep navy/charcoal backgrounds, accent gradients)
- CSS custom properties for colors, spacing, typography
- Glassmorphism card styles
- Smooth animations and transitions
- Google Font: Inter

#### [NEW] `frontend/src/App.jsx`
Main app with React Router:
- `/` — Landing/Login page
- `/dashboard` — Repository list (after auth)
- `/repo/:owner/:name` — Repo detail / import view

#### [NEW] `frontend/src/api/github.js`
API service module with fetch calls to the Spring Boot backend:
- `getUser()`, `getRepos()`, `importRepo()`, `pushFile()`, `logout()`

#### [NEW] `frontend/src/pages/LoginPage.jsx`
Premium landing page with:
- Hero section with animated gradient
- "Login with GitHub" button with GitHub icon
- Animated background elements

#### [NEW] `frontend/src/pages/DashboardPage.jsx`
Repository browser (Vercel-like):
- User profile card (avatar, name, GitHub link)
- Search/filter bar for repositories
- Repository cards showing: name, description, language, stars, visibility (public/private), last updated
- "Import" button on each repo card
- Pagination support
- Imported repos section at the top

#### [NEW] `frontend/src/pages/RepoDetailPage.jsx`
Repo detail view after importing:
- Repo metadata display
- File push interface (select file path, enter content, commit message)
- Push to GitHub button with loading states
- Activity/status log

#### [NEW] `frontend/src/components/Navbar.jsx`
Top navigation bar with user avatar, app logo, and logout.

#### [NEW] `frontend/src/components/RepoCard.jsx`
Reusable repository card component with hover effects and micro-animations.

#### [NEW] `frontend/src/components/SearchBar.jsx`
Search and filter component for repositories.

#### [NEW] `frontend/src/components/ProtectedRoute.jsx`
Route guard that redirects unauthenticated users to login.

---

## Architecture Diagram

```
┌─────────────────┐        ┌─────────────────┐        ┌─────────────────┐
│   React (5173)  │ ──────▶│ Spring Boot     │ ──────▶│  GitHub API     │
│                 │  REST  │    (8080)       │  HTTP  │                 │
│  - Login Page   │◀────── │  - AuthController│◀────── │  - OAuth        │
│  - Dashboard    │        │  - RepoController│        │  - /user/repos  │
│  - Repo Detail  │        │  - GitPushCtrl  │        │  - /contents    │
└─────────────────┘        └─────────────────┘        └─────────────────┘
                              │          ▲
                              ▼          │
                           ┌─────────────────┐
                           │   H2 Database   │
                           │  (Users, Repos) │
                           └─────────────────┘
```

## OAuth Flow

```
User clicks "Login with GitHub"
        │
        ▼
Frontend redirects to → Backend /api/auth/github
        │
        ▼
Backend redirects to → GitHub OAuth /authorize?scope=repo,user:email
        │
        ▼
User authorizes on GitHub
        │
        ▼
GitHub redirects to → Backend /api/auth/github/callback?code=xxx
        │
        ▼
Backend exchanges code for access_token (server-side, secure)
        │
        ▼
Backend stores token + user info in DB & session
        │
        ▼
Backend redirects to → Frontend /dashboard
        │
        ▼
Frontend calls /api/auth/me → gets user info
Frontend calls /api/repos → lists repositories
```

---

## Verification Plan

### Automated Tests
- Run `mvn clean compile` to verify backend compiles
- Run `npm run build` to verify frontend builds

### Manual Verification
1. Start backend (`mvn spring-boot:run`) and frontend (`npm run dev`)
2. Click "Login with GitHub" → should redirect to GitHub
3. After authorization → should redirect back to dashboard with repos listed
4. Search/filter repos → should work in real-time
5. Click "Import" on a repo → should move it to imported section
6. Push a file from the detail page → should appear on GitHub
