package org.reactome.curation;

import org.reactome.curation.user.model.User;
import org.reactome.curation.user.service.UserService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

public class UserManager {

    public static void main(String[] args) {
        int exitCode = 0;
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(CuratorToolWsApplication.class)
                .web(WebApplicationType.NONE)
                .run(args)) {
            UserService userService = context.getBean(UserService.class);
            execute(args, userService);
        }
        catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            printUsage();
            exitCode = 1;
        }
        catch (Exception e) {
            System.err.println("User manager failed: " + e.getMessage());
            e.printStackTrace(System.err);
            exitCode = 1;
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static void execute(String[] args, UserService userService) {
        if (args == null || args.length == 0) {
            throw new IllegalArgumentException("A command is required.");
        }
        String command = args[0];
        switch (command) {
            case "create":
                createUser(args, userService);
                return;
            case "list":
                listUsers(userService);
                return;
            case "delete":
                deleteUser(args, userService);
                return;
            case "change-password":
                changePassword(args, userService);
                return;
            case "help":
            case "--help":
            case "-h":
                printUsage();
                return;
            default:
                throw new IllegalArgumentException("Unsupported command: " + command);
        }
    }

    private static void createUser(String[] args, UserService userService) {
        requireArgumentCount(args, 3, "create requires <username> <password> [role].");
        String username = requireNonBlank(args[1], "Username cannot be blank.");
        String password = requireNonBlank(args[2], "Password cannot be blank.");
        String role = args.length >= 4 ? requireNonBlank(args[3], "Role cannot be blank.") : null;
        User user = userService.saveUser(username, password, role);
        System.out.println("Created user '" + user.getUsername() + "'" + formatRole(user.getRole()) + ".");
    }

    private static void deleteUser(String[] args, UserService userService) {
        requireArgumentCount(args, 2, "delete requires <username>.");
        String username = requireNonBlank(args[1], "Username cannot be blank.");
        userService.deleteUser(username);
        System.out.println("Deleted user '" + username + "'.");
    }

    private static void listUsers(UserService userService) {
        System.out.println("Username\tRole");
        for (User user : userService.findAllUsers()) {
            System.out.println(user.getUsername() + "\t" + formatRoleName(user.getRole()));
        }
    }

    private static void changePassword(String[] args, UserService userService) {
        requireArgumentCount(args, 3, "change-password requires <username> <newPassword>.");
        String username = requireNonBlank(args[1], "Username cannot be blank.");
        String password = requireNonBlank(args[2], "Password cannot be blank.");
        userService.changePassword(username, password);
        System.out.println("Updated password for user '" + username + "'.");
    }

    private static void requireArgumentCount(String[] args, int minimumLength, String message) {
        if (args.length < minimumLength) {
            throw new IllegalArgumentException(message);
        }
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String formatRole(String role) {
        return role == null || role.isBlank() ? "" : " with role '" + role + "'";
    }

    private static String formatRoleName(String role) {
        return role == null || role.isBlank() ? "-" : role;
    }

    private static void printUsage() {
        System.err.println("Usage:");
        System.err.println("  create <username> <password> [role]");
        System.err.println("  list");
        System.err.println("  delete <username>");
        System.err.println("  change-password <username> <newPassword>");
    }
}
