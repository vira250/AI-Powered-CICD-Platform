package com.cicd.platform.dto;

public class RepositoryTreeNode {

    private String path;
    private String name;
    private String type; // "directory", "text", "binary"

    public RepositoryTreeNode() {
    }

    public RepositoryTreeNode(String path, String name, String type) {
        this.path = path;
        this.name = name;
        this.type = type;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}
