package org.reactome.curation.user.repository;

import java.util.Optional;

import org.reactome.curation.user.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
}
