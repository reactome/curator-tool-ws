package org.reactome.curation.user.repository;

import org.apache.commons.io.FileUtils;
import org.reactome.curation.user.model.User;
import org.springframework.stereotype.Repository;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Repository
public class UserRepository {
    public User findByEmail(String emailFromUser) {
        ArrayList<String> lines = this.readFile("src/main/resources/users.txt");
        ArrayList<User> users = new ArrayList<>();
        int index = 0;
        for (String value : lines) {
            String[] data = value.split(",");
            String email = data[0];
            String password = data[1];
            User user = new User(email, password);
            user.setPassword(password);
            users.add(user);
            if (email.equals(emailFromUser)) {
                //ArrayList<String> hashes = this.readFile("src/main/resources/hash.txt");
                //user.setStoredHash(hashes.get(index).getBytes(StandardCharsets.UTF_8));
                //ArrayList<String> salts = this.readFile("src/main/resources/salt.txt");
                //user.setStoredSalt(salts.get(index).getBytes());
//                System.out.println(hashes.get(index).getBytes());
//                System.out.println(salts.get(index).getBytes());
                return user;
            }
            index++;
        }

        return new User();
    }

    public User findById(UUID id) {
        return new User();
    }

    public User save(User user) throws IOException {
        this.writeFile("src/main/resources/users.txt", user.getEmail() + "," + user.getPassword());
        String hash = new String(user.getStoredHash());
        System.out.println("saveHash" + hash);
        //FileUtils.writeByteArrayToFile(new File("src/main/resources/hash.txt"), user.getStoredHash());

        this.writeFile("src/main/resources/hash.txt", hash);
        this.writeFile("src/main/resources/salt.txt", Arrays.toString(user.getStoredSalt()));
        return user;
    }

    private ArrayList<String> readFile(String fileName){
        ArrayList<String> lines = new ArrayList<>();
        try {
            File myObj = new File(fileName);
            Scanner myReader = new Scanner(myObj);
            while (myReader.hasNextLine()) {
                String data = myReader.nextLine();
                lines.add(data);
            }
            myReader.close();
        } catch (FileNotFoundException e) {
            System.out.println("An error occurred.");
            e.printStackTrace();
        }
        return lines;
    }

    private void writeFile(String fileName, String fileContent) {
        try {
            FileWriter myWriter = new FileWriter(fileName);
            myWriter.write(fileContent);
            myWriter.close();
        } catch (IOException e) {
            System.out.println("An error occurred.");
            e.printStackTrace();
        }
    }
}
