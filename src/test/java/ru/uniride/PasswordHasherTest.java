package ru.uniride;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class PasswordHasherTest {

    @Test
    void generateSaltReturnsRandomBase64EncodedSixteenBytes() {
        String first = PasswordHasher.generateSalt();
        String second = PasswordHasher.generateSalt();

        assertEquals(16, Base64.getDecoder().decode(first).length);
        assertEquals(16, Base64.getDecoder().decode(second).length);
        assertNotEquals(first, second);
    }

    @Test
    void hashIsDeterministicForSamePasswordAndSalt() {
        String salt = PasswordHasher.generateSalt();

        assertEquals(PasswordHasher.hash("secret", salt), PasswordHasher.hash("secret", salt));
    }

    @Test
    void matchesAcceptsCorrectPasswordAndRejectsWrongOrMissingData() {
        String salt = PasswordHasher.generateSalt();
        String hash = PasswordHasher.hash("secret", salt);

        assertTrue(PasswordHasher.matches("secret", salt, hash));
        assertFalse(PasswordHasher.matches("wrong", salt, hash));
        assertFalse(PasswordHasher.matches("secret", null, hash));
        assertFalse(PasswordHasher.matches("secret", salt, null));
    }
}
