package org.reactome.curation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.junit.jupiter.api.Test;
import org.reactome.curation.UserManager;
import org.reactome.curation.user.model.User;
import org.reactome.curation.user.service.UserService;

class UserManagerTests {

    @Test
    void shouldRequireCommand() {
        UserService userService = new StubUserService();
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> UserManager.execute(new String[0], userService));
        assertEquals("A command is required.", exception.getMessage());
    }

    @Test
    void shouldCreateUserWithRole() {
        StubUserService userService = new StubUserService();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(baos));
        try {
            UserManager.execute(new String[] {"create", "cli-user", "secret", "curator"}, userService);
        }
        finally {
            System.setOut(originalOut);
        }
        assertEquals("cli-user", userService.savedUsername);
        assertEquals("secret", userService.savedPassword);
        assertEquals("curator", userService.savedRole);
        assertEquals("Created user 'cli-user' with role 'curator'.", baos.toString().trim());
    }

    @Test
    void shouldDeleteUser() {
        StubUserService userService = new StubUserService();
        UserManager.execute(new String[] {"delete", "cli-user"}, userService);
        assertEquals("cli-user", userService.deletedUsername);
    }

    @Test
    void shouldChangePassword() {
        StubUserService userService = new StubUserService();
        UserManager.execute(new String[] {"change-password", "cli-user", "new-secret"}, userService);
        assertEquals("cli-user", userService.changedUsername);
        assertEquals("new-secret", userService.changedPassword);
    }

    private static class StubUserService extends UserService {
        private String savedUsername;
        private String savedPassword;
        private String savedRole;
        private String deletedUsername;
        private String changedUsername;
        private String changedPassword;

        @Override
        public User saveUser(String username, String rawPassword, String role) {
            this.savedUsername = username;
            this.savedPassword = rawPassword;
            this.savedRole = role;
            User user = new User();
            user.setUsername(username);
            user.setRole(role);
            return user;
        }

        @Override
        public void deleteUser(String username) {
            this.deletedUsername = username;
        }

        @Override
        public User changePassword(String username, String rawPassword) {
            this.changedUsername = username;
            this.changedPassword = rawPassword;
            User user = new User();
            user.setUsername(username);
            return user;
        }
    }
}
