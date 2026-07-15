package ru.uniride;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class Database {
    // Реальные значения задаются переменными окружения DB_URL/DB_USER/DB_PASSWORD -
    // здесь только заглушки, чтобы в публичном репозитории не оказался настоящий пароль
    private static final String URL = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/uniride");
    private static final String USER = System.getenv().getOrDefault("DB_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD", "changeme");

    static {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("PostgreSQL driver not found", e);
        }
    }

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    public static void initSchema() {
        String createTable = """
            CREATE TABLE IF NOT EXISTS students (
                id BIGSERIAL PRIMARY KEY,
                first_name VARCHAR(100) NOT NULL,
                last_name VARCHAR(100) NOT NULL,
                group_number VARCHAR(50) NOT NULL,
                role VARCHAR(20) NOT NULL DEFAULT 'STUDENT' CHECK (role IN ('STUDENT', 'ADMIN')),
                status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
                created_at TIMESTAMP NOT NULL DEFAULT now()
            )
            """;
        // Отдельные ALTER TABLE, чтобы не терять уже существующие записи при добавлении новых полей
        String addPhone = "ALTER TABLE students ADD COLUMN IF NOT EXISTS phone_number VARCHAR(20)";
        String addGradebook = "ALTER TABLE students ADD COLUMN IF NOT EXISTS gradebook_number VARCHAR(50)";
        String addPasswordHash = "ALTER TABLE students ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255)";
        String addPasswordSalt = "ALTER TABLE students ADD COLUMN IF NOT EXISTS password_salt VARCHAR(64)";
        String addSessionToken = "ALTER TABLE students ADD COLUMN IF NOT EXISTS session_token VARCHAR(64)";
        String addNoShowCount = "ALTER TABLE students ADD COLUMN IF NOT EXISTS no_show_count INTEGER NOT NULL DEFAULT 0";
        String uniquePhoneIndex = "CREATE UNIQUE INDEX IF NOT EXISTS idx_students_phone_number ON students (phone_number)";
        String uniqueGradebookIndex = "CREATE UNIQUE INDEX IF NOT EXISTS idx_students_gradebook_number ON students (gradebook_number)";
        String uniqueSessionTokenIndex = "CREATE UNIQUE INDEX IF NOT EXISTS idx_students_session_token ON students (session_token)";

        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(createTable);
            stmt.execute(addPhone);
            stmt.execute(addGradebook);
            stmt.execute(addPasswordHash);
            stmt.execute(addPasswordSalt);
            stmt.execute(addSessionToken);
            stmt.execute(addNoShowCount);
            stmt.execute(uniquePhoneIndex);
            stmt.execute(uniqueGradebookIndex);
            stmt.execute(uniqueSessionTokenIndex);
            System.out.println("Схема базы данных проверена/создана.");
        } catch (SQLException e) {
            throw new RuntimeException("Не удалось инициализировать схему БД", e);
        }
    }
}
