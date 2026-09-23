package com.cicd.platform.dto;

public class GoogleTokenRequest {

    private String credential;

    public GoogleTokenRequest() {}

    public GoogleTokenRequest(String credential) {
        this.credential = credential;
    }

    public String getCredential() { return credential; }
    public void setCredential(String credential) { this.credential = credential; }
}
