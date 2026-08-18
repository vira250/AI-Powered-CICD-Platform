-- Creates the two databases used by the Spring Boot backend.
-- "CICD Login" : user accounts (GitHub OAuth sessions)
-- "CICD repo"  : repositories, pipelines, analysis reports, deployments
CREATE DATABASE "CICD Login";
CREATE DATABASE "CICD repo";
