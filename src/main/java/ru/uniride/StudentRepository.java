package ru.uniride;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class StudentRepository {

    public static AuthSession register(String firstName, String lastName, String groupNumber,
                                        String phoneNumber, String gradebookNumber, String password) throws SQLException {
        String salt = PasswordHasher.generateSalt();
        String passwordHash = PasswordHasher.hash(password, salt);
        String sessionToken = UUID.randomUUID().toString();

        String sql = "INSERT INTO students (first_name, last_name, group_number, phone_number, gradebook_number, " +
                "password_hash, password_salt, session_token, role, status) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'STUDENT', 'PENDING') RETURNING id, status";
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, firstName);
            ps.setString(2, lastName);
            ps.setString(3, groupNumber);
            ps.setString(4, phoneNumber);
            ps.setString(5, gradebookNumber);
            ps.setString(6, passwordHash);
            ps.setString(7, salt);
            ps.setString(8, sessionToken);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Student student = new Student(rs.getLong("id"), firstName, lastName, groupNumber, phoneNumber,
                        gradebookNumber, "STUDENT", rs.getString("status"));
                return new AuthSession(student, sessionToken);
            }
        }
    }

    public static AuthSession verifyLogin(String gradebookNumber, String password) throws SQLException {
        String sql = "SELECT id, first_name, last_name, group_number, phone_number, gradebook_number, role, status, " +
                "password_hash, password_salt FROM students WHERE gradebook_number = ?";
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, gradebookNumber);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                if (!PasswordHasher.matches(password, rs.getString("password_salt"), rs.getString("password_hash"))) {
                    return null;
                }
                Student student = mapRow(rs);
                String sessionToken = issueSessionToken(student.id);
                return new AuthSession(student, sessionToken);
            }
        }
    }

    // Непредсказуемый токен вместо порядкового ID - checkStatus по нему не даёт
    // перебором прочитать данные чужих пользователей
    private static String issueSessionToken(long studentId) throws SQLException {
        String token = UUID.randomUUID().toString();
        String sql = "UPDATE students SET session_token = ? WHERE id = ?";
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, token);
            ps.setLong(2, studentId);
            ps.executeUpdate();
        }
        return token;
    }

    public static Student findBySessionToken(String sessionToken) throws SQLException {
        String sql = "SELECT id, first_name, last_name, group_number, phone_number, gradebook_number, role, status " +
                "FROM students WHERE session_token = ?";
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionToken);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return mapRow(rs);
            }
        }
    }

    public static List<Student> findAll() throws SQLException {
        String sql = "SELECT id, first_name, last_name, group_number, phone_number, gradebook_number, role, status " +
                "FROM students ORDER BY created_at ASC";
        List<Student> result = new ArrayList<>();
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(mapRow(rs));
            }
        }
        return result;
    }

    public static boolean updateStatus(long id, String status) throws SQLException {
        String sql = "UPDATE students SET status = ? WHERE id = ? AND status = 'PENDING'";
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setLong(2, id);
            return ps.executeUpdate() > 0;
        }
    }

    // Используется и для отказа в заявке, и для отзыва доступа - в обоих случаях
    // запись просто пропадает из базы, а не зависает в статусе REJECTED
    public static boolean deleteStudent(long id) throws SQLException {
        String sql = "DELETE FROM students WHERE id = ?";
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    private static Student mapRow(ResultSet rs) throws SQLException {
        return new Student(
                rs.getLong("id"),
                rs.getString("first_name"),
                rs.getString("last_name"),
                rs.getString("group_number"),
                rs.getString("phone_number"),
                rs.getString("gradebook_number"),
                rs.getString("role"),
                rs.getString("status")
        );
    }
}
