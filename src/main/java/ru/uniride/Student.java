package ru.uniride;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Student {
    public Long id;
    public String firstName;
    public String lastName;
    public String groupNumber;
    public String phoneNumber;
    public String gradebookNumber;
    public String role;
    public String status;
    public int noShowCount;

    public Student() {}

    public Student(Long id, String firstName, String lastName, String groupNumber, String phoneNumber,
                    String gradebookNumber, String role, String status, int noShowCount) {
        this.id = id;
        this.firstName = firstName;
        this.lastName = lastName;
        this.groupNumber = groupNumber;
        this.phoneNumber = phoneNumber;
        this.gradebookNumber = gradebookNumber;
        this.role = role;
        this.status = status;
        this.noShowCount = noShowCount;
    }
}
