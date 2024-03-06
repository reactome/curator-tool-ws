package org.reactome.curation.user.repository;

import org.reactome.curation.user.model.User;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class UserRepository {
    //    @Query(
//            "" +
//                    "SELECT CASE WHEN COUNT(u) > 0 THEN " +
//                    "TRUE ELSE FALSE END " +
//                    "FROM UserEntity u " +
//                    "WHERE u.email = ?1"
//    )
    Boolean selectExistsEmail(String email) {
        return null;
    }

    // @Column(unique = true) is needed in entity
    public User findByEmail(String email) {
        return null;
    }

    public User findById(UUID id) {
        return new User();
    }
}
