package org.reactome.curation.user.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

import lombok.Data;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@Entity
@Data
@Table(name = "users") // User a non-reserved name. The default table name "User" cannot work since it is a reserved keyword in H2.
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    public User(String username, String password) {
        this.username = username;
        this.password = password;
    }

    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    private String password;
    
    @Column(nullable = true)
    private String role; 
}
