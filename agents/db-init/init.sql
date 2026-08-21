-- Creates the two databases used by the Spring Boot backend.
-- "login_db" : user accounts (GitHub OAuth sessions)
-- "repo_db"  : repositories, pipelines, analysis reports, deployments

SELECT 'CREATE DATABASE login_db' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'login_db')\gexec
SELECT 'CREATE DATABASE repo_db'  WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'repo_db')\gexec

\c login_db;

CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    github_id BIGINT UNIQUE,
    login VARCHAR(255) UNIQUE,
    email VARCHAR(255) UNIQUE,
    password VARCHAR(255),
    name VARCHAR(255),
    avatar_url VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW()
);

\c repo_db;

CREATE TABLE IF NOT EXISTS connected_repositories (
    id BIGSERIAL PRIMARY KEY,
    installation_id BIGINT NOT NULL,
    owner VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    full_name VARCHAR(255) UNIQUE NOT NULL,
    default_branch VARCHAR(255) DEFAULT 'main',
    is_private BOOLEAN DEFAULT FALSE,
    connected_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS pipelines (
    id BIGSERIAL PRIMARY KEY,
    repository_id BIGINT NOT NULL REFERENCES connected_repositories(id) ON DELETE CASCADE,
    workflow_path VARCHAR(255),
    template_used VARCHAR(255),
    stack_json TEXT,
    workflow_yaml TEXT,
    status VARCHAR(50) NOT NULL DEFAULT 'GENERATED',
    last_run_id BIGINT,
    credits_used DOUBLE PRECISION,
    total_tokens INTEGER,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    pushed_at TIMESTAMP WITHOUT TIME ZONE
);

CREATE TABLE IF NOT EXISTS analysis_reports (
    id BIGSERIAL PRIMARY KEY,
    repository_id BIGINT NOT NULL REFERENCES connected_repositories(id) ON DELETE CASCADE,
    run_id BIGINT,
    agent VARCHAR(100),
    root_cause TEXT,
    impact TEXT,
    suggested_fix TEXT,
    confidence INTEGER,
    simple_fix BOOLEAN,
    error_excerpt TEXT,
    review_report_json TEXT,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS deployments (
    id BIGSERIAL PRIMARY KEY,
    repository_id BIGINT NOT NULL REFERENCES connected_repositories(id) ON DELETE CASCADE,
    version VARCHAR(255) NOT NULL,
    image VARCHAR(255),
    status VARCHAR(50) NOT NULL,
    current BOOLEAN NOT NULL DEFAULT FALSE,
    detail_json TEXT,
    deployed_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW()
);

