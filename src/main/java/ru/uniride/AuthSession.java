package ru.uniride;

public class AuthSession {
    public Student student;
    public String sessionToken;

    public AuthSession(Student student, String sessionToken) {
        this.student = student;
        this.sessionToken = sessionToken;
    }
}
