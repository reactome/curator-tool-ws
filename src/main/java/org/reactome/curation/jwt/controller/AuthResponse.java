package org.reactome.curation.jwt.controller;

import lombok.Data;

@Data
public class AuthResponse {

    private final String accessToken;
    private final String refreshToken;

    public AuthResponse(String accessToken, String refreshToken) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
    }
}
