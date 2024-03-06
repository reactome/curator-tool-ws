package org.reactome.curation.user.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class User {

    private UUID id;

    private String email;

    private String mobileNumber;
    private String password;

    private byte[] storedHash;
    private byte[] storedSalt;

    public User(String email, String mobileNumber) {
        this.email = email;
        this.mobileNumber = mobileNumber;
    }
}
